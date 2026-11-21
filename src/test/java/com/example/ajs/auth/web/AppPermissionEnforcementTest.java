package com.example.ajs.auth.web;

import com.example.ajs.auth.domain.App;
import com.example.ajs.auth.domain.User;
import com.example.ajs.auth.repository.AppRepository;
import com.example.ajs.auth.repository.UserRepository;
import com.example.ajs.auth.security.JwtService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the per-user app denylist is actually <em>enforced</em>, not merely
 * stored.
 *
 * <p>This test was written because it initially failed. The permission model was
 * saved and displayed in the admin UI, but the routes ignored it: a user denied
 * the Failures page opened it anyway. A restriction that is recorded but not
 * applied is worse than no restriction, because it reads as a working control.
 */
@SpringBootTest
@ActiveProfiles("test")
class AppPermissionEnforcementTest {

    private static final String PASSWORD = "correct-horse-battery";

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository users;

    @Autowired
    private AppRepository apps;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User ordinary;
    private User admin;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();

        users.findByEmail("perm-user@test.local").ifPresent(u -> users.delete(u.id()));
        users.findByEmail("perm-admin@test.local").ifPresent(u -> users.delete(u.id()));

        ordinary = users.create("perm-user@test.local",
                passwordEncoder.encode(PASSWORD), "Perm User", User.Role.USER);
        admin = users.create("perm-admin@test.local",
                passwordEncoder.encode(PASSWORD), "Perm Admin", User.Role.ADMIN);

        // Make sure the routes this test exercises have registered apps.
        ensureApp("Overview", "overview");
        ensureApp("Jobs due", "calendar");
        ensureApp("Failures", "failures");
        ensureApp("Job inventory", "jobs");
        ensureApp("Needs attention", "attention");

        apps.setBlockedAppIds(ordinary.id(), List.of());
    }

    @Test
    @DisplayName("with no deny rows the user reaches every dashboard")
    void fullAccessByDefault() throws Exception {
        String token = signIn(ordinary.email());

        for (String path : new String[]{"/overview", "/calendar", "/failures",
                "/jobs", "/attention"}) {
            mockMvc.perform(get(path).cookie(cookie(token)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("denying one app returns 403 for that route only")
    void deniedAppIsForbidden() throws Exception {
        long failuresId = apps.findAppBySlug("failures").orElseThrow().id();
        apps.setBlockedAppIds(ordinary.id(), List.of(failuresId));

        String token = signIn(ordinary.email());

        mockMvc.perform(get("/failures").cookie(cookie(token)))
                .andExpect(status().isForbidden());

        // Everything else keeps working: a deny row must not revoke the rest.
        mockMvc.perform(get("/overview").cookie(cookie(token)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/jobs").cookie(cookie(token)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a denied sub-path is forbidden too, so it cannot be bypassed by navigating deeper")
    void deniedSubPathIsForbidden() throws Exception {
        long jobsId = apps.findAppBySlug("jobs").orElseThrow().id();
        apps.setBlockedAppIds(ordinary.id(), List.of(jobsId));

        String token = signIn(ordinary.email());

        mockMvc.perform(get("/jobs").cookie(cookie(token)))
                .andExpect(status().isForbidden());
        // /jobs/PAYROLL1 must inherit the 'jobs' permission.
        mockMvc.perform(get("/jobs/PAYROLL1").cookie(cookie(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an administrator bypasses the denylist so they can still administer")
    void adminBypassesDenylist() throws Exception {
        long failuresId = apps.findAppBySlug("failures").orElseThrow().id();
        apps.setBlockedAppIds(admin.id(), List.of(failuresId));

        String token = signIn(admin.email());

        // Locking an admin out of a dashboard would have no in-product remedy.
        mockMvc.perform(get("/failures").cookie(cookie(token)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("clearing the deny rows restores access without re-authenticating")
    void clearingDenyRestoresAccess() throws Exception {
        long failuresId = apps.findAppBySlug("failures").orElseThrow().id();
        String token = signIn(ordinary.email());

        apps.setBlockedAppIds(ordinary.id(), List.of(failuresId));
        mockMvc.perform(get("/failures").cookie(cookie(token)))
                .andExpect(status().isForbidden());

        apps.setBlockedAppIds(ordinary.id(), List.of());
        // Permissions are read per request, so no re-login is needed.
        mockMvc.perform(get("/failures").cookie(cookie(token)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the denylist never blocks sign-in or administration routes")
    void authRoutesAreNotGovernedByDenylist() throws Exception {
        // Deny literally every registered app.
        apps.setBlockedAppIds(ordinary.id(),
                apps.findAllApps().stream().map(App::id).toList());

        // Sign-in must still work, otherwise a misconfigured denylist would lock
        // every user out of the product with no way back in.
        String token = signIn(ordinary.email());
        assertThat(token).isNotBlank();

        mockMvc.perform(get("/api/auth/me").cookie(cookie(token)))
                .andExpect(status().isOk());
    }

    private void ensureApp(String name, String slug) {
        if (apps.findAppBySlug(slug).isEmpty()) {
            apps.createApp(name, slug, "layout-grid", "/" + slug);
        }
    }

    private Cookie cookie(String token) {
        return new Cookie(JwtService.COOKIE_NAME, token);
    }

    private String signIn(String email) throws Exception {
        var result = mockMvc.perform(post("/auth/sign-in")
                        .param("email", email)
                        .param("password", PASSWORD))
                .andReturn();
        Cookie c = result.getResponse().getCookie(JwtService.COOKIE_NAME);
        assertThat(c).as("sign-in should issue a cookie for %s", email).isNotNull();
        return c.getValue();
    }
}
