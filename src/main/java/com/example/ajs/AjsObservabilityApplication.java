package com.example.ajs;

import com.example.ajs.config.FixtureProfileGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AJS Observability — a read-only observability layer for IBM i Advanced Job
 * Scheduler (AJS), built per {@code req.txt} Option A + C.
 *
 * <p>Reads the AJS physical files in {@code QUSRIJS} over the jt400 JDBC driver
 * and renders server-side Thymeleaf dashboards. Nothing is installed on the
 * IBM i and no AJS configuration is changed.
 */
@SpringBootApplication
public class AjsObservabilityApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AjsObservabilityApplication.class);
        // Registered before the context refreshes so a fixture profile cannot
        // come up quietly in a real environment.
        app.addListeners(new FixtureProfileGuard());
        app.run(args);
    }
}
