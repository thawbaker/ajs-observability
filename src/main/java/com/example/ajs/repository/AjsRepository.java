package com.example.ajs.repository;

import com.example.ajs.domain.AjsJob;
import com.example.ajs.domain.JobRun;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-only access to AJS data on an IBM i partition.
 *
 * <p>Why this interface exists (verified constraint): the jt400 driver accepts
 * only {@code jdbc:as400:} URLs. It will not talk to H2, SQLite, or any other
 * local database. Therefore, to make the application testable with no IBM i
 * present — the requirement in {@code req.txt} §6, "no dependence on live i" —
 * the SQL boundary has to be an interface with more than one implementation.
 *
 * <p>Two implementations are expected:
 * <ul>
 *   <li>{@link JdbcAjsRepository} — production, over {@code jdbc:as400:}.</li>
 *   <li>The test fixture seeding H2 in {@code MODE=DB2}, wired by the test
 *       configuration.</li>
 * </ul>
 *
 * <p>Every method is read-only by contract. Nothing here may write to the IBM i;
 * write actions (hold/release/submit) are explicitly out of scope for v1 per
 * {@code req.txt} §9 question 1, and would require a fundamentally different
 * authority model.
 */
public interface AjsRepository {

    /** All AJS job definitions, ordered by application group then job name. */
    List<AjsJob> findAllJobs();

    /**
     * Job definitions with a next-run date on the given date and status
     * {@code *READY} — the "due today" view.
     */
    List<AjsJob> findScheduledOn(LocalDate date);

    /**
     * Jobs whose last run completed abnormally ({@code JMLCC = 'A'}).
     *
     * <p>This is a raw predicate. Callers must apply
     * {@link AjsJob#genuineFailure()} before presenting these as failures, to
     * exclude {@code *NOSBM} jobs that were skipped rather than failed.
     */
    List<AjsJob> findByCompletionCode(String completionCode);

    /** Jobs that have never run ({@code JMLRD = 0}). */
    List<AjsJob> findNeverRun();

    /** Jobs currently held ({@code JMSTS = '*HELD'}). */
    List<AjsJob> findHeld();

    /**
     * Command list for a job, from {@code QAIJS1CM} joined on
     * {@code CMJOB = JMJOB}, ordered by command sequence.
     *
     * <p>Note the two different "sequence" concepts and why this takes only the
     * job name: {@code QAIJSMST.JMSEQ} is the job's sequence number, whereas
     * {@code QAIJS1CM.CMSEQ} is the position of each command within that job.
     * They are unrelated. An earlier version of this method filtered on
     * {@code CMSEQ = JMSEQ}, which returned one command instead of the whole
     * list. Do not reintroduce that filter.
     *
     * @param jobName the AJS job name ({@code JMJOB})
     * @return command texts in execution order
     */
    List<String> findCommandsForJob(String jobName);

    /**
     * Historical runs whose start falls within the given window, inclusive of
     * {@code from} and exclusive of {@code to}.
     *
     * <p>Feeds the run-timeline view. Ordering is by start time so the caller can
     * lay bars out in sequence and compute the gap between consecutive runs of a
     * job without re-sorting.
     *
     * <p>The window is deliberately {@code [from, to)} rather than inclusive of
     * both ends: adjacent windows that share a boundary then do not double-count
     * the run that starts exactly on it, which is what makes paging through days
     * on the timeline add up to the right total.
     *
     * @param from inclusive start of the window
     * @param to   exclusive end of the window
     */
    List<JobRun> findRunsBetween(LocalDateTime from, LocalDateTime to);

    /**
     * Number of job definitions visible. Used by the health/summary view and as
     * a cheap liveness probe against the partition.
     */
    int countJobs();
}
