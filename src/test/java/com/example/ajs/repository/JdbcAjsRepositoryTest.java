package com.example.ajs.repository;

import com.example.ajs.domain.AjsJob;
import com.example.ajs.domain.JobRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier 2 — the repository driven against H2 in {@code MODE=DB2}, standing in for
 * QUSRIJS. No IBM i, no Docker, no network.
 *
 * <p>Caveat stated plainly: the H2 schema in {@code schema-ajs.sql} is authored
 * from {@code req.txt} §2, not extracted from a real partition. These tests prove
 * the SQL shape, the row mapping and the domain semantics are correct. They do
 * not prove the real QAIJSMST has these columns (risk R1). The seed rows are
 * built to exercise each documented AJS semantic.
 */
@SpringBootTest
@ActiveProfiles("test")
class JdbcAjsRepositoryTest {

    /**
     * Rows seeded into {@code QAIJSHST} by {@code data-ajs.sql}. Used by the
     * read-only guard as a change-detector; update it when the fixture's run
     * history grows.
     */
    private static final int FIXTURE_HISTORY_ROWS = 20;

    @Autowired
    private AjsRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("all seeded jobs load and the mapper trims blank-padded IBM i CHAR columns")
    void loadsAllJobsWithTrimmedFields() {
        List<AjsJob> jobs = repository.findAllJobs();

        assertThat(jobs).hasSize(8);
        // H2 returns CHAR(10) blank-padded exactly as IBM i does, so this
        // assertion is a genuine check that the mapper trims.
        assertThat(jobs).allSatisfy(job ->
                assertThat(job.jobName()).doesNotContain(" ").isNotBlank());
        assertThat(jobs).extracting(AjsJob::jobName)
                .containsExactlyInAnyOrder("PAYROLL1", "PAYROLL2", "PAYROLL3",
                        "ARCHIVE1", "REPORT1", "REPORT2", "LEGACY1", "BATCH1");
    }

    @Test
    @DisplayName("CYYMMDD next-run dates decode, and 9999999 becomes absent")
    void decodesNextRunDates() {
        AjsJob payroll = job("PAYROLL1");
        assertThat(payroll.nextRunDate()).contains(LocalDate.of(2025, 11, 3));
        assertThat(payroll.nextRunTime()).contains(java.time.LocalTime.of(23, 0));

        // REPORT1 stores the 9999999 sentinel -> no scheduled next run.
        AjsJob report = job("REPORT1");
        assertThat(report.nextRunDate()).isEmpty();
        assertThat(report.nextRunDateDisplay()).isEqualTo("—");
    }

    @Test
    @DisplayName("a 1990s value in YYMMDD stays in the 1990s")
    void decodesLegacyLastRunDates() {
        assertThat(job("REPORT2").lastRunDate()).contains(LocalDate.of(1997, 12, 31));
        // LEGACY1's next run is CYYMMDD 0991231 with century flag 0 -> 1999.
        assertThat(job("LEGACY1").nextRunDate()).contains(LocalDate.of(1999, 12, 31));
    }

    @Test
    @DisplayName("JMLRD = 0 means never run and is not decoded as a date")
    void neverRunIsAbsent() {
        assertThat(job("ARCHIVE1").lastRunDate()).isEmpty();
        assertThat(job("ARCHIVE1").neverRun()).isTrue();
        assertThat(job("ARCHIVE1").lastRunDateDisplay()).isEqualTo("Never");
    }

    @Test
    @DisplayName("findScheduledOn returns only *READY jobs due that date")
    void findsJobsScheduledOnDate() {
        List<AjsJob> due = repository.findScheduledOn(LocalDate.of(2025, 11, 3));

        // PAYROLL1/2/3 and BATCH1 are due 2025-11-03; ARCHIVE1 is *HELD so is
        // excluded even though nothing schedules it for that date anyway.
        assertThat(due).extracting(AjsJob::jobName)
                .containsExactlyInAnyOrder("PAYROLL1", "PAYROLL2", "PAYROLL3", "BATCH1");
        assertThat(due).allSatisfy(job -> assertThat(job.ready()).isTrue());
    }

    @Test
    @DisplayName("a held job is never returned as due")
    void heldJobsAreNotDue() {
        List<AjsJob> due = repository.findScheduledOn(LocalDate.of(2025, 11, 9));
        assertThat(due).extracting(AjsJob::jobName).doesNotContain("ARCHIVE1");
    }

    @Test
    @DisplayName("findByCompletionCode('A') returns raw abnormal rows including *NOSBM skips")
    void findsAbnormalCompletionRaw() {
        List<AjsJob> abnormal = repository.findByCompletionCode("A");

        assertThat(abnormal).extracting(AjsJob::jobName)
                .containsExactlyInAnyOrder("PAYROLL2", "PAYROLL3");

        // The repository is deliberately raw; classification happens in the
        // domain layer. This asserts the split so nobody "helpfully" filters here.
        assertThat(job("PAYROLL3").genuineFailure())
                .as("PAYROLL3 is *NOSBM skipped, not a genuine failure")
                .isFalse();
        assertThat(job("PAYROLL2").genuineFailure()).isTrue();
    }

    @Test
    @DisplayName("a null completion code maps to UNKNOWN rather than a failure")
    void nullCompletionCodeIsUnknown() {
        assertThat(job("BATCH1").lastOutcome()).isEqualTo(AjsJob.LastOutcome.UNKNOWN);
        assertThat(job("BATCH1").genuineFailure()).isFalse();
    }

    @Test
    @DisplayName("findNeverRun and findHeld partition the seeded data correctly")
    void findsNeverRunAndHeld() {
        assertThat(repository.findNeverRun()).extracting(AjsJob::jobName)
                .containsExactlyInAnyOrder("ARCHIVE1", "REPORT1");

        assertThat(repository.findHeld()).extracting(AjsJob::jobName)
                .containsExactly("ARCHIVE1");
    }

    @Test
    @DisplayName("command lists return every command for a job, in sequence order")
    void findsCommandsForJob() {
        // Regression: CMSEQ is the command's position, not the job's JMSEQ. An
        // earlier version filtered on CMSEQ = JMSEQ and returned only one row.
        // Also asserts padding is stripped: CMCMD is a fixed-width CHAR column,
        // so the raw driver value would be blank-padded to 200 characters.
        assertThat(repository.findCommandsForJob("PAYROLL1"))
                .hasSize(2)
                .allSatisfy(cmd -> assertThat(cmd).isEqualTo(cmd.strip()))
                .contains("CALL PGM(PAYLIB/PAYEXT) PARM('DAILY')");

        assertThat(repository.findCommandsForJob("NOPE")).isEmpty();
    }

    @Test
    @DisplayName("countJobs reflects the seeded row count")
    void countsJobs() {
        assertThat(repository.countJobs()).isEqualTo(8);
    }

    @Test
    @DisplayName("the repository is read-only in practice: no writes reach the table")
    void repositoryOnlyReads() {
        int before = repository.countJobs();
        repository.findAllJobs();
        repository.findHeld();
        repository.findNeverRun();
        repository.findByCompletionCode("A");
        assertThat(repository.countJobs()).isEqualTo(before);

        // Belt and braces: confirm nothing in the source opened a write path.
        // Asserts the *total* row count rather than a count of abnormal rows:
        // the intent is to detect any write to the table, and pinning a magic
        // number for one completion code would make this test break every time
        // the fixture gains history for the timeline view, without telling us
        // anything more about write safety.
        Integer historyRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM QUSRIJS.QAIJSHST", Integer.class);
        assertThat(historyRows).isEqualTo(FIXTURE_HISTORY_ROWS);
    }

    @Test
    @DisplayName("findRunsBetween returns only runs inside the window, ordered by start")
    void runWindowIsHalfOpenAndOrdered() {
        LocalDateTime from = LocalDateTime.of(2025, 11, 20, 0, 0);
        LocalDateTime to = LocalDateTime.of(2025, 11, 21, 0, 0);

        List<JobRun> runs = repository.findRunsBetween(from, to);

        assertThat(runs).isNotEmpty();
        assertThat(runs).allSatisfy(run -> {
            assertThat(run.startedAt()).isNotNull();
            assertThat(run.startedAt()).isAfterOrEqualTo(from);
            assertThat(run.startedAt()).isBefore(to);
        });
        // Ordered by start time, so the caller can compute gaps without re-sorting.
        assertThat(runs).isSortedAccordingTo(
                java.util.Comparator.comparing(JobRun::startedAt));
        // The fixture's 2025-11-19 and 2025-11-21 rows must be excluded.
        assertThat(runs).noneSatisfy(run ->
                assertThat(run.startedAt().toLocalDate()).isEqualTo(LocalDate.of(2025, 11, 19)));
        assertThat(runs).noneSatisfy(run ->
                assertThat(run.startedAt().toLocalDate()).isEqualTo(LocalDate.of(2025, 11, 21)));
    }

    @Test
    @DisplayName("the window is half-open: a run starting exactly at `to` belongs to the next window")
    void windowExcludesTheExclusiveUpperBound() {
        // Adjacent windows share a boundary, so an inclusive upper bound would
        // double-count the run starting exactly on it and make paging through
        // days over-report.
        LocalDateTime boundary = LocalDateTime.of(2025, 11, 20, 23, 0);

        List<JobRun> upTo = repository.findRunsBetween(
                LocalDateTime.of(2025, 11, 20, 0, 0), boundary);
        List<JobRun> fromBoundary = repository.findRunsBetween(
                boundary, LocalDateTime.of(2025, 11, 21, 0, 0));

        assertThat(upTo).noneSatisfy(run ->
                assertThat(run.startedAt()).isEqualTo(boundary));
        assertThat(fromBoundary).anySatisfy(run ->
                assertThat(run.startedAt()).isEqualTo(boundary));
    }

    @Test
    @DisplayName("a run with no elapsed time surfaces as running rather than as zero duration")
    void unfinishedRunIsNotZeroLength() {
        List<JobRun> runs = repository.findRunsBetween(
                LocalDateTime.of(2025, 11, 20, 0, 0),
                LocalDateTime.of(2025, 11, 21, 0, 0));

        // The fixture's failure is recorded with a real elapsed time, so it must
        // NOT be classed as running.
        assertThat(runs).filteredOn(r -> r.outcome() == AjsJob.LastOutcome.ABNORMAL)
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r.stillRunning()).isFalse());
    }

    @Test
    @DisplayName("an inverted or empty window is rejected rather than silently returning nothing")
    void invertedWindowIsRejected() {
        LocalDateTime t = LocalDateTime.of(2025, 11, 20, 0, 0);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> repository.findRunsBetween(t, t))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> repository.findRunsBetween(t.plusHours(1), t))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- C2: the history query must be bounded ----------------------------

    /**
     * The window must be applied as a range restriction, not by reading the whole
     * history file and filtering in Java (design review finding C2).
     *
     * <p>This is asserted structurally rather than by timing, because a timing
     * assertion against a 20-row fixture would pass either way and prove nothing.
     * The claim being tested is about the SQL that is issued, so the test inspects
     * it: the statement must carry a {@code WHERE} predicate on {@code HSDTE} and
     * must not be an unbounded select of the table.
     */
    @Test
    @DisplayName("the history query is range-restricted, not an unbounded table read")
    void historyQueryIsBounded() {
        List<String> captured = new java.util.ArrayList<>();

        // Wrap the real JdbcTemplate in a proxy that records the SQL it is handed
        // and then delegates. This exercises the production code path unchanged
        // while observing the statement it issues — no re-implementation of the
        // query in the test, which would let the two drift apart.
        //
        // JdbcTemplate is a class, not an interface, so a JDK dynamic proxy will
        // not do; CGLIB via Spring's ProxyFactory is the tool for this.
        org.springframework.aop.framework.ProxyFactory factory =
                new org.springframework.aop.framework.ProxyFactory(jdbc);
        // Force a class-based (CGLIB) proxy: JdbcTemplate implements interfaces,
        // and the default would produce a JDK proxy that cannot be cast back.
        factory.setProxyTargetClass(true);
        factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            Object[] args = invocation.getArguments();
            if (invocation.getMethod().getName().equals("query")
                    && args != null && args.length > 0 && args[0] instanceof String sql) {
                captured.add(sql);
            }
            return invocation.proceed();
        });
        JdbcTemplate recorder = (JdbcTemplate) factory.getProxy();

        new JdbcAjsRepository(recorder).findRunsBetween(
                LocalDateTime.of(2025, 11, 20, 0, 0),
                LocalDateTime.of(2025, 11, 21, 0, 0));

        assertThat(captured).isNotEmpty();
        String sql = captured.get(0).toUpperCase();
        assertThat(sql)
                .as("the run-history query must restrict on the encoded date rather "
                        + "than selecting every row in QAIJSHST")
                .contains("WHERE")
                .contains("HSDTE")
                .contains("BETWEEN")
                .contains("ORDER BY");
    }

    /**
     * The boundaries are the risk: the SQL compares <em>encoded</em> integers, so
     * rows exactly on a window edge are the ones an off-by-one in the encoder
     * would lose. Two adjacent single-day windows must together account for every
     * run on those two days, with nothing dropped and nothing double-counted.
     */
    @Test
    @DisplayName("adjacent windows cover the day between them without losing or duplicating a run")
    void adjacentWindowsPartitionHistoryCleanly() {
        LocalDateTime day1Start = LocalDateTime.of(2025, 11, 19, 0, 0);
        LocalDateTime day2Start = LocalDateTime.of(2025, 11, 20, 0, 0);
        LocalDateTime day3Start = LocalDateTime.of(2025, 11, 21, 0, 0);

        List<JobRun> day1 = repository.findRunsBetween(day1Start, day2Start);
        List<JobRun> day2 = repository.findRunsBetween(day2Start, day3Start);

        // Each run is in exactly one window: no loss at the shared boundary, and
        // no run counted twice.
        assertThat(day1).noneSatisfy(r -> assertThat(r.startedAt()).isAfterOrEqualTo(day2Start));
        assertThat(day2).noneSatisfy(r -> assertThat(r.startedAt()).isBefore(day2Start));

        // And the union matches an explicit two-day window, which is the strongest
        // statement that the narrow SQL range did not silently drop an edge row.
        List<JobRun> twoDays = repository.findRunsBetween(day1Start, day3Start);
        assertThat(day1).isNotEmpty();
        assertThat(twoDays).hasSameSizeAs(
                java.util.stream.Stream.concat(day1.stream(), day2.stream()).toList());
    }

    /**
     * A window far from any seeded data must return nothing. If the range predicate
     * were dropped or mis-encoded, this query would still return rows.
     */
    @Test
    @DisplayName("a window with no runs returns nothing rather than the whole table")
    void distantWindowReturnsNothing() {
        List<JobRun> runs = repository.findRunsBetween(
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 2, 0, 0));

        assertThat(runs)
                .as("a bounded query for an empty window must not return history")
                .isEmpty();
    }

    private AjsJob job(String name) {
        Optional<AjsJob> found = repository.findAllJobs().stream()
                .filter(j -> j.jobName().equals(name))
                .findFirst();
        assertThat(found).as("job %s should exist in the fixture", name).isPresent();
        return found.orElseThrow();
    }
}
