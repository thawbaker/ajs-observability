package com.example.ajs.service;

import com.example.ajs.domain.AjsJob;
import com.example.ajs.domain.JobRun;
import com.example.ajs.repository.AjsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the view logic, focusing on the domain rules that are easy to
 * get wrong and expensive to get wrong — chiefly the {@code *NOSBM} false-alarm
 * rule from {@code req.txt} §3.
 */
class AjsObservabilityServiceTest {

    private AjsRepository repository;
    private AjsObservabilityService service;

    @BeforeEach
    void setUp() {
        repository = mock(AjsRepository.class);
        service = new AjsObservabilityService(repository);
    }

    @Test
    @DisplayName("genuine failures exclude *NOSBM jobs that were skipped, not failed")
    void genuineFailuresExcludeSkippedJobs() {
        when(repository.findByCompletionCode("A")).thenReturn(List.of(
                job("REALFAIL", "A", "*SBMJOB", Optional.of(LocalDate.of(2025, 11, 1))),
                job("SKIPPED", "A", "*NOSBM", Optional.of(LocalDate.of(2025, 11, 1)))));
        when(repository.findAllJobs()).thenReturn(List.of(
                job("REALFAIL", "A", "*SBMJOB", Optional.of(LocalDate.of(2025, 11, 1))),
                job("SKIPPED", "A", "*NOSBM", Optional.of(LocalDate.of(2025, 11, 1)))));

        assertThat(service.genuineFailures()).extracting(AjsJob::jobName)
                .containsExactly("REALFAIL");
        assertThat(service.skippedNotFailed()).extracting(AjsJob::jobName)
                .containsExactly("SKIPPED");
    }

    @Test
    @DisplayName("overview counts failures excluding skips and reports the exclusion separately")
    void overviewSeparatesSkipsFromFailures() {
        List<AjsJob> jobs = List.of(
                job("OK1", "N", "*SBMJOB", Optional.of(LocalDate.now())),
                job("FAIL1", "A", "*SBMJOB", Optional.of(LocalDate.now())),
                job("SKIP1", "A", "*NOSBM", Optional.of(LocalDate.now())),
                heldJob("HOLD1"),
                neverRunJob("NEW1"));
        when(repository.findAllJobs()).thenReturn(jobs);

        AjsObservabilityService.Overview overview = service.overview();

        assertThat(overview.totalJobs()).isEqualTo(5);
        assertThat(overview.genuineFailures()).isEqualTo(1);
        assertThat(overview.skippedNotFailed()).isEqualTo(1);
        assertThat(overview.held()).isEqualTo(1);
        assertThat(overview.neverRun()).isEqualTo(1); // NEW1 has no last-run date
        // OK1, FAIL1, SKIP1 and NEW1 are *READY with a next run of today.
        // HOLD1 is also dated today but is *HELD, so it must not be counted.
        assertThat(overview.dueToday()).isEqualTo(4);
    }

    @Test
    @DisplayName("unscheduled jobs are counted but not treated as due")
    void unscheduledJobsAreCountedSeparately() {
        when(repository.findAllJobs()).thenReturn(List.of(
                job("NOSCHED", "N", "*SBMJOB", Optional.empty())));

        AjsObservabilityService.Overview overview = service.overview();

        assertThat(overview.unscheduled()).isEqualTo(1);
        assertThat(overview.dueToday()).isZero();
    }

    @Test
    @DisplayName("jobs due delegates to the repository with the requested date")
    void jobsDueDelegates() {
        LocalDate date = LocalDate.of(2025, 11, 3);
        when(repository.findScheduledOn(date)).thenReturn(List.of(
                job("DUE1", "N", "*SBMJOB", Optional.of(date))));

        assertThat(service.jobsDueOn(date)).extracting(AjsJob::jobName).containsExactly("DUE1");
    }

    @Test
    @DisplayName("inventory groups by application, with a placeholder for blanks")
    void inventoryGroupsByApplication() {
        when(repository.findAllJobs()).thenReturn(List.of(
                withApp("A1", "FINANCE"),
                withApp("A2", "FINANCE"),
                withApp("A3", "OPS"),
                withApp("A4", null)));

        assertThat(service.byApplicationGroup())
                .containsOnlyKeys("FINANCE", "OPS", "(none)")
                .satisfies(groups -> {
                    assertThat(groups.get("FINANCE")).hasSize(2);
                    assertThat(groups.get("OPS")).hasSize(1);
                    assertThat(groups.get("(none)")).hasSize(1);
                });
    }

    @Test
    @DisplayName("commands and counts delegate without transformation")
    void delegatesCommandsAndCount() {
        when(repository.findCommandsForJob(anyString()))
                .thenReturn(List.of("CALL PGM(X)"));
        when(repository.countJobs()).thenReturn(42);

        AjsJob job = job("X", "N", "*SBMJOB", Optional.empty());
        assertThat(service.commandsFor(job)).containsExactly("CALL PGM(X)");
        assertThat(service.jobCount()).isEqualTo(42);
    }

    @Test
    @DisplayName("an empty partition produces zeros, not a crash")
    void emptyPartitionIsSafe() {
        when(repository.findAllJobs()).thenReturn(List.of());
        when(repository.findByCompletionCode(any())).thenReturn(List.of());

        AjsObservabilityService.Overview overview = service.overview();

        assertThat(overview.totalJobs()).isZero();
        assertThat(service.genuineFailures()).isEmpty();
        assertThat(service.byApplicationGroup()).isEmpty();
    }

    // --- helpers -----------------------------------------------------------

    // ---------------------------------------------------------------------
    // Timeline: run-window filtering and gap arithmetic.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("timeline keeps a lane for a job that was expected to run but produced no run")
    void timelineKeepsEmptyLaneForExpectedJob() {
        // The whole point of building lanes from the master file rather than from
        // history rows: a ready job with no runs in the window must still appear,
        // because "it never ran" is the thing an operator is looking for.
        when(repository.findAllJobs()).thenReturn(List.of(
                withApp("RAN", "APP"), withApp("SILENT", "APP")));
        when(repository.findRunsBetween(any(), any())).thenReturn(List.of(
                run("RAN", 1, LocalDateTime.of(2025, 11, 20, 23, 0), 142, "N")));

        List<AjsObservabilityService.TimelineLane> lanes =
                service.timeline(LocalDateTime.of(2025, 11, 20, 0, 0),
                                 LocalDateTime.of(2025, 11, 21, 0, 0));

        assertThat(lanes).hasSize(2);
        assertThat(lanes).extracting(l -> l.job().jobName())
                .containsExactly("RAN", "SILENT");
        assertThat(lanes.get(1).runs()).isEmpty();
        assertThat(lanes.get(1).expectedButDidNotRun()).isTrue();
        assertThat(lanes.get(0).expectedButDidNotRun()).isFalse();
    }

    @Test
    @DisplayName("gaps are null for the first run and for a run following one still in progress")
    void gapsAreNullWhenThereIsNoMeasurablePriorEnd() {
        // Index alignment matters: the template pairs gaps[i] with runs[i] without
        // tracking offsets, so the list must stay the same length as the runs.
        List<JobRun> runs = List.of(
                run("J", 1, LocalDateTime.of(2025, 11, 20, 1, 0), 60, "N"),
                run("J", 1, LocalDateTime.of(2025, 11, 20, 2, 0), null, null),  // running
                run("J", 1, LocalDateTime.of(2025, 11, 20, 3, 0), 60, "N"));

        List<Long> gaps = AjsObservabilityService.gapsBetween(runs);

        assertThat(gaps).hasSize(3);
        assertThat(gaps.get(0)).as("first run has no prior run").isNull();
        assertThat(gaps.get(1)).as("measured from the end of run 1").isEqualTo(3540L);
        assertThat(gaps.get(2)).as("prior run still in progress: no end to measure from")
                .isNull();
    }

    @Test
    @DisplayName("an overlapping run yields a zero gap rather than a negative one")
    void gapsClampNegativeToZero() {
        // Run 2 starts while run 1 is still going, which is a real scenario when a
        // job is resubmitted. A negative gap would render as a backwards bar.
        List<JobRun> runs = List.of(
                run("J", 1, LocalDateTime.of(2025, 11, 20, 1, 0), 3600, "N"),
                run("J", 1, LocalDateTime.of(2025, 11, 20, 1, 30), 60, "N"));

        assertThat(AjsObservabilityService.gapsBetween(runs).get(1)).isZero();
    }

    @Test
    @DisplayName("a run with no elapsed time is running, not a zero-length success")
    void runWithoutElapsedTimeIsStillRunning() {
        // Treating a null HSELAP as duration zero would draw an in-flight job as an
        // instantaneous success, which is a false all-clear on a monitoring view.
        JobRun inFlight = run("J", 1, LocalDateTime.of(2025, 11, 20, 1, 0), null, null);

        assertThat(inFlight.stillRunning()).isTrue();
        assertThat(inFlight.duration()).isEmpty();
        assertThat(inFlight.durationDisplay()).isEqualTo("running");
    }

    private JobRun run(String name, int seq, LocalDateTime start, Integer elapsed, String cc) {
        return new JobRun(name, seq, start, cc, elapsed);
    }

    private AjsJob job(String name, String code, String recovery, Optional<LocalDate> next) {
        return build(name, "*READY", code, recovery, next,
                Optional.of(LocalDate.of(2025, 11, 1)), "APP");
    }

    private AjsJob heldJob(String name) {
        return build(name, "*HELD", "N", "*SBMJOB", Optional.of(LocalDate.now()),
                Optional.of(LocalDate.of(2025, 11, 1)), "APP");
    }

    private AjsJob neverRunJob(String name) {
        return build(name, "*READY", null, "*SBMJOB", Optional.of(LocalDate.now()),
                Optional.empty(), "APP");
    }

    private AjsJob withApp(String name, String app) {
        return build(name, "*READY", "N", "*SBMJOB", Optional.empty(),
                Optional.of(LocalDate.of(2025, 11, 1)), app);
    }

    private AjsJob build(String name, String status, String code, String recovery,
                         Optional<LocalDate> next, Optional<LocalDate> last, String app) {
        return new AjsJob(
                name, 1, "GRP", app, "description",
                "SCHED", "*DAILY", "CAL", "HOL", "FIS",
                next, Optional.empty(),
                "QBATCH", "QGPL", "JOBD", "QGPL", "USER", "QSYSOPR", "QGPL", recovery,
                status, last, "status text", code, 10);
    }
}
