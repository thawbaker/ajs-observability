package com.example.ajs.service;

import com.example.ajs.domain.AjsJob;
import com.example.ajs.repository.AjsRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read-only view logic for the AJS dashboards.
 *
 * <p>Keeps the domain semantics from {@code req.txt} §3 out of the templates:
 * the {@code *NOSBM} false-alarm rule, the never-run and held classifications,
 * and the CYYMMDD sentinel handling are all decided here, where they can be
 * unit-tested, rather than in Thymeleaf expressions.
 */
public class AjsObservabilityService {

    private final AjsRepository repository;

    public AjsObservabilityService(AjsRepository repository) {
        this.repository = repository;
    }

    /** Headline counters for the overview page. */
    public Overview overview() {
        List<AjsJob> all = repository.findAllJobs();
        LocalDate today = LocalDate.now();

        int ready = (int) all.stream().filter(AjsJob::ready).count();
        int held = (int) all.stream().filter(AjsJob::held).count();
        int neverRun = (int) all.stream().filter(AjsJob::neverRun).count();

        // Genuine failures only: *NOSBM skips are excluded so the number does
        // not cry wolf (req.txt §3).
        int failures = (int) all.stream().filter(AjsJob::genuineFailure).count();

        // Reported separately so the exclusions stay visible rather than hidden.
        int skippedNotFailed = (int) all.stream()
                .filter(job -> job.lastOutcome() == AjsJob.LastOutcome.ABNORMAL)
                .filter(AjsJob::skippedRatherThanFailed)
                .count();

        int dueToday = (int) all.stream()
                .filter(AjsJob::ready)
                .filter(job -> job.nextRunDate().filter(today::equals).isPresent())
                .count();

        int unscheduled = (int) all.stream().filter(job -> job.nextRunDate().isEmpty()).count();

        return new Overview(all.size(), ready, held, dueToday, failures, skippedNotFailed,
                neverRun, unscheduled);
    }

    /** Jobs due on the given date, ready to run. */
    public List<AjsJob> jobsDueOn(LocalDate date) {
        return repository.findScheduledOn(date);
    }

    /**
     * Jobs that genuinely failed — abnormal completion, excluding {@code *NOSBM}
     * skips (req.txt §3 false-alarm rule).
     */
    public List<AjsJob> genuineFailures() {
        return repository.findByCompletionCode("A").stream()
                .filter(AjsJob::genuineFailure)
                .toList();
    }

    /**
     * Abnormal completions that are <em>not</em> failures, because the recovery
     * action means they were skipped. Surfaced so operators can see why the
     * failure count differs from a raw {@code JMLCC='A'} query.
     */
    public List<AjsJob> skippedNotFailed() {
        return repository.findByCompletionCode("A").stream()
                .filter(AjsJob::skippedRatherThanFailed)
                .toList();
    }

    public List<AjsJob> neverRun() {
        return repository.findNeverRun();
    }

    public List<AjsJob> held() {
        return repository.findHeld();
    }

    public List<AjsJob> allJobs() {
        return repository.findAllJobs();
    }

    public List<String> commandsFor(AjsJob job) {
        return repository.findCommandsForJob(job.jobName());
    }

    public int jobCount() {
        return repository.countJobs();
    }

    /** Jobs grouped by application group, for the inventory page. */
    public Map<String, List<AjsJob>> byApplicationGroup() {
        return repository.findAllJobs().stream()
                .collect(Collectors.groupingBy(
                        job -> job.applicationGroup() == null || job.applicationGroup().isBlank()
                                ? "(none)" : job.applicationGroup(),
                        Collectors.toList()));
    }

    /** Overview counters. A record so the template cannot mutate them. */
    public record Overview(
            int totalJobs,
            int ready,
            int held,
            int dueToday,
            int genuineFailures,
            int skippedNotFailed,
            int neverRun,
            int unscheduled) {
    }
}
