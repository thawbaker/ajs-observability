package com.example.ajs.repository;

import com.example.ajs.domain.AjsDateDecoders;
import com.example.ajs.domain.AjsJob;
import com.example.ajs.domain.JobRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Production implementation of {@link AjsRepository}: read-only SQL over the AJS
 * physical files in {@code QUSRIJS} via the jt400 JDBC driver
 * ({@code jdbc:as400://...}).
 *
 * <p>This is Option A from {@code req.txt} §3 and is the recommended primary
 * data source. It requires nothing to be installed on the IBM i and no change
 * to AJS configuration — it reads the same tables the AJS 5250 panels read.
 *
 * <p>The {@link JdbcTemplate} is supplied by Spring. In production it is backed
 * by a jt400 {@code DataSource}; in tests it is backed by H2 in {@code MODE=DB2}.
 * Because the SQL below is deliberately ANSI-shaped DB2, the same statements run
 * against both, which is what makes the offline test tier meaningful.
 *
 * <h2>Read-only guarantee</h2>
 * Every statement here is a SELECT. This is reinforced by the connection itself:
 * the IBM i service profile should be granted only read authority, and the
 * datasource enables {@code read only=true} where the driver honours it.
 *
 * <h2>Why the caching lives here</h2>
 * These methods are the only things that touch the partition, so caching at this
 * layer bounds IBM i load for every view at once rather than per controller. It
 * also removes a duplicate that predates the cache: the failures page calls
 * {@code findByCompletionCode("A")} twice (failures and skipped), which was two
 * identical scans of the master file per page view and is now one.
 *
 * <p>The cache is a time-based staleness cap, not a claim of freshness. On a
 * monitoring tool a stale number is worse than a slow one — an operator acting
 * on a "2 failures" reading that was true ninety seconds ago is being misled —
 * so the TTLs are deliberately short and are the control the operator actually
 * has (see {@code ajs.polling.*} in application.yml). This is the "cache
 * slow-changing inventory, only poll what the page needs" requirement from
 * {@code req.txt} §6, and the mitigation for risk R6.
 *
 * <p>What is deliberately <em>not</em> cached: {@link #findRunsBetween} on an
 * arbitrary window. It is already bounded by an index-usable range predicate, and
 * caching it would multiply memory by the number of distinct windows users
 * browse. And {@link #countJobs} is a single aggregate the database answers
 * cheaply.
 *
 * <h2>The job-count assumption is checked, not trusted</h2>
 * {@link #findAllJobs()} has no {@code WHERE} clause: it reads the whole master
 * file. That is a deliberate choice, recorded in {@code req.txt} §6 as "a few
 * thousand jobs", and it is what lets next-run dates be decoded in Java instead
 * of in SQL (CYYMMDD cannot be compared to a SQL date without SQL0181, risk R2).
 *
 * <p>An unqualified full read is only acceptable while that scale holds. So the
 * count is compared against {@link #ASSUMED_SCALE_JOBS} on every read and a
 * warning is logged once if it is exceeded. The point is that the assumption
 * fails loudly instead of quietly turning one page view into a large transfer.
 * If that warning ever appears, the fix is to project only the columns the list
 * views need and/or push the next-run-date filter into SQL — not to raise the
 * constant.
 */
public class JdbcAjsRepository implements AjsRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcAjsRepository.class);

    /**
     * The job count above which the unqualified master read is re-examined.
     *
     * <p>Set to the upper end of {@code req.txt}'s "a few thousand". Exceeding it
     * is not an error — the query still works — but it invalidates the reasoning
     * behind reading the whole file, so it is worth one warning in the log.
     */
    static final int ASSUMED_SCALE_JOBS = 5_000;

    /** Guards the scale warning so a busy process does not log it on every refresh. */
    private volatile boolean scaleWarningLogged = false;

    /** Library-qualified master file. Qualification avoids reliance on job libraries. */
    static final String MASTER = "QUSRIJS.QAIJSMST";

    /** Command list file — one row per command in a job's sequence. */
    static final String COMMAND_LIST = "QUSRIJS.QAIJS1CM";

    /** AJS history / run records. */
    static final String HISTORY = "QUSRIJS.QAIJSHST";

    private final JdbcTemplate jdbc;

    public JdbcAjsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Cacheable("ajsJobs")
    public List<AjsJob> findAllJobs() {
        String sql = "SELECT " + AjsJobRowMapper.SELECT_COLUMNS
                + " FROM " + MASTER + " m"
                + " ORDER BY m.JMAPPL, m.JMJOB";
        List<AjsJob> jobs = jdbc.query(sql, new AjsJobRowMapper());
        warnIfBeyondAssumedScale(jobs.size());
        return jobs;
    }

    /**
     * One-shot warning when the master file outgrows the design assumption.
     *
     * <p>Logged inside the cached method on purpose: it fires once per cache fill
     * rather than once per request, and the flag prevents even that from repeating
     * if the TTL lapses repeatedly. Deliberately a warning and not an exception —
     * a monitoring tool that refuses to render because the scheduler has many jobs
     * is worse than one that renders a little slower and says why.
     */
    private void warnIfBeyondAssumedScale(int jobCount) {
        if (jobCount <= ASSUMED_SCALE_JOBS || scaleWarningLogged) {
            return;
        }
        scaleWarningLogged = true;
        log.warn("QAIJSMST holds {} job definitions, above the {} assumed by the "
                        + "unqualified master read. Page cost is now proportional to that "
                        + "count; see JdbcAjsRepository's note on the job-count assumption "
                        + "before raising this constant.",
                jobCount, ASSUMED_SCALE_JOBS);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The next-run date is stored as CYYMMDD, which cannot be compared to a
     * SQL date directly without provoking SQL0181. So instead of casting in SQL,
     * the next-run date is filtered in Java after decoding. For the few-thousand
     * job scale stated in {@code req.txt} §6 this is comfortably fast, and it
     * keeps the fragile format handling in one tested place rather than spread
     * across SQL that cannot be unit-tested without a live host.
     *
     * <p><b>Caching caveat, found by measuring.</b> A {@code @Cacheable} on this
     * method would not be enough on its own, and neither is relying on
     * {@link #findAllJobs()}'s cache. Self-invocation does not go through the
     * Spring proxy, so the call above to {@code findAllJobs()} executes the method
     * body directly and skips its cache entirely. Measured with statements logged:
     * five calendar views for five different dates produced <b>five</b> master-file
     * reads, not one.
     *
     * <p>So this method is annotated {@code @Cacheable} itself, keyed by date. Calls
     * arriving through the proxy (i.e. from the service layer, which is the only
     * caller) are cached; the internal call it makes is not, but it no longer
     * matters because the whole result is cached per date.
     *
     * <p>Measured after the fix: six repeat views of one date produced <b>zero</b>
     * master-file reads. Five views of five <em>different</em> dates still produce
     * five reads, which is correct — each date is a distinct cached result. So the
     * guarantee is one read per distinct date per TTL window, not one read total.
     * The TTL is the live rate, because a job that ran changes its next-run date.
     */
    @Override
    @Cacheable(value = "ajsScheduled", key = "#date")
    public List<AjsJob> findScheduledOn(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("date must not be null");
        }
        return findAllJobs().stream()
                .filter(AjsJob::ready)
                .filter(job -> job.nextRunDate().filter(date::equals).isPresent())
                .toList();
    }

    @Override
    @Cacheable("ajsCompletion")
    public List<AjsJob> findByCompletionCode(String completionCode) {
        String sql = "SELECT " + AjsJobRowMapper.SELECT_COLUMNS
                + " FROM " + MASTER + " m"
                + " WHERE m.JMLCC = ?"
                + " ORDER BY m.JMLRD DESC, m.JMJOB";
        return jdbc.query(sql, new AjsJobRowMapper(), completionCode);
    }

    @Override
    @Cacheable("ajsNeverRun")
    public List<AjsJob> findNeverRun() {
        String sql = "SELECT " + AjsJobRowMapper.SELECT_COLUMNS
                + " FROM " + MASTER + " m"
                + " WHERE m.JMLRD = 0 OR m.JMLRD IS NULL"
                + " ORDER BY m.JMAPPL, m.JMJOB";
        return jdbc.query(sql, new AjsJobRowMapper());
    }

    @Override
    @Cacheable("ajsHeld")
    public List<AjsJob> findHeld() {
        String sql = "SELECT " + AjsJobRowMapper.SELECT_COLUMNS
                + " FROM " + MASTER + " m"
                + " WHERE m.JMSTS = ?"
                + " ORDER BY m.JMAPPL, m.JMJOB";
        return jdbc.query(sql, new AjsJobRowMapper(), AjsJob.STATUS_HELD);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Ordered by {@code CMSEQ}, the command's position within the job. See the
     * interface javadoc for why this is not the same as {@code JMSEQ}.
     *
     * <p>{@code CMCMD} is a fixed-length character column on IBM i, so the driver
     * returns it blank-padded to its full width. The padding is stripped here
     * rather than in the template: leaving it in leaks into CSV exports and makes
     * command comparison and display wrong.
     */
    @Override
    @Cacheable("ajsCommands")
    public List<String> findCommandsForJob(String jobName) {
        String sql = "SELECT c.CMCMD FROM " + COMMAND_LIST + " c"
                + " WHERE c.CMJOB = ?"
                + " ORDER BY c.CMSEQ";
        return jdbc.query(sql, (rs, rowNum) -> {
            String cmd = rs.getString(1);
            return cmd == null ? "" : cmd.strip();
        }, jobName);
    }

    /**
     * {@inheritDoc}
     *
     * <h2>Why the date range is pushed into SQL</h2>
     * The previous version of this method issued {@code SELECT ... FROM QAIJSHST}
     * with no {@code WHERE} clause: it read the <em>entire</em> run history, moved
     * it over DRDA, mapped every row in Java, and only then discarded all but the
     * requested window. On a real partition that file grows without bound, so a
     * single timeline page view cost more every month the system ran. This was
     * design review finding C2.
     *
     * <p>It is tempting to conclude that the range cannot be pushed down at all,
     * because {@code HSDTE} is a packed legacy numeric that raises SQL0181/CPD4019
     * when compared against a SQL timestamp (risk R2). That conclusion is wrong in
     * an important way. What cannot be done is <em>date arithmetic</em> in SQL.
     * What can be done is a range comparison on the <em>encoded integers</em>,
     * because CYYMMDD is numerically ordered within a century
     * ({@code 1251120 < 1251121}). The window edges are therefore encoded in Java
     * by {@link AjsDateDecoders#encodeCyymmdd(LocalDate)}, the century logic stays
     * in its single containment boundary, and the database gets a selective,
     * index-usable predicate.
     *
     * <p>The comparison is deliberately inclusive of the whole {@code from} and
     * {@code to} days. The caller's contract is a half-open
     * {@code [from, to)} on timestamps, so encoding only {@code to.toLocalDate()}
     * would drop runs later the same day; the {@code <=} on the encoded end date
     * keeps them in scope for the exact Java filter below. Widening the SQL range
     * and narrowing in Java is the safe direction — it can only over-fetch a single
     * day, never silently lose a run.
     *
     * <p>The {@code HSDTE IS NOT NULL} guard is belt-and-braces for the same reason
     * as the {@code BETWEEN}: a null date fails a range comparison in SQL anyway,
     * but stating it means the predicate reads the same way the Java filter below
     * behaves.
     *
     * <p>Rows whose date is unplottable (null or sentinel) are dropped here rather
     * than mapped to a default time: a run with no position on a time axis cannot
     * be drawn, and inventing one would put a bar on the timeline that never
     * happened. {@link JobRunRowMapper} maps such rows to a null start; this method
     * is what removes them.
     */
    @Override
    public List<JobRun> findRunsBetween(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to must not be null");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException(
                    "from must be strictly before to: " + from + " .. " + to);
        }

        // Encode the window edges as CYYMMDD integers so the filter can be a range
        // predicate. Inclusive on both ends: the caller's bound is half-open on
        // timestamps, and the exact filter below is what makes it precise.
        Integer fromDate = AjsDateDecoders.encodeCyymmdd(from.toLocalDate());
        Integer toDate = AjsDateDecoders.encodeCyymmdd(to.toLocalDate());

        String sql = "SELECT " + JobRunRowMapper.SELECT_COLUMNS
                + " FROM " + HISTORY + " h"
                + " WHERE h.HSDTE IS NOT NULL"
                + " AND h.HSDTE BETWEEN ? AND ?"
                + " ORDER BY h.HSDTE, h.HSTME";

        // The ORDER BY in SQL is not merely cosmetic: it lets the database use an
        // index for the ordering instead of the driver materialising every row for
        // a Java-side sort. The Java sort is retained below because it is the
        // authority on the final order once decoding has happened — SQL orders by
        // the packed value, which is the same order within a century but is not
        // this method's contract.
        return jdbc.query(sql, new JobRunRowMapper(), fromDate, toDate).stream()
                .filter(run -> run.startedAt() != null)
                .filter(run -> !run.startedAt().isBefore(from))
                .filter(run -> run.startedAt().isBefore(to))
                .sorted(Comparator.comparing(JobRun::startedAt))
                .toList();
    }

    @Override
    public int countJobs() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + MASTER, Integer.class);
        return count == null ? 0 : count;
    }
}
