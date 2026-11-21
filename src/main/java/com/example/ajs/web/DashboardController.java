package com.example.ajs.web;

import com.example.ajs.domain.AjsJob;
import com.example.ajs.service.AjsObservabilityService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.LocalDate;
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

    private final AjsObservabilityService service;

    public DashboardController(AjsObservabilityService service) {
        this.service = service;
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

    /** Held and never-run jobs — the "is anything stuck?" view. */
    @GetMapping("/attention")
    public String attention(Model model) {
        model.addAttribute("held", service.held());
        model.addAttribute("neverRun", service.neverRun());
        model.addAttribute("active", "attention");
        return "attention";
    }
}
