package com.example.ajs.repository;

import com.example.ajs.domain.AjsDateDecoders;
import com.example.ajs.domain.JobRun;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Maps a {@code QAIJSHST} row to a {@link JobRun}.
 *
 * <p>Like {@link AjsJobRowMapper}, this decodes the legacy date and time at the
 * boundary so nothing downstream handles CYYMMDD arithmetic.
 *
 * <p>{@code HSDTE} and {@code HSTME} are read with {@code getObject(..., Integer.class)}
 * rather than {@code getInt}, for the reason documented on {@link AjsJobRowMapper}:
 * a null-capable packed decimal raises SQL0181/CPD4019 on a direct {@code getInt},
 * which is the specific failure IBM documents for this file.
 *
 * <p>A row whose date is missing or a sentinel is not a plottable run — it has no
 * position on a time axis — so it is mapped to a {@code null} start and filtered
 * by the repository rather than being silently placed at some default time.
 */
public class JobRunRowMapper implements RowMapper<JobRun> {

    /** Column list shared by every SELECT of history rows. */
    public static final String SELECT_COLUMNS = """
            h.HSJOB, h.HSSEQ, h.HSDTE, h.HSTME, h.HSCC, h.HSELAP
            """;

    @Override
    public JobRun mapRow(ResultSet rs, int rowNum) throws SQLException {
        String job = rs.getString("HSJOB");
        Integer rawDate = rs.getObject("HSDTE", Integer.class);
        Integer rawTime = rs.getObject("HSTME", Integer.class);

        LocalDate date = AjsDateDecoders.decodeCyymmdd(rawDate).orElse(null);
        int time = rawTime == null ? 0 : rawTime;

        LocalDateTime startedAt = null;
        if (date != null) {
            // A missing or unparseable time is treated as midnight rather than
            // discarding the run: the date alone still places the bar usefully
            // on a day-scale axis, and dropping the row would hide a real
            // execution from a monitoring view.
            startedAt = AjsDateDecoders.decodeHhmmss(time)
                    .map(date::atTime)
                    .orElseGet(() -> date.atStartOfDay());
        }

        String cc = rs.getString("HSCC");
        Integer elapsed = rs.getObject("HSELAP", Integer.class);

        return new JobRun(
                job == null ? "" : job.strip(),
                rs.getInt("HSSEQ"),
                startedAt,
                cc == null ? null : cc.strip(),
                elapsed);
    }
}
