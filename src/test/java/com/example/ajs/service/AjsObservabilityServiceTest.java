package com.example.ajs.service;

import com.example.ajs.domain.AjsJob;
import com.example.ajs.repository.AjsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
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
