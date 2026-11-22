package com.example.ajs.auth.web;

import com.example.ajs.auth.domain.User;
import com.example.ajs.auth.repository.UserRepository;
import com.example.ajs.auth.security.JwtService;
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

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level tests for the authentication flow, exercised through the real
 * filter chain: sign-in, sign-up, sign-out, role gating and the disabled-account
 * path.
 *
 * <p>MockMvc is built explicitly with {@code springSecurity()} so the security
 * filter chain is genuinely in the request path. An earlier version of the
 * dashboard tests used the default MockMvc setup, which silently did not apply
 * the chain, and asserted "unauthenticated access is refused" against a harness
 * with no security at all.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthFlowTest {

    private static final String PASSWORD = "correct-horse-battery";

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository users;

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

        users.findByEmail("flow-user@test.local").ifPresent(u -> users.delete(u.id()));
        users.findByEmail("flow-admin@test.local").ifPresent(u -> users.delete(u.id()));
        users.findByEmail("flow-disabled@test.local").ifPresent(u -> users.delete(u.id()));

        ordinary = users.create("flow-user@test.local",
                passwordEncoder.encode(PASSWORD), "Flow User", User.Role.USER);
        admin = users.create("flow-admin@test.local",
                passwordEncoder.encode(PASSWORD), "Flow Admin", User.Role.ADMIN);
    }

    // --- sign-in -----------------------------------------------------------

    @Test
    @DisplayName("signing in with correct credentials issues an auth-token cookie")
    void signInIssuesCookie() throws Exception {
        mockMvc.perform(post("/auth/sign-in")
                        .with(csrf())
                        .param("email", ordinary.email())
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/overview"))
                .andExpect(cookie().exists(JwtService.COOKIE_NAME));
    }

    @Test
    @DisplayName("the session cookie is HttpOnly, so JavaScript cannot read the token")
    void sessionCookieIsHttpOnly() throws Exception {
        mockMvc.perform(post("/auth/sign-in")
                        .with(csrf())
                        .param("email", ordinary.email())
                        .param("password", PASSWORD))
                .andExpect(cookie().httpOnly(JwtService.COOKIE_NAME, true));
    }

    @Test
    @DisplayName("the cookie is signed and carries the role, but is only 24h long")
    void sessionCookieLifetime() throws Exception {
        mockMvc.perform(post("/auth/sign-in")
                        .with(csrf())
                        .param("email", ordinary.email())
                        .param("password", PASSWORD))
                .andExpect(cookie().maxAge(JwtService.COOKIE_NAME,
                        (int) (JwtService.TOKEN_TTL_MILLIS / 1000)));
    }

    @Test
    @DisplayName("a wrong password is refused and no cookie is issued")
    void wrongPasswordRefused() throws Exception {
        mockMvc.perform(post("/auth/sign-in")
                        .with(csrf())
                        .param("email", ordinary.email())
                        .param("password", "definitely-wrong"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Incorrect password")))
                .andExpect(cookie().doesNotExist(JwtService.COOKIE_NAME));
    }

    @Test
    @DisplayName("an unknown email is refused")
    void unknownEmailRefused() throws Exception {
        mockMvc.perform(post("/auth/sign-in")
                        .with(csrf())
                        .param("email", "nobody@nowhere.test")
                        .param("password", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No account found")))
                .andExpect(cookie().doesNotExist(JwtService.COOKIE_NAME));
    }

    @Test
    @DisplayName("a disabled account cannot sign in, even with the right password")
    void disabledAccountCannotSignIn() throws Exception {
        User disabled = users.create("flow-disabled@test.local",
                passwordEncoder.encode(PASSWORD), "Disabled", User.Role.USER);
        users.setDisabled(disabled.id(), true);

        mockMvc.perform(post("/auth/sign-in")
                        .with(csrf())
                        .param("email", disabled.email())
                        .param("password", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("disabled")))
                .andExpect(cookie().doesNotExist(JwtService.COOKIE_NAME));
    }

    // --- protected routes --------------------------------------------------

    @Test
    @DisplayName("a valid session cookie grants access to the dashboards")
    void validCookieGrantsAccess() throws Exception {
        String token = signInAndGetToken(ordinary.email());

        mockMvc.perform(get("/overview").cookie(new Cookie(JwtService.COOKIE_NAME, token)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("no cookie means no access")
    void noCookieIsRefused() throws Exception {
        mockMvc.perform(get("/overview"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("a tampered cookie is refused")
    void tamperedCookieIsRefused() throws Exception {
        String token = signInAndGetToken(ordinary.email());

        mockMvc.perform(get("/overview")
                        .cookie(new Cookie(JwtService.COOKIE_NAME, token + "x")))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("a cookie for a disabled user stops working immediately")
    void disabledUserSessionEndsImmediately() throws Exception {
        String token = signInAndGetToken(ordinary.email());

        // Valid before the change.
        mockMvc.perform(get("/overview").cookie(new Cookie(JwtService.COOKIE_NAME, token)))
                .andExpect(status().isOk());

        users.setDisabled(ordinary.id(), true);

        // The token is still cryptographically valid and unexpired, but the
        // filter re-reads the account, so access ends now rather than in 24h.
        mockMvc.perform(get("/overview").cookie(new Cookie(JwtService.COOKIE_NAME, token)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("a cookie for a deleted user stops working immediately")
    void deletedUserSessionEndsImmediately() throws Exception {
        String token = signInAndGetToken(ordinary.email());

        users.delete(ordinary.id());

        mockMvc.perform(get("/overview").cookie(new Cookie(JwtService.COOKIE_NAME, token)))
                .andExpect(status().is4xxClientError());
    }

    // --- admin gating ------------------------------------------------------

    @Test
    @DisplayName("an admin can reach /admin/users")
    void adminCanReachAdminPage() throws Exception {
        String token = signInAndGetToken(admin.email());

        mockMvc.perform(get("/admin/users").cookie(new Cookie(JwtService.COOKIE_NAME, token)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("denylist")));
    }

    @Test
    @DisplayName("an ordinary user is forbidden from /admin/users")
    void ordinaryUserForbiddenFromAdmin() throws Exception {
        String token = signInAndGetToken(ordinary.email());

        mockMvc.perform(get("/admin/users").cookie(new Cookie(JwtService.COOKIE_NAME, token)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("demoting an admin removes admin access on the next request")
    void demotionTakesEffectImmediately() throws Exception {
        String token = signInAndGetToken(admin.email());

        mockMvc.perform(get("/admin/users").cookie(new Cookie(JwtService.COOKIE_NAME, token)))
                .andExpect(status().isOk());

        users.updateRole(admin.id(), User.Role.USER);

        // The token still claims admin, but the database is authoritative.
        mockMvc.perform(get("/admin/users").cookie(new Cookie(JwtService.COOKIE_NAME, token)))
                .andExpect(status().isForbidden());
    }

    // --- sign-up and sign-out ---------------------------------------------

    @Test
    @DisplayName("sign-up creates an ordinary user and signs them in")
    void signUpCreatesOrdinaryUser() throws Exception {
        users.findByEmail("brand-new@test.local").ifPresent(u -> users.delete(u.id()));

        mockMvc.perform(post("/auth/sign-up")
                        .with(csrf())
                        .param("email", "brand-new@test.local")
                        .param("password", PASSWORD)
                        .param("name", "Brand New"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/overview"))
                .andExpect(cookie().exists(JwtService.COOKIE_NAME));

        User created = users.findByEmail("brand-new@test.local").orElseThrow();
        assertThat(created.role())
                .as("the public sign-up form must never produce an admin")
                .isEqualTo(User.Role.USER);

        users.delete(created.id());
    }

    @Test
    @DisplayName("sign-up refuses a short password")
    void signUpRefusesShortPassword() throws Exception {
        mockMvc.perform(post("/auth/sign-up")
                        .with(csrf())
                        .param("email", "shorty@test.local")
                        .param("password", "short")
                        .param("name", "Shorty"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("at least 8 characters")));
    }

    @Test
    @DisplayName("sign-up refuses a duplicate email")
    void signUpRefusesDuplicateEmail() throws Exception {
        mockMvc.perform(post("/auth/sign-up")
                        .with(csrf())
                        .param("email", ordinary.email())
                        .param("password", PASSWORD)
                        .param("name", "Impostor"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("already exists")));
    }

    @Test
    @DisplayName("signing out clears the session cookie")
    void signOutClearsCookie() throws Exception {
        String token = signInAndGetToken(ordinary.email());

        mockMvc.perform(post("/auth/sign-out")
                        .with(csrf())
                        .cookie(new Cookie(JwtService.COOKIE_NAME, token)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/sign-in"))
                .andExpect(cookie().maxAge(JwtService.COOKIE_NAME, 0));
    }

    @Test
    @DisplayName("/api/auth/me reports identity when signed in and 401 otherwise")
    void meEndpoint() throws Exception {
        String token = signInAndGetToken(admin.email());

        mockMvc.perform(get("/api/auth/me")
                        .cookie(new Cookie(JwtService.COOKIE_NAME, token)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("flow-admin@test.local")))
                .andExpect(content().string(containsString("admin")));

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers -----------------------------------------------------------

    /** Signs in through the real endpoint and returns the issued token. */
    private String signInAndGetToken(String email) throws Exception {
        var result = mockMvc.perform(post("/auth/sign-in")
                        .with(csrf())
                        .param("email", email)
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        Cookie cookie = result.getResponse().getCookie(JwtService.COOKIE_NAME);
        assertThat(cookie).as("sign-in must issue the session cookie").isNotNull();
        return cookie.getValue();
    }
}
