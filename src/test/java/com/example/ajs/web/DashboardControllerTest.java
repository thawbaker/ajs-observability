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
    @DisplayName("timeline renders bars, and a bad range warns instead of returning 400")
    void timelineRendersAndToleratesBadInput() throws Exception {
        // The window is pinned to the fixture's seeded history. Using "now" would
        // silently render the empty state and assert nothing about the bars.
        mockMvc.perform(get("/timeline")
                        .param("from", "2025-11-20T00:00")
                        .param("to", "2025-11-21T00:00")
                        .with(asUser))
                .andExpect(status().isOk())
                .andExpect(view().name("timeline"))
                .andExpect(model().attributeExists("lanes", "from", "to", "scale"))
                .andExpect(content().string(containsString("Run timeline")))
                // A real bar with server-side geometry, proving the percentage
                // arithmetic rendered rather than an unformatted expression.
                .andExpect(content().string(containsString("class=\"tl-bar tl-")))
                .andExpect(content().string(containsString("tl-abnormal")))
                .andExpect(content().string(org.hamcrest.Matchers.matchesPattern(
                        "(?s).*left: [0-9.]+%; width: [0-9.]+%.*")))
                // The table and legend come AFTER the chart in the template. A
                // parse error part-way down produced a truncated page that still
                // returned HTTP 200 and still contained the strings above, so
                // these assert the tail actually rendered.
                .andExpect(content().string(containsString("Runs in window")))
                .andExpect(content().string(containsString("tl-legend")))
                .andExpect(content().string(containsString("</html>")));

        // An unparseable range must degrade to the default window with a visible
        // warning. On a monitoring tool a 400 here is a support call, and a blank
        // chart would read as "nothing ran".
        mockMvc.perform(get("/timeline").param("from", "not-a-date").with(asUser))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Unrecognised start")));

        // An inverted range is the dangerous case: every bar would sit off-screen
        // and the page would imply no jobs ran.
        mockMvc.perform(get("/timeline")
                        .param("from", "2025-11-21T00:00")
                        .param("to", "2025-11-20T00:00")
                        .with(asUser))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Start must be before end")));
    }

    @Test
    @DisplayName("timeline scale is validated rather than trusted")
    void timelineScaleIsValidated() throws Exception {
        // The scale drives tick arithmetic in the template, so an arbitrary value
        // from the query string must not reach it unbounded.
        mockMvc.perform(get("/timeline").param("scale", "hour").with(asUser))
                .andExpect(status().isOk())
                .andExpect(model().attribute("scale", "hour"));

        mockMvc.perform(get("/timeline").param("scale", "<script>").with(asUser))
                .andExpect(status().isOk())
                .andExpect(model().attribute("scale", "auto"));
    }

    @Test
    @DisplayName("an over-wide window is narrowed and the narrowing is stated, not silent")
    void overWideWindowIsNarrowedAndExplained() throws Exception {
        // The page bound, not the query bound: every run is a bar and a table row,
        // so the response grows with the window width. A five-year request must not
        // be served as-is, and it must not 400 either — on a monitoring tool a 400
        // is a support call. It is narrowed, and the page says so.
        mockMvc.perform(get("/timeline")
                        .param("from", "2020-01-01T00:00")
                        .param("to", "2025-01-01T00:00")
                        .with(asUser))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("wider than the")))
                .andExpect(content().string(containsString("maximum")))
                // The page still rendered in full, not truncated mid-template.
                .andExpect(content().string(containsString("</html>")));

        // And the effective window really was reduced to the cap.
        mockMvc.perform(get("/timeline")
                        .param("from", "2020-01-01T00:00")
                        .param("to", "2025-01-01T00:00")
                        .with(asUser))
                .andExpect(model().attribute("from",
                        java.time.LocalDateTime.of(2025, 1, 1, 0, 0)
                                .minus(DashboardController.MAX_WINDOW)));
    }

    @Test
    @DisplayName("a window at or under the maximum is served unchanged, with no warning")
    void windowWithinMaximumIsNotNarrowed() throws Exception {
        // The guard must not fire on ordinary use. A 31-day window is allowed; the
        // boundary case is checked so an off-by-one in the comparison cannot
        // silently shorten every legitimate request.
        mockMvc.perform(get("/timeline")
                        .param("from", "2025-11-01T00:00")
                        .param("to", "2025-11-20T00:00")
                        .with(asUser))
                .andExpect(status().isOk())
                .andExpect(model().attribute("warning", (Object) null));

        mockMvc.perform(get("/timeline")
                        .param("from", "2025-11-01T00:00")
                        .param("to", "2025-12-02T00:00")
                        .with(asUser))
                .andExpect(status().isOk())
                .andExpect(model().attribute("warning", (Object) null));
    }

    @Test
    @DisplayName("the timeline page reports truncation instead of an understated total")
    void truncationIsReportedOnThePage() throws Exception {
        // Not truncated in this ordinary fixture window: the totals must reconcile
        // with the bars, and no notice should appear. The truncated rendering path
        // is covered at the service tier, where a 200+ run lane can be built
        // without seeding one into the shared fixture.
        mockMvc.perform(get("/timeline")
                        .param("from", "2025-11-20T00:00")
                        .param("to", "2025-11-21T00:00")
                        .with(asUser))
                .andExpect(status().isOk())
                .andExpect(model().attribute("runsTruncated", false))
                .andExpect(model().attribute("omittedRuns", 0L))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(containsString("are not drawn"))));
    }

    @Test
    @DisplayName("timeline over a window with no runs says so explicitly")
    void timelineEmptyWindowIsExplicit() throws Exception {
        // A blank chart could mean "no runs" or "the query failed". The page must
        // distinguish them, or the monitor loses trust the first time it is wrong.
        mockMvc.perform(get("/timeline")
                        .param("from", "1990-01-01T00:00")
                        .param("to", "1990-01-02T00:00")
                        .with(asUser))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No runs recorded in this window")));
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
