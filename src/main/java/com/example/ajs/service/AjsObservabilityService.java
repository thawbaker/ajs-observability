package com.example.ajs.service;

import com.example.ajs.domain.AjsJob;
import com.example.ajs.domain.JobRun;
import com.example.ajs.repository.AjsRepository;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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

    /**
     * Runs in the window, grouped into one lane per job for the timeline view.
     *
     * <p>Grouping and the gap calculation live here rather than in Thymeleaf so
     * the arithmetic is unit-testable. Templates get a display-ready structure.
     *
     * <p>Lane order is application group then job name, matching the Inventory
     * view, so a job sits in the same relative position on both pages — moving
     * between them does not require re-finding a row.
     */
    public List<TimelineLane> timeline(LocalDateTime from, LocalDateTime to) {
        List<JobRun> runs = repository.findRunsBetween(from, to);

        Map<String, List<JobRun>> byJob = runs.stream()
                .collect(Collectors.groupingBy(run -> run.jobName().toUpperCase()));

        // Lanes come from the master file, not from the history rows: a lane must
        // still appear (empty) for a job that was expected to run but did not.
        // Deriving lanes from history alone would silently hide that job, which is
        // the one case an operator most needs to see.
        return repository.findAllJobs().stream()
                .map(job -> {
                    List<JobRun> jobRuns = byJob.getOrDefault(
                            job.jobName().toUpperCase(), List.of());
                    return new TimelineLane(job, jobRuns, gapsBetween(jobRuns));
                })
                .sorted(Comparator
                        .comparing((TimelineLane l) -> nullSafe(l.job().applicationGroup()))
                        .thenComparing(l -> l.job().jobName()))
                .toList();
    }

    /**
     * Seconds between the end of each run and the start of the next, in order.
     *
     * <p>The first entry is always {@code null} — there is no prior run to compare
     * against — which keeps the list index-aligned with the run list so a template
     * can pair them without tracking offsets.
     *
     * <p>A negative gap is clamped to zero rather than shown as-is. Overlapping
     * runs are real (a job can be resubmitted while the previous one is still
     * going) but a negative "gap until next run" is meaningless, and it would break
     * anything that treats a gap as a length.
     */
    static List<Long> gapsBetween(List<JobRun> runs) {
        List<Long> gaps = new ArrayList<>(runs.size());
        for (int i = 0; i < runs.size(); i++) {
            if (i == 0) {
                gaps.add(null);
                continue;
            }
            JobRun prev = runs.get(i - 1);
            // A still-running previous run has no end to measure from: unknown,
            // not zero.
            LocalDateTime prevEnd = prev.duration()
                    .map(prev.startedAt()::plus)
                    .orElse(null);
            if (prevEnd == null) {
                gaps.add(null);
                continue;
            }
            gaps.add(Math.max(0, Duration.between(prevEnd, runs.get(i).startedAt()).getSeconds()));
        }
        return gaps;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /**
     * One job's lane on the timeline: its definition, its runs in the window, and
     * the gap before each run.
     *
     * <p>{@code gaps} is index-aligned with {@code runs}; its first element is
     * always null.
     */
    public record TimelineLane(AjsJob job, List<JobRun> runs, List<Long> gaps) {

        /** Whether this job was expected to run in the window but produced no run. */
        public boolean expectedButDidNotRun() {
            return runs.isEmpty() && job.ready();
        }
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
