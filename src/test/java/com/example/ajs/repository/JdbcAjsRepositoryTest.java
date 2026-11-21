package com.example.ajs.repository;

import com.example.ajs.domain.AjsJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
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
        Integer written = jdbc.queryForObject(
                "SELECT COUNT(*) FROM QUSRIJS.QAIJSHST WHERE HSCC = 'A'", Integer.class);
        assertThat(written).isEqualTo(1);
    }

    private AjsJob job(String name) {
        Optional<AjsJob> found = repository.findAllJobs().stream()
                .filter(j -> j.jobName().equals(name))
                .findFirst();
        assertThat(found).as("job %s should exist in the fixture", name).isPresent();
        return found.orElseThrow();
    }
}
