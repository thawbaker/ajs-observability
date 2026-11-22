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
    public TimelineResult timeline(LocalDateTime from, LocalDateTime to) {
        List<JobRun> runs = repository.findRunsBetween(from, to);

        Map<String, List<JobRun>> byJob = runs.stream()
                .collect(Collectors.groupingBy(run -> run.jobName().toUpperCase()));

        // Lanes come from the master file, not from the history rows: a lane must
        // still appear (empty) for a job that was expected to run but did not.
        // Deriving lanes from history alone would silently hide that job, which is
        // the one case an operator most needs to see.
        List<TimelineLane> lanes = repository.findAllJobs().stream()
                .map(job -> {
                    List<JobRun> all = byJob.getOrDefault(
                            job.jobName().toUpperCase(), List.of());
                    List<JobRun> shown = capPerLane(all);
                    return new TimelineLane(job, shown, gapsBetween(shown),
                            all.size() - shown.size());
                })
                .sorted(Comparator
                        .comparing((TimelineLane l) -> nullSafe(l.job().applicationGroup()))
                        .thenComparing(l -> l.job().jobName()))
                .toList();

        // Counted over EVERY run that lands on a lane, BEFORE capping. This is the
        // subtle half of the bound: computing the totals from the drawn lanes
        // instead would make a truncated job's failures and still-running runs
        // disappear from the summary — a false all-clear produced by a display
        // decision, which is precisely the failure this page must never have.
        //
        // The totals are gathered from the lanes' unattributed runs, not from the
        // raw query result, because history rows whose job is absent from the
        // master file have never appeared on this page: lanes come from the master
        // file, so an orphan row has no lane to be drawn on. Folding those into the
        // total would make omittedRuns() — which exists to mean "dropped by the
        // cap" — also mean "orphaned", and the two call for different responses:
        // the first says narrow the range, the second says the files disagree.
        List<JobRun> allLaneRuns = new ArrayList<>();
        for (TimelineLane lane : lanes) {
            allLaneRuns.addAll(byJob.getOrDefault(
                    lane.job().jobName().toUpperCase(), List.of()));
        }

        long failures = allLaneRuns.stream()
                .filter(r -> r.outcome() == AjsJob.LastOutcome.ABNORMAL)
                .count();
        long running = allLaneRuns.stream().filter(JobRun::stillRunning).count();
        long totalRuns = lanes.stream().mapToLong(TimelineLane::totalRuns).sum();
        long drawnRuns = lanes.stream().mapToLong(l -> l.runs().size()).sum();

        return new TimelineResult(lanes, totalRuns, drawnRuns, failures, running);
    }

    /**
     * A rendered timeline window: the lanes to draw, plus totals for the whole
     * window rather than only the drawn part.
     *
     * <p>Returning both is the point. The lanes are capped to keep the response
     * bounded ({@link #MAX_RUNS_PER_LANE}), and the totals are deliberately not —
     * so the summary line can say "200 of 1,440 runs shown" instead of quietly
     * reporting 200 as the total. A monitor that understates activity when it is
     * busiest is worse than one that is slow.
     *
     * @param totalRuns  every run in the window
     * @param drawnRuns  runs actually rendered ({@code <= totalRuns})
     * @param failures   abnormal runs across the whole window, drawn or not
     * @param running    still-running jobs across the whole window, drawn or not
     */
    public record TimelineResult(List<TimelineLane> lanes, long totalRuns, long drawnRuns,
                                 long failures, long running) {

        /** Runs in the window that the page does not draw, because of the cap. */
        public long omittedRuns() {
            return totalRuns - drawnRuns;
        }

        /** Whether any lane had to drop older runs to stay within the cap. */
        public boolean truncated() {
            return omittedRuns() > 0;
        }
    }

    /**
     * Most runs drawn per job. The response size is what this bounds.
     *
     * <p>Why this exists rather than pagination: the timeline is a density view,
     * and its bar positions are percentages <em>of the window</em>. Splitting runs
     * across pages would not make it smaller — it would make it wrong. A page of
     * runs drawn against the full window would leave the plot looking almost empty
     * for a job that actually ran constantly, which is the exact misreading this
     * page exists to prevent. The same objection kills pagination of the detail
     * table: "gap from prior" is sequential by definition, so the first row of any
     * page after the first has no truthful value.
     *
     * <p>So the bound is applied per lane, keeping the <em>most recent</em> runs.
     * Recency is the right end to keep: an operator reading a timeline is asking
     * "what is it doing now", and the newest runs are the ones that answer it.
     * Discarding the oldest is also what keeps the plot legible — the bars that
     * survive are the ones in the right-hand, most-recently-viewed part of the
     * window.
     *
     * <p>A truncated lane is <strong>reported</strong>, not silently shortened
     * ({@link TimelineLane#omittedRuns()}), and the page states the figure. Silent
     * truncation would understate the run count and could hide failures — a false
     * all-clear, which is the one outcome worse than a slow page.
     */
    static final int MAX_RUNS_PER_LANE = 200;

    /** Keeps the newest {@value #MAX_RUNS_PER_LANE} runs; order is preserved. */
    private static List<JobRun> capPerLane(List<JobRun> runs) {
        if (runs.size() <= MAX_RUNS_PER_LANE) {
            return runs;
        }
        return List.copyOf(runs.subList(runs.size() - MAX_RUNS_PER_LANE, runs.size()));
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
     *
     * <p>{@code omittedRuns} is how many older runs this lane had in the window
     * but did not draw, because {@link #MAX_RUNS_PER_LANE} was reached. It is a
     * count and not a boolean on purpose: the page reports the exact figure, so a
     * truncated lane reads as "200 of 1,440 shown" rather than as a smaller total.
     * Zero for every lane in the ordinary case.
     */
    public record TimelineLane(AjsJob job, List<JobRun> runs, List<Long> gaps,
                               int omittedRuns) {

        /** Whether this job was expected to run in the window but produced no run. */
        public boolean expectedButDidNotRun() {
            return runs.isEmpty() && job.ready();
        }

        /** Whether this lane had to drop older runs to stay within the cap. */
        public boolean truncated() {
            return omittedRuns > 0;
        }

        /** Total runs in the window, including any not drawn. */
        public int totalRuns() {
            return runs.size() + omittedRuns;
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
