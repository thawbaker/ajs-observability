package com.example.ajs.auth.config;

import com.example.ajs.auth.domain.App;
import com.example.ajs.auth.domain.User;
import com.example.ajs.auth.repository.AppRepository;
import com.example.ajs.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Creates the first administrator and registers the built-in dashboards, so a
 * fresh deployment is usable without a manual database edit.
 *
 * <h2>Bootstrap admin</h2>
 * A first admin is needed because every admin route is gated and self-service
 * sign-up can only create ordinary users — by design, there is no path from the
 * public form to an administrator. Without this, a new install would have no way
 * to administer anything.
 *
 * <p>The credentials come from {@code AUTH_BOOTSTRAP_ADMIN_EMAIL} and
 * {@code AUTH_BOOTSTRAP_ADMIN_PASSWORD}. If the password is not supplied, a
 * random one is generated and logged once. That is deliberate: a fixed default
 * password is how these accounts end up in production unchanged.
 *
 * <p>A pre-existing password is never overwritten, and the account is only
 * created when no enabled admin exists at all.
 */
@Component
@ConditionalOnProperty(name = "auth.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class AuthBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthBootstrap.class);

    /**
     * The dashboards this application provides, registered as apps so they can be
     * granted or denied per user through the same mechanism as anything else.
     * Slugs match the routes so the mapping is obvious.
     */
    private static final List<String[]> BUILT_IN_APPS = List.of(
            new String[]{"Overview", "overview", "gauge"},
            new String[]{"Jobs due", "calendar", "calendar-clock"},
            new String[]{"Timeline", "timeline", "activity"},
            new String[]{"Failures", "failures", "alert-triangle"},
            new String[]{"Job inventory", "jobs", "list"},
            new String[]{"Needs attention", "attention", "eye"});

    private final UserRepository users;
    private final AppRepository apps;
    private final PasswordEncoder passwordEncoder;

    @Value("${auth.bootstrap.admin-email:}")
    private String adminEmail;

    @Value("${auth.bootstrap.admin-password:}")
    private String adminPassword;

    public AuthBootstrap(UserRepository users, AppRepository apps, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.apps = apps;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        registerBuiltInApps();
        ensureAdminExists();
    }

    /** Idempotent: an app is only inserted when its slug is absent. */
    private void registerBuiltInApps() {
        int created = 0;
        for (String[] app : BUILT_IN_APPS) {
            if (apps.findAppBySlug(app[1]).isEmpty()) {
                App saved = apps.createApp(app[0], app[1], app[2], "/" + app[1]);
                log.info("Registered application: {} ({})", saved.name(), saved.slug());
                created++;
            }
        }
        if (created > 0) {
            log.info("Registered {} built-in application(s)", created);
        }
    }

    private void ensureAdminExists() {
        if (users.countActiveAdmins() > 0) {
            log.debug("An enabled admin already exists; skipping bootstrap");
            return;
        }

        String email = (adminEmail == null || adminEmail.isBlank())
                ? "admin@localhost" : adminEmail.trim();

        // Reuse the existing account if the email is taken but has no admin role,
        // rather than failing: this is the common case when someone signs up
        // before first configuring the bootstrap.
        var existing = users.findByEmail(email);
        if (existing.isPresent()) {
            User user = existing.get();
            users.updateRole(user.id(), User.Role.ADMIN);
            users.setDisabled(user.id(), false);
            log.warn("Promoted existing account {} to administrator. "
                    + "Its password is unchanged.", email);
            return;
        }

        String password = adminPassword;
        boolean generated = false;
        if (password == null || password.isBlank()) {
            password = generatePassword();
            generated = true;
        }

        users.create(email, passwordEncoder.encode(password), "Administrator", User.Role.ADMIN);

        if (generated) {
            // Logged once, at WARN, because it is the only time this value is
            // visible. A generated password that is never shown is useless.
            log.warn("""
                    ======================================================================
                    Bootstrap administrator created.
                      email:    {}
                      password: {}
                    This password is shown ONCE and is NOT stored in plain text.
                    Sign in and change it, or set AUTH_BOOTSTRAP_ADMIN_PASSWORD before
                    the next fresh deployment.
                    ======================================================================""",
                    email, password);
        } else {
            log.info("Bootstrap administrator created for {} using the configured password.", email);
        }
    }

    /** 32 bytes of URL-safe random. Long enough that it cannot be guessed. */
    private String generatePassword() {
        byte[] bytes = new byte[24];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
