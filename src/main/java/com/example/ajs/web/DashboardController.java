package com.example.ajs.web;

import com.example.ajs.domain.AjsJob;
import com.example.ajs.domain.JobRun;
import com.example.ajs.service.AjsObservabilityService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Server-rendered Thymeleaf dashboards, per {@code req.txt} §3.
 *
 * <p>All routes are GET and read-only. Once the dashboards are settled, an
 * {@code @PreAuthorize} layer should be added so that viewing is authenticated
 * (req.txt §6) — deliberately left out here because the authentication
 * mechanism (Kerberos/SSO vs local) is still open (req.txt §9 question 8).
 */
@Controller
public class DashboardController {

    /**
     * Widest window the timeline page will render.
     *
     * <p>This is a bound on the <em>page</em>, not on the query. Measured on the
     * offline fixture with 200,000 seeded runs: the bounded SQL returns a five-year
     * window in ~0.4s, so the query is not the problem. The problem is that every
     * run becomes a bar and a table row, so the response itself grows without limit
     * with the width of the window. A month is wide enough for every question the
     * timeline answers (the quick ranges offer 24h and 7d) while keeping a single
     * page to a size a browser can render.
     *
     * <p>Wider requests are narrowed, not rejected: on a monitoring tool a 400 is a
     * support call, and the narrowing is announced on the page (see
     * {@code warning}) rather than applied silently.
     */
    static final Duration MAX_WINDOW = Duration.ofDays(31);

    /** Format used when telling the user which window was actually shown. */
    private static final DateTimeFormatter formatter =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm");

    private final AjsObservabilityService service;

    public DashboardController(AjsObservabilityService service) {
        this.service = service;
    }

    /** Human-readable form of {@link #MAX_WINDOW} for the warning message. */
    private static String describeMaxWindow() {
        return MAX_WINDOW.toDays() + "-day";
    }

    /** Overview — counts across the whole partition. */
    @GetMapping({"/", "/overview"})
    public String overview(Model model) {
        model.addAttribute("overview", service.overview());
        model.addAttribute("today", LocalDate.now());
        model.addAttribute("active", "overview");
        return "overview";
    }

    /**
     * Run calendar — jobs due on a date (defaults to today).
     *
     * <p>Accepting the date as a string and parsing defensively avoids a 400 page
     * for a malformed query parameter, which on a monitoring tool is a support
     * call rather than a helpful error.
     */
    @GetMapping("/calendar")
    public String calendar(String date, Model model) {
        LocalDate target = LocalDate.now();
        String warning = null;
        if (date != null && !date.isBlank()) {
            try {
                target = LocalDate.parse(date);
            } catch (DateTimeParseException ex) {
                warning = "Unrecognised date '" + date + "'; showing today instead.";
            }
        }
        List<AjsJob> jobs = service.jobsDueOn(target);
        model.addAttribute("jobs", jobs);
        model.addAttribute("targetDate", target);
        model.addAttribute("warning", warning);
        model.addAttribute("active", "calendar");
        return "calendar";
    }

    /** Failures — genuine failures, plus the *NOSBM skips shown separately. */
    @GetMapping("/failures")
    public String failures(Model model) {
        model.addAttribute("failures", service.genuineFailures());
        model.addAttribute("skipped", service.skippedNotFailed());
        model.addAttribute("active", "failures");
        return "failures";
    }

    /** Inventory — everything AJS knows about, grouped by application group. */
    @GetMapping("/jobs")
    public String jobs(Model model) {
        model.addAttribute("groups", service.byApplicationGroup());
        model.addAttribute("active", "jobs");
        return "jobs";
    }

    /** Job detail — definition plus the command list from QAIJS1CM. */
    @GetMapping("/jobs/{jobName}")
    public String jobDetail(@PathVariable String jobName, Model model) {
        AjsJob job = service.allJobs().stream()
                .filter(j -> j.jobName().equalsIgnoreCase(jobName))
                .findFirst()
                .orElse(null);

        if (job == null) {
            model.addAttribute("jobName", jobName);
            model.addAttribute("active", "jobs");
            return "job-not-found";
        }

        model.addAttribute("job", job);
        model.addAttribute("commands", service.commandsFor(job));
        model.addAttribute("active", "jobs");
        return "job-detail";
    }

    /**
     * Run timeline — every job's runs across a date/time window, as a chart.
     *
     * <p>Defaults to the last 24 hours. Like {@link #calendar}, the range is taken
     * as a string and parsed defensively, so a bad link produces a warning on the
     * page rather than a 400 — on a monitoring tool a 400 is a support call.
     *
     * <p>The scale is a view concern and is echoed back to the template so the
     * selected control stays selected. It is validated against a fixed set rather
     * than trusted, because it also drives tick arithmetic.
     */
    @GetMapping("/timeline")
    public String timeline(String from, String to, String scale, Model model) {
        LocalDateTime end = LocalDateTime.now().withSecond(0).withNano(0);
        LocalDateTime start = end.minusHours(24);
        String warning = null;

        // Accept both `datetime-local` (2025-11-20T23:00) and a plain date.
        if (from != null && !from.isBlank()) {
            try {
                start = parseDateTime(from, false);
            } catch (DateTimeParseException ex) {
                warning = "Unrecognised start '" + from + "'; showing the last 24 hours.";
            }
        }
        if (to != null && !to.isBlank()) {
            try {
                end = parseDateTime(to, true);
            } catch (DateTimeParseException ex) {
                warning = "Unrecognised end '" + to + "'; showing the last 24 hours.";
            }
        }

        // An inverted range would place every bar off-screen and look like "no
        // jobs ran", which is the most dangerous thing this page could imply.
        // Fall back to the default window and say so.
        if (warning == null && !start.isBefore(end)) {
            warning = "Start must be before end; showing the last 24 hours.";
            end = LocalDateTime.now().withSecond(0).withNano(0);
            start = end.minusHours(24);
        }

        // A very wide window is narrowed rather than served, and the narrowing is
        // stated. Not because the query cannot cope — findRunsBetween is bounded by
        // the range and returns a 5-year window over 200k rows in well under a
        // second — but because the *page* cannot: every run is a bar and a table
        // row, so a minutely job over five years is hundreds of thousands of
        // elements in one response.
        //
        // The window is reduced from the end the user is least likely to want. For
        // a monitoring tool that is the far past: someone asking for five years is
        // almost always trying to see a wide sweep, and the recent portion is what
        // they are actually reading. Narrowing keeps the right-hand edge fixed, so
        // the view stays anchored to "now" instead of silently sliding.
        if (warning == null && Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
            LocalDateTime narrowed = end.minus(MAX_WINDOW);
            warning = "Range is wider than the " + describeMaxWindow()
                    + " maximum; showing " + formatter.format(narrowed)
                    + " to " + formatter.format(end) + ". Narrow the range or step"
                    + " through it with the quick ranges.";
            start = narrowed;
        }

        // The service returns the lanes to draw AND the true totals for the window,
        // because the lanes are capped. The counters below therefore describe the
        // whole window, not just the drawn part — see TimelineResult.
        AjsObservabilityService.TimelineResult result = service.timeline(start, end);

        model.addAttribute("lanes", result.lanes());
        model.addAttribute("from", start);
        model.addAttribute("to", end);
        // Passed for the "now" marker. Read once here rather than in the template:
        // a template calling now() twice could place the marker and label it from
        // two different instants.
        model.addAttribute("now", LocalDateTime.now());
        model.addAttribute("scale", validScale(scale));
        model.addAttribute("totalRuns", result.totalRuns());
        model.addAttribute("drawnRuns", result.drawnRuns());
        model.addAttribute("omittedRuns", result.omittedRuns());
        model.addAttribute("runsTruncated", result.truncated());
        model.addAttribute("failureRuns", result.failures());
        model.addAttribute("runningRuns", result.running());
        model.addAttribute("warning", warning);
        model.addAttribute("active", "timeline");
        return "timeline";
    }

    /** Scale names the template offers; anything else falls back to the default. */
    private static final List<String> SCALES =
            List.of("auto", "15min", "hour", "6hour", "day");

    private static String validScale(String scale) {
        return scale != null && SCALES.contains(scale) ? scale : "auto";
    }

    /**
     * Parses a {@code datetime-local} value, or a bare date.
     *
     * <p>A bare date is a whole day, so {@code from} becomes midnight and
     * {@code to} becomes the end of that day. The caller passes which end is
     * being parsed, because the same string means something different at each.
     */
    private static LocalDateTime parseDateTime(String raw, boolean endOfDay) {
        String value = raw.strip();
        if (value.length() <= 10) {
            LocalDate date = LocalDate.parse(value);
            return endOfDay
                    ? date.plusDays(1).atStartOfDay()
                    : date.atStartOfDay();
        }
        return LocalDateTime.parse(value);
    }

    /** Held and never-run jobs — the "is anything stuck?" view. */
    @GetMapping("/attention")
    public String attention(Model model) {
        model.addAttribute("held", service.held());
        model.addAttribute("neverRun", service.neverRun());
        model.addAttribute("active", "attention");
        return "attention";
    }
}
