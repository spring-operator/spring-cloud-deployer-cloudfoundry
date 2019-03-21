/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.deployer.spi.cloudfoundry;

import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.DOMAIN_PROPERTY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.HEALTHCHECK_PROPERTY_KEY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.HOST_PROPERTY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.NO_ROUTE_PROPERTY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.ROUTE_PATH_PROPERTY;
import static org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties.USE_SPRING_APPLICATION_JSON_KEY;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.applications.UpdateApplicationRequest;
import org.cloudfoundry.client.v2.applications.UpdateApplicationResponse;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.ApplicationHealthCheck;
import org.cloudfoundry.operations.applications.DeleteApplicationRequest;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.applications.InstanceDetail;
import org.cloudfoundry.operations.applications.PushApplicationRequest;
import org.cloudfoundry.operations.applications.StartApplicationRequest;
import org.cloudfoundry.operations.services.BindServiceInstanceRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.util.StringUtils;

/**
 * A deployer that targets Cloud Foundry using the public API.
 *
 * @author Eric Bottard
 * @author Greg Turnquist
 * @author Ben Hale
 */
public class CloudFoundryAppDeployer extends AbstractCloudFoundryDeployer implements AppDeployer {

	private static final Logger logger = LoggerFactory.getLogger(CloudFoundryAppDeployer.class);

	private final AppNameGenerator applicationNameGenerator;

	private final CloudFoundryClient client;

	private final CloudFoundryOperations operations;

	public CloudFoundryAppDeployer(AppNameGenerator applicationNameGenerator, CloudFoundryClient client, CloudFoundryDeploymentProperties deploymentProperties,
								   CloudFoundryOperations operations) {
		super(deploymentProperties);
		this.operations = operations;
		this.client = client;
		this.applicationNameGenerator = applicationNameGenerator;
	}

	@Override
	public String deploy(AppDeploymentRequest request) {
		logger.trace("Entered deploy: Deploying AppDeploymentRequest: AppDefinition = {}, Resource = {}, Deployment Properties = {}",
			request.getDefinition(), request.getResource(), request.getDeploymentProperties());
		String deploymentId = deploymentId(request);

		logger.trace("deploy: Getting Status for Deployment Id = {}", deploymentId);
		getStatus(deploymentId)
			.doOnNext(status -> assertApplicationDoesNotExist(deploymentId, status))
			// Need to block here to be able to throw exception early
			.block(Duration.ofSeconds(this.deploymentProperties.getApiTimeout()));

		logger.trace("deploy: Pushing application");
		pushApplication(deploymentId, request)
			.then(setEnvironmentVariables(deploymentId, getEnvironmentVariables(deploymentId, request)))
			.then(bindServices(deploymentId, request))
			.then(startApplication(deploymentId))
			.timeout(Duration.ofSeconds(this.deploymentProperties.getApiTimeout()))
			.doOnSuccess(v -> logger.info("Successfully deployed {}", deploymentId))
			.doOnError(e -> logger.error(String.format("Failed to deploy %s", deploymentId), e))
			.subscribe();

		logger.trace("Exiting deploy().  Deployment Id = {}", deploymentId);
		return deploymentId;
	}

	@Override
	public AppStatus status(String id) {
		try {
			return getStatus(id)
				.doOnSuccess(v -> logger.info("Successfully computed status [{}] for {}", v, id))
				.doOnError(e -> logger.error(String.format("Failed to compute status for %s", id), e))
				.block(Duration.ofMillis(this.deploymentProperties.getStatusTimeout()));
		}
		catch (Exception timeoutDueToBlock) {
			logger.error("Caught exception while querying for status of {}", id, timeoutDueToBlock);
			return createErrorAppStatus(id);
		}
	}

	@Override
	public void undeploy(String id) {
		requestDeleteApplication(id)
			.timeout(Duration.ofSeconds(this.deploymentProperties.getApiTimeout()))
			.doOnSuccess(v -> logger.info("Successfully undeployed app {}", id))
			.doOnError(e -> logger.error(String.format("Failed to undeploy app %s", id), e))
			.subscribe();
	}

	private void assertApplicationDoesNotExist(String deploymentId, AppStatus status) {
		DeploymentState state = status.getState();
		if (state != DeploymentState.unknown && state != DeploymentState.error) {
			throw new IllegalStateException(String.format("App %s is already deployed with state %s", deploymentId, state));
		}
	}

	private Mono<Void> bindServices(String deploymentId, AppDeploymentRequest request) {
		return Flux.fromIterable(servicesToBind(request))
			.flatMap(service -> requestBindService(deploymentId, service)
				.doOnSuccess(v -> logger.debug("Binding service {} to app {}", service, deploymentId))
				.doOnError(e -> logger.error(String.format("Failed to bind service %s to app %s", service, deploymentId), e)))
			.then();
	}

	private AppStatus createAppStatus(ApplicationDetail applicationDetail, String deploymentId) {
		logger.trace("Gathering instances for " + applicationDetail);
		logger.trace("InstanceDetails: " + applicationDetail.getInstanceDetails());

		AppStatus.Builder builder = AppStatus.of(deploymentId);

		int i = 0;
		for (InstanceDetail instanceDetail : applicationDetail.getInstanceDetails()) {
			builder.with(new CloudFoundryAppInstanceStatus(applicationDetail, instanceDetail, i++));
		}
		for (; i < applicationDetail.getInstances(); i++) {
			builder.with(new CloudFoundryAppInstanceStatus(applicationDetail, null, i));
		}

		return builder.build();
	}

	private AppStatus createEmptyAppStatus(String deploymentId) {
		return AppStatus.of(deploymentId)
			.build();
	}

	private AppStatus createErrorAppStatus(String deploymentId) {
		return AppStatus.of(deploymentId)
			.generalState(DeploymentState.error)
			.build();
	}

	private String domain(AppDeploymentRequest request) {
		return Optional.ofNullable(request.getDeploymentProperties().get(DOMAIN_PROPERTY))
			.orElse(this.deploymentProperties.getDomain());
	}

	private Path getApplication(AppDeploymentRequest request) {
		try {
			return request.getResource().getFile().toPath();
		} catch (IOException e) {
			throw Exceptions.propagate(e);
		}
	}

	private Mono<String> getApplicationId(String deploymentId) {
		return requestGetApplication(deploymentId)
			.map(ApplicationDetail::getId);
	}

	private Mono<AppStatus> getStatus(String deploymentId) {
		return requestGetApplication(deploymentId)
			.map(applicationDetail -> createAppStatus(applicationDetail, deploymentId))
			.otherwise(IllegalArgumentException.class, t -> {
				logger.debug("Application for {} does not exist.", deploymentId);
				return Mono.just(createEmptyAppStatus(deploymentId));
			})
			.transform(statusRetry(deploymentId))
			.otherwiseReturn(createErrorAppStatus(deploymentId));
	}

	private ApplicationHealthCheck healthCheck(AppDeploymentRequest request) {
		return Optional.ofNullable(request.getDeploymentProperties().get(HEALTHCHECK_PROPERTY_KEY))
			.map(this::toApplicationHealthCheck)
			.orElse(this.deploymentProperties.getHealthCheck());
	}

	private String host(AppDeploymentRequest request) {
		return Optional.ofNullable(request.getDeploymentProperties().get(HOST_PROPERTY))
			.orElse(this.deploymentProperties.getHost());
	}

	private Mono<Void> pushApplication(String deploymentId, AppDeploymentRequest request) {
		return requestPushApplication(PushApplicationRequest.builder()
			.application(getApplication(request))
			.buildpack(buildpack(request))
			.diskQuota(diskQuota(request))
			.domain(domain(request))
			.healthCheckType(healthCheck(request))
			.host(host(request))
			.instances(instances(request))
			.memory(memory(request))
			.name(deploymentId)
			.noRoute(toggleNoRoute(request))
			.noStart(true)
			.routePath(routePath(request))
			.build())
			.doOnSuccess(v -> logger.info("Done uploading bits for {}", deploymentId))
			.doOnError(e -> logger.error(String.format("Error creating app %s.  Exception Message %s", deploymentId, e.getMessage())));
	}

	private Mono<Void> requestBindService(String deploymentId, String service) {
		return this.operations.services()
			.bind(BindServiceInstanceRequest.builder()
				.applicationName(deploymentId)
				.serviceInstanceName(service)
				.build());
	}

	private Mono<Void> requestDeleteApplication(String id) {
		return this.operations.applications()
			.delete(DeleteApplicationRequest.builder()
				.deleteRoutes(true)
				.name(id)
				.build());
	}

	private Mono<ApplicationDetail> requestGetApplication(String id) {
		return this.operations.applications()
			.get(GetApplicationRequest.builder()
				.name(id)
				.build());
	}

	private Mono<Void> requestPushApplication(PushApplicationRequest request) {
		return this.operations.applications()
			.push(request);
	}

	private Mono<Void> requestStartApplication(String name, Duration stagingTimeout, Duration startupTimeout) {
		return this.operations.applications()
			.start(StartApplicationRequest.builder()
				.name(name)
				.stagingTimeout(stagingTimeout)
				.startupTimeout(startupTimeout)
				.build());
	}

	private Mono<UpdateApplicationResponse> requestUpdateApplication(String applicationId, Map<String, String> environmentVariables) {
		return this.client.applicationsV2()
			.update(UpdateApplicationRequest.builder()
				.applicationId(applicationId)
				.environmentJsons(environmentVariables)
				.build());
	}

	private String routePath(AppDeploymentRequest request) {
		return request.getDeploymentProperties().get(ROUTE_PATH_PROPERTY);
	}

	private Mono<UpdateApplicationResponse> setEnvironmentVariables(String deploymentId, Map<String, String> environmentVariables) {
		return getApplicationId(deploymentId)
			.then(applicationId -> requestUpdateApplication(applicationId, environmentVariables))
			.doOnSuccess(v -> logger.debug("Setting individual env variables to {} for app {}", environmentVariables, deploymentId))
			.doOnError(e -> logger.error(String.format("Unable to set individual env variables for app %s", deploymentId)));
	}

	private Mono<Void> startApplication(String deploymentId) {
		return requestStartApplication(deploymentId, this.deploymentProperties.getStagingTimeout(), this.deploymentProperties.getStartupTimeout())
			.doOnSuccess(v -> logger.info("Started app {}", deploymentId))
			.doOnError(e -> logger.error(String.format("Failed to start app %s", deploymentId), e));
	}

	private ApplicationHealthCheck toApplicationHealthCheck(String raw) {
		try {
			return ApplicationHealthCheck.valueOf(raw.toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(String.format("Unsupported health-check value '%s'. Available values are %s", raw,
				StringUtils.arrayToCommaDelimitedString(ApplicationHealthCheck.values())), e);
		}
	}

	private Boolean toggleNoRoute(AppDeploymentRequest request) {
		return Optional.ofNullable(request.getDeploymentProperties().get(NO_ROUTE_PROPERTY))
			.map(Boolean::valueOf)
			.orElse(null);
	}

	public String deploymentId(AppDeploymentRequest request) {
		String prefix = Optional.ofNullable(request.getDeploymentProperties().get(AppDeployer.GROUP_PROPERTY_KEY))
			.map(group -> String.format("%s-", group))
			.orElse("");

		String appName = String.format("%s%s", prefix, request.getDefinition().getName());

		return this.applicationNameGenerator.generateAppName(appName);
	}

}
