package com.example.ajs.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Tier 3 — HTTP-level tests that render the real Thymeleaf templates against the
 * H2-backed repository. This is the regression net for the dashboards.
 *
 * <p>Rendering is asserted, not just the model. Template errors are the most
 * common thing to break silently in a Thymeleaf application, so these tests would
 * fail on a malformed expression even when the controller is correct.
 *
 * <p>The dashboards are behind authentication (see SecurityConfig), so each
 * request carries a mock authenticated ordinary user. That is deliberate: an
 * unauthenticated GET is asserted separately to prove the gate is actually
 * closed rather than accidentally left open.
 */
@SpringBootTest
@ActiveProfiles("test")
class DashboardControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private com.example.ajs.auth.repository.UserRepository userRepository;

    /**
     * MockMvc is built by hand with {@code springSecurity()} applied.
     *
     * <p>Why not {@code @AutoConfigureMockMvc}: in this project it did not apply
     * the security filter chain, so unauthenticated requests returned 200 in
     * tests while the running application returned 401. Asserting "the gate is
     * closed" against a harness with no gate installed is worse than not testing
     * it, so the chain is wired explicitly here. The application itself was
     * verified correct over real HTTP; see NOTES.md.
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
        authenticate();
    }

    /**
     * An ordinary, non-admin user, built fresh in the database so the filter's
     * mandatory DB re-check can find it — a token alone is not enough.
     */
    private RequestPostProcessor asUser;

    @BeforeEach
    void authenticate() {
        var existing = userRepository.findByEmail("dash-viewer@test.local");
        var user = existing.orElseGet(() -> userRepository.create(
                "dash-viewer@test.local", "unused-bcrypt-hash", "Dash Viewer",
                com.example.ajs.auth.domain.User.Role.USER));

        // Build the same authentication the JwtAuthenticationFilter would set:
        // principal is the domain User record, authority is ROLE_<role>.
        // Using SecurityMockMvcRequestPostProcessors.user(...) is not possible
        // here because that helper expects a UserDetails, and this application
        // deliberately uses its own User record as the principal so controllers
        // can read the account without a second query.
        var authentication = new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(
                user, null,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));

        asUser = SecurityMockMvcRequestPostProcessors.authentication(authentication);
    }

    @Test
    @DisplayName("dashboards reject an unauthenticated request")
    void dashboardsRequireAuthentication() throws Exception {
        // Deliberately NO .with(asUser): this must be the anonymous case. If
        // these ever return 200, the observability data is exposed to anyone who
        // can reach the port.
        mockMvc.perform(get("/overview"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/jobs"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("overview renders the counts and explains *NOSBM exclusions")
    void overviewRenders() throws Exception {
        mockMvc.perform(get("/overview").with(asUser))
                .andExpect(status().isOk())
                .andExpect(view().name("overview"))
                .andExpect(model().attributeExists("overview"))
                .andExpect(content().string(containsString("AJS Observability")))
                .andExpect(content().string(containsString("Job definitions")))
                .andExpect(content().string(containsString("Genuine failures")))
                // The *NOSBM explanation must be visible, not hidden.
                .andExpect(content().string(containsString("*NOSBM")));
    }

    @Test
    @DisplayName("root path serves the overview")
    void rootServesOverview() throws Exception {
        mockMvc.perform(get("/").with(asUser))
                .andExpect(status().isOk())
                .andExpect(view().name("overview"));
    }

    @Test
    @DisplayName("failures page separates genuine failures from skipped jobs")
    void failuresPageSplitsTheTwoCategories() throws Exception {
        mockMvc.perform(get("/failures").with(asUser))
                .andExpect(status().isOk())
                .andExpect(view().name("failures"))
                .andExpect(model().attribute("failures",
                        org.hamcrest.Matchers.hasSize(1)))
                .andExpect(model().attribute("skipped",
                        org.hamcrest.Matchers.hasSize(1)))
                .andExpect(content().string(containsString("PAYROLL2")))
                .andExpect(content().string(containsString("PAYROLL3")))
                .andExpect(content().string(containsString("Skipped, not failed")));
    }

    @Test
    @DisplayName("calendar defaults to today and lists jobs for an explicit date")
    void calendarRenders() throws Exception {
        mockMvc.perform(get("/calendar").with(asUser))
                .andExpect(status().isOk())
                .andExpect(view().name("calendar"))
                .andExpect(model().attributeExists("targetDate"));

        mockMvc.perform(get("/calendar").param("date", "2025-11-03").with(asUser))
                .andExpect(status().isOk())
                .andExpect(model().attribute("jobs",
                        org.hamcrest.Matchers.hasSize(4)))
                .andExpect(content().string(containsString("PAYROLL1")));
    }

    @Test
    @DisplayName("a malformed date shows a warning instead of a 400 error page")
    void malformedDateIsTolerated() throws Exception {
        mockMvc.perform(get("/calendar").param("date", "not-a-date").with(asUser))
                .andExpect(status().isOk())
                .andExpect(view().name("calendar"))
                .andExpect(model().attributeExists("warning"))
                .andExpect(content().string(containsString("Unrecognised date")));
    }

    @Test
    @DisplayName("inventory groups jobs by application")
    void inventoryRenders() throws Exception {
        mockMvc.perform(get("/jobs").with(asUser))
                .andExpect(status().isOk())
                .andExpect(view().name("jobs"))
                .andExpect(content().string(containsString("FINANCE")))
                .andExpect(content().string(containsString("OPS")))
                .andExpect(content().string(containsString("REPORTING")));
    }

    @Test
    @DisplayName("job detail renders the definition and the QAIJS1CM command list")
    void jobDetailRendersCommands() throws Exception {
        mockMvc.perform(get("/jobs/PAYROLL1").with(asUser))
                .andExpect(status().isOk())
                .andExpect(view().name("job-detail"))
                .andExpect(content().string(containsString("PAYROLL1")))
                .andExpect(content().string(containsString("CALL PGM(PAYLIB/PAYEXT)")))
                .andExpect(content().string(containsString("Command list")));
    }

    @Test
    @DisplayName("a job with the CYYMMDD sentinel shows 'not scheduled' rather than a bogus date")
    void jobDetailExplainsSentinel() throws Exception {
        mockMvc.perform(get("/jobs/REPORT1").with(asUser))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("9999999")))
                .andExpect(content().string(containsString("not scheduled")));
    }

    @Test
    @DisplayName("a *NOSBM job detail explains why it is not a failure")
    void jobDetailExplainsNosbm() throws Exception {
        mockMvc.perform(get("/jobs/PAYROLL3").with(asUser))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("*NOSBM")))
                .andExpect(content().string(containsString("not counted as a failure")));
    }

    @Test
    @DisplayName("an unknown job renders a helpful not-found page, not a stack trace")
    void unknownJobIsHandled() throws Exception {
        mockMvc.perform(get("/jobs/NOSUCHJOB").with(asUser))
                .andExpect(status().isOk())
                .andExpect(view().name("job-not-found"))
                .andExpect(content().string(containsString("Job not found")))
                .andExpect(content().string(containsString("NOSUCHJOB")));
    }

    @Test
    @DisplayName("attention page lists held and never-run jobs")
    void attentionRenders() throws Exception {
        mockMvc.perform(get("/attention").with(asUser))
                .andExpect(status().isOk())
                .andExpect(view().name("attention"))
                .andExpect(content().string(containsString("Held jobs")))
                .andExpect(content().string(containsString("Never run")))
                .andExpect(content().string(containsString("ARCHIVE1")))
                .andExpect(content().string(containsString("REPORT1")));
    }

    @Test
    @DisplayName("no dashboard leaks an unrendered Thymeleaf expression")
    void noUnrenderedExpressions() throws Exception {
        for (String path : new String[]{"/overview", "/failures", "/jobs",
                "/attention", "/calendar", "/jobs/PAYROLL1"}) {
            mockMvc.perform(get(path).with(asUser))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("${"))));
        }
    }
}
