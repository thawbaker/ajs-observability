package com.example.ajs.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Wires the AJS (IBM i) data access layer.
 *
 * <h2>Why the datasource is declared explicitly</h2>
 * Adding the SQLite authentication store means there are now two
 * {@link DataSource} beans. Spring Boot's {@code DataSourceAutoConfiguration}
 * backs off as soon as any user-defined {@code DataSource} exists, so the AJS
 * datasource can no longer be autoconfigured implicitly — it has to be declared
 * here, reading the same {@code spring.datasource.*} properties as before.
 *
 * <p>The AJS datasource is marked {@link Primary} because it is the one Spring
 * Boot's {@code JdbcTemplateAutoConfiguration} and Actuator health checks expect
 * when no qualifier is given. The auth datasource is always reached through its
 * {@code authDataSource} qualifier, never by default. Getting this backwards
 * would point the health check at SQLite and silently stop reporting IBM i
 * connectivity — which is exactly the signal an operations tool must not lose.
 */
@Configuration
public class AjsDataConfig {

    /** Bound from the pre-existing {@code spring.datasource.*} properties. */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties ajsDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    public DataSource dataSource(
            @Qualifier("ajsDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean
    @Primary
    public JdbcTemplate ajsJdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public com.example.ajs.repository.AjsRepository ajsRepository(
            @Qualifier("ajsJdbcTemplate") JdbcTemplate ajsJdbcTemplate) {
        return new com.example.ajs.repository.JdbcAjsRepository(ajsJdbcTemplate);
    }

    /**
     * Startup schema-drift check (risk R1). Disabled under the test profile,
     * where the H2 fixture is authored to match by construction.
     */
    @Bean
    @ConditionalOnProperty(name = "ajs.schema-validation.enabled", havingValue = "true")
    public ApplicationRunner ajsSchemaValidator(
            @Qualifier("ajsJdbcTemplate") JdbcTemplate ajsJdbcTemplate,
            @Value("${ajs.schema-validation.fail-on-missing-columns:false}") boolean failOnMissing) {
        return new com.example.ajs.schema.AjsSchemaValidator(ajsJdbcTemplate, failOnMissing);
    }
}
