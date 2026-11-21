package com.example.ajs.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * Fails fast if the application is started with an offline / fixture profile in
 * anything that looks like a real deployment.
 *
 * <p>The {@code local} and {@code test} profiles seed <em>fabricated</em> AJS job
 * data into an in-memory H2 database. Shipping a build where someone can
 * accidentally run those against a real environment would be far worse than most
 * bugs in this project: an operator could look at a dashboard of invented jobs
 * and believe it. Because {@code application-local.yml} lives in main resources
 * (it has to, so {@code bootRun} can use it), that mistake is one flag away.
 *
 * <p>So: these profiles are only allowed when the environment explicitly opts in
 * via {@code AJS_ALLOW_FIXTURE_PROFILES=true}. Otherwise startup is refused.
 * Default-deny is the only safe direction here.
 */
public class FixtureProfileGuard implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Logger log = LoggerFactory.getLogger(FixtureProfileGuard.class);

    /** Profiles that load fabricated data and must never run in production. */
    static final String[] FIXTURE_PROFILES = {"local", "test"};

    /** The opt-in that permits a fixture profile to run. */
    static final String ALLOW_ENV_VAR = "AJS_ALLOW_FIXTURE_PROFILES";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        Environment env = event.getEnvironment();

        String activeFixtures = Arrays.stream(env.getActiveProfiles())
                .filter(p -> Arrays.asList(FIXTURE_PROFILES).contains(p))
                .findAny()
                .orElse(null);

        if (activeFixtures == null) {
            return;
        }

        if (env.getProperty(ALLOW_ENV_VAR, Boolean.class, false)) {
            log.warn("""
                    ==========================================================================
                    Running with the '{}' profile: the dashboard is showing FABRICATED AJS
                    data held in memory. Do not treat anything on screen as real scheduler
                    state. This build must never be deployed in this mode.
                    ==========================================================================""",
                    activeFixtures);
            return;
        }

        throw new IllegalStateException(
                "Refusing to start: profile '" + activeFixtures + "' loads fabricated AJS data. "
                        + "If this is deliberate (local development, demo, smoke test), set "
                        + ALLOW_ENV_VAR + "=true. It must never be set in a deployed environment.");
    }
}
