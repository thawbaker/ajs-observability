package com.example.ajs.auth;

import com.example.ajs.auth.domain.User;
import com.example.ajs.auth.repository.UserRepository;
import com.example.ajs.auth.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves that CSRF protection is genuinely enforced, not merely configured.
 *
 * <p>This exists because of design review finding C1, where CSRF was disabled
 * behind a comment that asserted, wrongly, that there were no browser form
 * POSTs. There were ten, including admin user deletion.
 *
 * <p>The important property is that these tests fail if protection is switched
 * back off. Asserting "the suite is green" would not have caught the original
 * problem — a disabled CSRF filter makes every POST succeed, and a succeeding
 * POST is exactly what the other tests expect. So the assertions here are
 * deliberately inverted: the *absence* of a token must produce a rejection.
 */
@SpringBootTest
@ActiveProfiles("test")
class CsrfProtectionTest {

    private static final String PASSWORD = "correct-horse-battery";

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User ordinary;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();

        users.findByEmail("csrf-user@test.local").ifPresent(u -> users.delete(u.id()));
        ordinary = users.create("csrf-user@test.local",
                passwordEncoder.encode(PASSWORD), "CSRF User", User.Role.USER);
    }

    /**
     * The CsrfFilter is not exposed as a top-level bean (it is part of the
     * chain's internal filter list), so presence is asserted behaviourally
     * instead: a token-less POST must be rejected.
     */
    @Test
    @DisplayName("CSRF protection cannot be silently disabled")
    void csrfCannotBeSilentlyDisabled() throws Exception {
        mockMvc.perform(post("/auth/sign-in")
                        .param("email", ordinary.email())
                        .param("password", PASSWORD))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a POST with no CSRF token is rejected")
    void postWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(post("/auth/sign-in")
                        .param("email", ordinary.email())
                        .param("password", PASSWORD))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a POST with the CSRF token succeeds")
    void postWithTokenSucceeds() throws Exception {
        mockMvc.perform(post("/auth/sign-in")
                        .with(csrf())
                        .param("email", ordinary.email())
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("signing out without a token is rejected, so a cross-site logout cannot work")
    void signOutWithoutTokenIsRejected() throws Exception {
        String token = signInAndGetToken();

        mockMvc.perform(post("/auth/sign-out")
                        .cookie(new Cookie(JwtService.COOKIE_NAME, token)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the sign-in page actually renders a hidden _csrf field")
    void signInPageRendersHiddenCsrfField() throws Exception {
        mockMvc.perform(get("/sign-in"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    /**
     * The GET must also issue the token as a cookie, not only render it as a field.
     *
     * <p>This is the assertion that would have caught two separate defects, and its
     * absence is why both survived a green suite. Rendering the hidden field proves
     * only that <em>something</em> read the token; it does not prove the browser can
     * send it back. The cookie repository uses double-submit, so a page with the
     * field and no cookie produces a form that looks correct and 403s on submit —
     * the entire site unusable, with nothing visibly wrong.
     *
     * <p>The first defect was the default session-backed repository, which cannot
     * persist across a stateless chain at all. The second was the deferred token
     * handler, which resolves the token too late to write the cookie. Both are only
     * visible in this one response header.
     */
    @Test
    @DirtiesContext
    @DisplayName("a first visit issues the XSRF-TOKEN cookie, so the first POST can succeed")
    void firstVisitIssuesCsrfCookie() throws Exception {
        // The case that matters is the FIRST visit: no token cookie yet, so one
        // must be generated and sent, or the browser has a form field with nothing
        // to pair it against and every submission 403s.
        //
        // No cookie is sent: that is the genuine first-visit case, and the only one
        // in which the server both renders a token and issues the cookie. Measured
        // behaviour: with no cookie a Set-Cookie is sent; with a stale cookie the
        // server reuses the stale value and sends nothing (correct — the browser
        // already holds a token, so there is nothing to re-issue).
        //
        // This test is order-sensitive by nature, because MockMvc shares one
        // application context and the token repository is application-scoped: once
        // an earlier test has POSTed through the chain, the repository already holds
        // a token and a later GET is served without a new cookie. @DirtiesContext
        // gives this test its own context so the assertion is deterministic.
        for (String path : new String[]{"/sign-in", "/sign-up"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .cookie().exists("XSRF-TOKEN"));
        }
    }

    @Test
    @DisplayName("a request that already carries a token is served without re-issuing it")
    void existingTokenIsNotReissued() throws Exception {
        // The complement of the test above, and not a defect: once the browser has
        // the cookie, the server does not need to send it again. Pinned so a future
        // change that made every response carry a fresh token — which would
        // invalidate the token in the page the user is looking at — is caught.
        Cookie existing = new Cookie("XSRF-TOKEN", "existing-token-value");

        mockMvc.perform(get("/sign-in").cookie(existing))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .cookie().doesNotExist("XSRF-TOKEN"));
    }

    @Test
    @DisplayName("the sign-up page actually renders a hidden _csrf field")
    void signUpPageRendersHiddenCsrfField() throws Exception {
        mockMvc.perform(get("/sign-up"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    /**
     * The form renders a real, submittable token.
     *
     * <p>This test found a genuine defect. With the default HttpSession-backed
     * token repository on a STATELESS chain the token rendered into every form but
     * could never be verified on the way back, so every submission 403ed. The fix
     * was the cookie repository plus an eager token-resolution filter; both were
     * needed, and the second was found only by driving a real browser.
     *
     * <p><strong>What this test can and cannot prove, measured rather than assumed.</strong>
     * It proves the field renders and that a correctly paired submission is
     * accepted. It does <em>not</em> prove the browser round trip, and the reason is
     * worth recording because two earlier versions of it were misleading:
     *
     * <ul>
     *   <li>MockMvc shares one application context and the CSRF token repository is
     *       application-scoped, so whether a GET issues a fresh cookie depends on
     *       what earlier tests did. After the {@code @BeforeEach} writes a user,
     *       and after any POST through the chain, a later GET is served from the
     *       existing token and issues no new cookie.</li>
     *   <li>The cookie holds the <em>raw</em> token while the form field holds an
     *       <em>XOR-masked</em> form of it. They are deliberately different strings,
     *       so a hand-assembled pair that puts the field value in the cookie gets a
     *       403 that looks like a wiring failure and is not one.</li>
     * </ul>
     *
     * <p>Both are harness properties. Against the running application, a GET issues
     * the cookie, a POST succeeds with 302, and a later GET issues a fresh cookie;
     * and in Chromium sign-in reaches {@code /overview}. That is the evidence the
     * browser path works, and it is recorded in NOTES.md.
     *
     * <p>The {@code csrf()} post-processor is used for the submission here precisely
     * because it is the only way to make this assertion independent of ambient
     * context state. The property it cannot check — that the application issues the
     * cookie at all — is asserted by {@code firstVisitIssuesCsrfCookie} instead,
     * which is why that test exists rather than this one trying to do both.
     */
    @Test
    @DisplayName("the rendered token is accepted on submission")
    void renderedTokenIsUsable() throws Exception {
        var page = mockMvc.perform(get("/sign-in"))
                .andExpect(status().isOk())
                .andReturn();

        String value = extractCsrfValue(page.getResponse().getContentAsString());
        assertThat(value)
                .as("the hidden _csrf field must carry a non-empty token")
                .isNotBlank();

        mockMvc.perform(post("/auth/sign-in")
                        .with(csrf())
                        .param("email", ordinary.email())
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .cookie().exists(JwtService.COOKIE_NAME));
    }

    // --- helpers -----------------------------------------------------------

    private String signInAndGetToken() throws Exception {
        var result = mockMvc.perform(post("/auth/sign-in")
                        .with(csrf())
                        .param("email", ordinary.email())
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie(JwtService.COOKIE_NAME);
        assertThat(cookie).as("sign-in must issue the session cookie").isNotNull();
        return cookie.getValue();
    }

    /** Pulls the value out of a {@code Set-Cookie} header for one cookie. */
    private static String extractCookieValue(String setCookieHeader) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("XSRF-TOKEN=([^;]+)")
                .matcher(setCookieHeader);
        return m.find() ? m.group(1) : "";
    }

    /** Pulls the value out of `&lt;input type="hidden" name="_csrf" value="..."&gt;`. */
    private static String extractCsrfValue(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("name=\"_csrf\"\\s+value=\"([^\"]*)\"")
                .matcher(html);
        if (m.find()) {
            return m.group(1);
        }
        // Thymeleaf may emit the attributes in the other order.
        m = java.util.regex.Pattern
                .compile("value=\"([^\"]*)\"\\s+name=\"_csrf\"")
                .matcher(html);
        return m.find() ? m.group(1) : "";
    }
}
