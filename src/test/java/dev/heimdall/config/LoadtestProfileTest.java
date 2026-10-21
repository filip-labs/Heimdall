package dev.heimdall.config;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class LoadtestProfileTest {

    @Test
    void loadtestProfileBindsOnlyTheValidatedConnectionCapacityAndMbeans() {
        var environment = environment("--spring.profiles.active=loadtest");
        assertThat(environment.getActiveProfiles()).containsExactly("loadtest");
        var tomcat = tomcat(environment);
        assertThat(tomcat.getMaxConnections()).isEqualTo(12000);
        assertThat(tomcat.getMbeanregistry().isEnabled()).isTrue();
        assertUnchangedThreadAndPoolDefaults(environment, tomcat);
    }

    @Test
    void normalApplicationDoesNotActivateLoadtestOrOverrideConnectionDefaults() {
        var environment = environment();
        assertThat(environment.getActiveProfiles()).isEmpty();
        assertThat(environment.containsProperty("server.tomcat.max-connections")).isFalse();
        assertThat(environment.containsProperty("server.tomcat.mbeanregistry.enabled")).isFalse();
        var tomcat = tomcat(environment);
        assertThat(tomcat.getMaxConnections()).isEqualTo(8192);
        assertThat(tomcat.getMbeanregistry().isEnabled()).isFalse();
        assertUnchangedThreadAndPoolDefaults(environment, tomcat);
    }

    private StandardEnvironment environment(String... arguments) {
        var environment = new StandardEnvironment();
        // Test the shipped config, independently of developer/CI environment overrides.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(arguments));
        environment.getPropertySources().addLast(new SimpleCommandLinePropertySource(
                "configLocation", new String[]{"--spring.config.location=classpath:/application.yml"}));
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        return environment;
    }

    private TomcatServerProperties tomcat(StandardEnvironment environment) {
        var properties = new TomcatServerProperties();
        Binder.get(environment).bind("server.tomcat", Bindable.ofInstance(properties));
        return properties;
    }

    private void assertUnchangedThreadAndPoolDefaults(StandardEnvironment environment,
                                                     TomcatServerProperties tomcat) {
        assertThat(environment.containsProperty("server.tomcat.threads.max")).isFalse();
        assertThat(tomcat.getThreads().getMax()).isEqualTo(200);
        assertThat(environment.containsProperty("spring.datasource.hikari.maximum-pool-size")).isFalse();
        var hikari = new HikariConfig();
        hikari.setJdbcUrl(environment.getRequiredProperty("spring.datasource.url"));
        Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(hikari));
        // Resolves Hikari's defaults without creating a pool or opening a DB connection.
        hikari.validate();
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(10);
    }
}
