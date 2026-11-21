package com.example.ajs.repository;

import com.example.ajs.domain.AjsJob;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
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

    @Override
    public int countJobs() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + MASTER, Integer.class);
        return count == null ? 0 : count;
    }
}
