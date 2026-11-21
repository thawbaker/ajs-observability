package com.example.ajs.auth.config;

import com.example.ajs.auth.repository.AppRepository;
import com.example.ajs.auth.repository.SqliteAppRepository;
import com.example.ajs.auth.repository.SqliteUserRepository;
import com.example.ajs.auth.repository.UserRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.File;

/**
 * Wires the SQLite authentication store as a <em>second, independent</em>
 * datasource, separate from the jt400 IBM i datasource.
 *
 * <p>Two datasources exist because they have different lifetimes and different
 * failure modes. The IBM i may be unreachable while the web application must
 * still authenticate users, render sign-in, and be administrable. Binding auth
 * to the IBM i connection would mean an iSeries outage locks operators out of
 * the tool they use to diagnose the outage. So they are kept apart deliberately.
 *
 * <p>All beans here are qualified {@code authDataSource} / {@code authJdbcTemplate}
 * so they cannot be confused with the AJS ones, which are left unqualified.
 */
@Configuration
public class SqliteDataSourceConfig {

    /** SQLite file path. Created if missing, along with its parent directory. */
    @Value("${auth.database.path:./data/auth.db}")
    private String databasePath;

    @Bean(name = "authDataSource")
    public DataSource authDataSource() {
        File dbFile = new File(databasePath);
        File parent = dbFile.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create directory for auth database: " + parent);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setPoolName("ajs-auth-sqlite-pool");

        // SQLite serialises writers. A larger pool than this does not buy
        // throughput and risks SQLITE_BUSY under concurrent admin edits.
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);

        // SQLite has no network, so a connection attempt is effectively
        // instantaneous; fail fast rather than hang a request thread.
        config.setConnectionTimeout(5000);

        // Applied to every pooled connection. foreign_keys is the important one:
        // SQLite defaults it OFF, which would silently allow orphaned
        // user_applications rows when an application is deleted. busy_timeout
        // makes concurrent writers wait instead of failing immediately.
        config.setConnectionInitSql("PRAGMA foreign_keys = ON");
        config.addDataSourceProperty("busy_timeout", "5000");

        HikariDataSource dataSource = new HikariDataSource(config);

        // Schema is idempotent (CREATE TABLE IF NOT EXISTS) and small, so it is
        // applied on every startup. This keeps a fresh checkout runnable with no
        // migration step, which is the same approach the reference project takes.
        new ResourceDatabasePopulator(
                new ClassPathResource("auth/schema-auth.sql"))
                .execute(dataSource);

        return dataSource;
    }

    @Bean(name = "authJdbcTemplate")
    public JdbcTemplate authJdbcTemplate(@org.springframework.beans.factory.annotation.Qualifier("authDataSource")
                                         DataSource authDataSource) {
        return new JdbcTemplate(authDataSource);
    }

    @Bean(name = "authTransactionManager")
    public PlatformTransactionManager authTransactionManager(
            @org.springframework.beans.factory.annotation.Qualifier("authDataSource")
            DataSource authDataSource) {
        return new org.springframework.jdbc.datasource.DataSourceTransactionManager(authDataSource);
    }

    @Bean
    public UserRepository userRepository(
            @org.springframework.beans.factory.annotation.Qualifier("authJdbcTemplate")
            JdbcTemplate authJdbcTemplate) {
        return new SqliteUserRepository(authJdbcTemplate);
    }

    @Bean
    public AppRepository appRepository(
            @org.springframework.beans.factory.annotation.Qualifier("authJdbcTemplate")
            JdbcTemplate authJdbcTemplate,
            @org.springframework.beans.factory.annotation.Qualifier("authTransactionManager")
            PlatformTransactionManager authTransactionManager) {
        return new SqliteAppRepository(authJdbcTemplate, new TransactionTemplate(authTransactionManager));
    }
}
