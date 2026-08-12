package com.gewu.sandbox.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class DockerConfig {

    @Value("${gewu.sandbox.docker.host:unix:///var/run/docker.sock}")
    private String dockerHost;

    @Value("${gewu.sandbox.registry.url:}")
    private String registryUrl;

    @Value("${gewu.sandbox.registry.username:}")
    private String registryUsername;

    @Value("${gewu.sandbox.registry.password:}")
    private String registryPassword;

    @Bean
    public DockerClient dockerClient() {
        DefaultDockerClientConfig.Builder builder =
                DefaultDockerClientConfig.createDefaultConfigBuilder()
                        .withDockerHost(dockerHost);

        if (registryUrl != null && !registryUrl.isBlank()) {
            builder.withRegistryUrl(registryUrl);
            if (registryUsername != null && !registryUsername.isBlank()) {
                builder.withRegistryUsername(registryUsername);
                builder.withRegistryPassword(registryPassword);
            }
        }

        DockerClientConfig config = builder.build();

        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(50)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(45))
                .build();

        return DockerClientImpl.getInstance(config, httpClient);
    }
}