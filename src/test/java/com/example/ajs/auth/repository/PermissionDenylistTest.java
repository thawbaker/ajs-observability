package com.example.ajs.auth.repository;

import com.example.ajs.auth.domain.App;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the denylist semantics of {@code user_applications}.
 *
 * <p>This test exists because the model is inverted from what most people
 * expect. A row means DENIED, and no rows means FULL ACCESS. Getting it
 * backwards turns "restrict this user from one app" into "revoke everything",
 * or worse, leaves a user unrestricted when the intent was to restrict them.
 * Both directions are asserted explicitly.
 */
@SpringBootTest
@ActiveProfiles("test")
class PermissionDenylistTest {

    @Autowired
    private AppRepository apps;

    @Autowired
    private UserRepository users;

    @Autowired
    @Qualifier("authJdbcTemplate")
    private JdbcTemplate authJdbc;

    private long userId;

    @BeforeEach
    void setUp() {
        // Each test starts from a clean slate so ordering cannot matter.
        authJdbc.update("DELETE FROM user_applications");
        authJdbc.update("DELETE FROM applications");
        authJdbc.update("DELETE FROM users WHERE email LIKE '%@denylist.test'");

        long id = users.create("subject@denylist.test", "hash", "Subject",
                com.example.ajs.auth.domain.User.Role.USER).id();
        userId = id;
    }

    @Test
    @DisplayName("no deny rows means FULL access, not no access")
    void noRowsMeansFullAccess() {
        App a = apps.createApp("Overview", "overview", "gauge", "/overview");
        App b = apps.createApp("Failures", "failures", "alert", "/failures");

        assertThat(apps.blockedAppIds(userId)).isEmpty();
        assertThat(apps.accessibleApps(userId))
                .as("an empty denylist grants everything enabled")
                .extracting(App::slug)
                .containsExactlyInAnyOrder("overview", "failures");
    }

    @Test
    @DisplayName("a deny row removes ONLY that app, leaving the rest accessible")
    void denyRowRemovesOnlyThatApp() {
        App overview = apps.createApp("Overview", "overview", "gauge", "/overview");
        apps.createApp("Failures", "failures", "alert", "/failures");
        apps.createApp("Inventory", "jobs", "list", "/jobs");

        apps.setBlockedAppIds(userId, List.of(overview.id()));

        assertThat(apps.blockedAppIds(userId)).containsExactly(overview.id());
        assertThat(apps.accessibleApps(userId))
                .extracting(App::slug)
                .as("the deny row must not revoke unrelated apps")
                .containsExactlyInAnyOrder("failures", "jobs");
    }

    @Test
    @DisplayName("a newly created app is granted automatically to everyone")
    void newAppsAreGrantedByDefault() {
        apps.createApp("Overview", "overview", "gauge", "/overview");
        apps.setBlockedAppIds(userId, List.of(apps.findAppBySlug("overview").orElseThrow().id()));

        assertThat(apps.accessibleApps(userId)).extracting(App::slug).containsExactly();

        // Adding an app later must not require touching every user's rows.
        apps.createApp("Brand New", "brand-new", "star", "/new");

        assertThat(apps.accessibleApps(userId))
                .as("a new app has no deny row, so it is available immediately")
                .extracting(App::slug)
                .containsExactly("brand-new");
    }

    @Test
    @DisplayName("disabled apps are excluded even when not denied")
    void disabledAppsAreExcluded() {
        App overview = apps.createApp("Overview", "overview", "gauge", "/overview");
        apps.createApp("Failures", "failures", "alert", "/failures");

        apps.setAppEnabled(overview.id(), false);

        assertThat(apps.accessibleApps(userId))
                .extracting(App::slug)
                .containsExactly("failures");
    }

    @Test
    @DisplayName("setting deny rows replaces the previous set rather than appending")
    void settingReplacesRatherThanAppends() {
        App a = apps.createApp("A", "a", "x", "/a");
        App b = apps.createApp("B", "b", "x", "/b");

        apps.setBlockedAppIds(userId, List.of(a.id()));
        assertThat(apps.blockedAppIds(userId)).containsExactly(a.id());

        apps.setBlockedAppIds(userId, List.of(b.id()));
        assertThat(apps.blockedAppIds(userId))
                .as("the earlier deny must be gone, not accumulated")
                .containsExactly(b.id());
    }

    @Test
    @DisplayName("clearing the denylist restores full access")
    void clearingRestoresFullAccess() {
        App a = apps.createApp("A", "a", "x", "/a");
        apps.setBlockedAppIds(userId, List.of(a.id()));
        assertThat(apps.accessibleApps(userId)).isEmpty();

        apps.setBlockedAppIds(userId, List.of());

        assertThat(apps.accessibleApps(userId)).extracting(App::slug).containsExactly("a");
    }

    @Test
    @DisplayName("an empty list is treated as no restrictions, matching 'no checkboxes ticked'")
    void emptyListMeansNoRestrictions() {
        apps.createApp("A", "a", "x", "/a");
        apps.setBlockedAppIds(userId, List.of());

        assertThat(apps.accessibleApps(userId)).hasSize(1);
    }

    @Test
    @DisplayName("deny rows are scoped per user")
    void denyRowsArePerUser() {
        long otherId = users.create("other@denylist.test", "hash", "Other",
                com.example.ajs.auth.domain.User.Role.USER).id();

        App a = apps.createApp("A", "a", "x", "/a");
        apps.setBlockedAppIds(userId, List.of(a.id()));

        assertThat(apps.accessibleApps(userId)).isEmpty();
        assertThat(apps.accessibleApps(otherId))
                .as("one user's deny must not affect another")
                .hasSize(1);
    }

    @Test
    @DisplayName("deleting an app removes its deny rows instead of orphaning them")
    void deleteAppCascadesDenyRows() {
        App a = apps.createApp("A", "a", "x", "/a");
        apps.setBlockedAppIds(userId, List.of(a.id()));
        assertThat(apps.blockedAppIds(userId)).hasSize(1);

        apps.deleteApp(a.id());

        // Relies on PRAGMA foreign_keys = ON in SqliteDataSourceConfig. SQLite
        // defaults it OFF, in which case this row would survive as an orphan.
        assertThat(apps.blockedAppIds(userId))
                .as("foreign_keys must be enabled for ON DELETE CASCADE to work")
                .isEmpty();
    }
}
