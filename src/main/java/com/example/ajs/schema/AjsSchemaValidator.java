package com.example.ajs.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Startup check that the live QUSRIJS files still look like what the row mapper
 * expects. This is the mitigation for risk R1 in {@code req.txt} §7: the AJS
 * physical-file layouts are not a published contract, and a release or PTF can
 * change them.
 *
 * <p>Design choice: this <em>reports</em> drift rather than refusing to start by
 * default. A monitoring tool that will not boot because one column moved is
 * worse than one that boots and tells you loudly which column moved. Set
 * {@code ajs.schema-validation.fail-on-missing-columns=true} to make it hard-fail
 * once the layouts have been confirmed against a real partition.
 */
public class AjsSchemaValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AjsSchemaValidator.class);

    /**
     * The columns the application actually reads. Kept as an explicit list —
     * not derived from the mapper — so that adding a column to the mapper forces
     * a conscious update here too.
     */
    public static final Set<String> REQUIRED_MASTER_COLUMNS = Set.of(
            "JMJOB", "JMSEQ", "JMGRP", "JMAPPL", "JMTEXT",
            "JMSCDNAM", "JMSCH", "JMCAL", "JMHCAL", "JMFCAL",
            "JMDTE", "JMTME",
            "JMJOBQ", "JMJBQL", "JMJOBD", "JMJBDL",
            "JMUSER", "JMMSGQ", "JMMSGL", "JMRCYA",
            "JMSTS", "JMLRD", "JMLST", "JMLCC", "JMELAP");

    public static final Set<String> REQUIRED_COMMAND_COLUMNS = Set.of("CMJOB", "CMSEQ", "CMCMD");

    private final JdbcTemplate jdbc;
    private final boolean failOnMissingColumns;

    public AjsSchemaValidator(JdbcTemplate jdbc, boolean failOnMissingColumns) {
        this.jdbc = jdbc;
        this.failOnMissingColumns = failOnMissingColumns;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> problems = new ArrayList<>();
        checkTable("QAIJSMST", REQUIRED_MASTER_COLUMNS, problems);
        checkTable("QAIJS1CM", REQUIRED_COMMAND_COLUMNS, problems);

        if (problems.isEmpty()) {
            log.info("AJS schema validation passed: QUSRIJS files expose all {} expected columns",
                    REQUIRED_MASTER_COLUMNS.size() + REQUIRED_COMMAND_COLUMNS.size());
            return;
        }

        String message = "AJS schema drift detected (" + problems.size() + " problem(s)): "
                + String.join("; ", problems)
                + " — see README.md risk R1. Layouts are not a published contract; "
                + "confirm with DSPFFD against the live partition.";
        if (failOnMissingColumns) {
            throw new IllegalStateException(message);
        }
        log.warn(message);
    }

    private void checkTable(String tableName, Set<String> required, List<String> problems) {
        Set<String> actual;
        try {
            actual = readColumns(tableName);
        } catch (SQLException ex) {
            // A missing or unreadable table is itself drift worth reporting, and
            // could equally be an authority problem (risk R3) — say both.
            problems.add(tableName + " unreadable (" + ex.getMessage()
                    + "); table missing or the service profile lacks *USE authority");
            return;
        }

        if (actual.isEmpty()) {
            problems.add(tableName + " not found in QUSRIJS (or not accessible)");
            return;
        }

        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(actual);
        if (!missing.isEmpty()) {
            problems.add(tableName + " missing columns " + missing);
        }
    }

    private Set<String> readColumns(String tableName) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (var connection = jdbc.getDataSource().getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            // Try both with and without the QUSRIJS qualifier: IBM i reports
            // schema names differently across JDBC property combinations.
            for (String schema : new String[]{"QUSRIJS", null}) {
                try (ResultSet rs = meta.getColumns(null, schema, tableName, null)) {
                    while (rs.next()) {
                        columns.add(rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
                    }
                }
                if (!columns.isEmpty()) {
                    break;
                }
            }
        }
        return columns;
    }
}
