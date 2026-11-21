package com.example.ajs.repository;

import com.example.ajs.domain.AjsDateDecoders;
import com.example.ajs.domain.AjsJob;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps a {@code QAIJSMST} row (optionally joined to {@code QAIJS1CM}) to an
 * {@link AjsJob}.
 *
 * <p>All legacy-date decoding happens here so the rest of the application deals
 * only in {@code java.time} types. This is the single place that changes when a
 * release alters an encoding — risk R2's containment boundary.
 *
 * <p>Column reads use {@code getObject(..., Integer.class)} for the packed
 * numeric date fields. This matters: a direct {@code getInt} against a value
 * AJS stored as a null-capable packed decimal raises SQL0181/CPD4019, the exact
 * failure IBM documents for QAIJSHST. Going through {@code Integer} lets a null
 * arrive as null and be handled by the decoder instead of blowing up the query.
 */
public class AjsJobRowMapper implements RowMapper<AjsJob> {

    /** Column list shared by every SELECT so mappings stay in lockstep. */
    public static final String SELECT_COLUMNS = """
            m.JMJOB, m.JMSEQ, m.JMGRP, m.JMAPPL, m.JMTEXT,
            m.JMSCDNAM, m.JMSCH, m.JMCAL, m.JMHCAL, m.JMFCAL,
            m.JMDTE, m.JMTME,
            m.JMJOBQ, m.JMJBQL, m.JMJOBD, m.JMJBDL,
            m.JMUSER, m.JMMSGQ, m.JMMSGL, m.JMRCYA,
            m.JMSTS,
            m.JMLRD, m.JMLST, m.JMLCC, m.JMELAP
            """;

    @Override
    public AjsJob mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AjsJob(
                trim(rs.getString("JMJOB")),
                rs.getInt("JMSEQ"),
                trim(rs.getString("JMGRP")),
                trim(rs.getString("JMAPPL")),
                trim(rs.getString("JMTEXT")),

                trim(rs.getString("JMSCDNAM")),
                trim(rs.getString("JMSCH")),
                trim(rs.getString("JMCAL")),
                trim(rs.getString("JMHCAL")),
                trim(rs.getString("JMFCAL")),

                AjsDateDecoders.decodeCyymmdd(rs.getObject("JMDTE", Integer.class)),
                AjsDateDecoders.decodeHhmmss(rs.getObject("JMTME", Integer.class)),

                trim(rs.getString("JMJOBQ")),
                trim(rs.getString("JMJBQL")),
                trim(rs.getString("JMJOBD")),
                trim(rs.getString("JMJBDL")),
                trim(rs.getString("JMUSER")),
                trim(rs.getString("JMMSGQ")),
                trim(rs.getString("JMMSGL")),
                trim(rs.getString("JMRCYA")),

                trim(rs.getString("JMSTS")),

                AjsDateDecoders.decodeYymmdd(rs.getObject("JMLRD", Integer.class)),
                trim(rs.getString("JMLST")),
                trim(rs.getString("JMLCC")),
                rs.getObject("JMELAP", Integer.class)
        );
    }

    /**
     * IBM i fixed-format character columns are blank-padded. Trimming at the
     * boundary keeps template output and equality checks sane.
     */
    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
