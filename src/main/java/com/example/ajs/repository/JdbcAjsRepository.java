package com.example.ajs.repository;

import com.example.ajs.domain.AjsDateDecoders;
import com.example.ajs.domain.AjsJob;
import com.example.ajs.domain.JobRun;
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
 */
public class JdbcAjsRepository implements AjsRepository {

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
    public List<AjsJob> findAllJobs() {
        String sql = "SELECT " + AjsJobRowMapper.SELECT_COLUMNS
                + " FROM " + MASTER + " m"
                + " ORDER BY m.JMAPPL, m.JMJOB";
        return jdbc.query(sql, new AjsJobRowMapper());
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
     */
    @Override
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
    public List<AjsJob> findByCompletionCode(String completionCode) {
        String sql = "SELECT " + AjsJobRowMapper.SELECT_COLUMNS
                + " FROM " + MASTER + " m"
                + " WHERE m.JMLCC = ?"
                + " ORDER BY m.JMLRD DESC, m.JMJOB";
        return jdbc.query(sql, new AjsJobRowMapper(), completionCode);
    }

    @Override
    public List<AjsJob> findNeverRun() {
        String sql = "SELECT " + AjsJobRowMapper.SELECT_COLUMNS
                + " FROM " + MASTER + " m"
                + " WHERE m.JMLRD = 0 OR m.JMLRD IS NULL"
                + " ORDER BY m.JMAPPL, m.JMJOB";
        return jdbc.query(sql, new AjsJobRowMapper());
    }

    @Override
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
