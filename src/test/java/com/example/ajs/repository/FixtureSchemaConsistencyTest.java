package com.example.ajs.repository;

import com.example.ajs.schema.AjsSchemaValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that the offline fixture and the row mappers agree about columns.
 *
 * <p>This test exists because of a specific risk in the offline tier. The whole
 * application's evidence base rests on one authored file,
 * {@code fixture/schema-ajs.sql}, and the mappers read columns by name. If a
 * mapper gains a column that the fixture lacks, the failure is a runtime
 * {@code SQLException} on the {@code local} profile and a red integration test —
 * loud, but only if something happens to execute that query. If the fixture gains
 * a column the mapper does not read, nothing fails at all, and the fixture quietly
 * stops representing what the code needs.
 *
 * <p>The column lists are read from the same constants the mappers use
 * ({@link AjsJobRowMapper#SELECT_COLUMNS}, {@link JobRunRowMapper#SELECT_COLUMNS})
 * rather than duplicated here. That is deliberate: a hand-copied list is exactly
 * the kind of duplicate that drifts. What this test catches is the case where a
 * mapper's constant names a column the fixture does not define — the failure that
 * would otherwise only surface on a host.
 *
 * <p>It does <em>not</em> prove the real {@code QAIJSMST} has these columns. That
 * is risk R1, and only {@code DSPFFD} against a real partition can retire it. See
 * NOTES.md §12b.
 */
@SpringBootTest
@ActiveProfiles("test")
class FixtureSchemaConsistencyTest {

    @Autowired
    @Qualifier("ajsJdbcTemplate")
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("every column the master-file mapper selects exists in the fixture")
    void masterMapperColumnsExistInFixture() throws SQLException {
        Set<String> selected = columnNamesFrom(AjsJobRowMapper.SELECT_COLUMNS);
        Set<String> actual = columnsOf("QAIJSMST");

        assertThat(actual)
                .as("QAIJSMST columns in fixture/schema-ajs.sql")
                .containsAll(selected);
    }

    @Test
    @DisplayName("every column the history mapper selects exists in the fixture")
    void historyMapperColumnsExistInFixture() throws SQLException {
        Set<String> selected = columnNamesFrom(JobRunRowMapper.SELECT_COLUMNS);
        Set<String> actual = columnsOf("QAIJSHST");

        assertThat(actual)
                .as("QAIJSHST columns in fixture/schema-ajs.sql")
                .containsAll(selected);
    }

    @Test
    @DisplayName("the schema validator's required columns all exist in the fixture")
    void validatorRequirementsExistInFixture() throws SQLException {
        // The validator runs against the real host to detect drift. Pointed at the
        // fixture it must find nothing missing, or the fixture and the validator
        // disagree about what the application needs — and the validator's output
        // on a real host would be untrustworthy in a way nobody would notice.
        assertThat(columnsOf("QAIJSMST"))
                .as("QAIJSMST columns the validator requires")
                .containsAll(AjsSchemaValidator.REQUIRED_MASTER_COLUMNS);
        assertThat(columnsOf("QAIJS1CM"))
                .as("QAIJS1CM columns the validator requires")
                .containsAll(AjsSchemaValidator.REQUIRED_COMMAND_COLUMNS);
    }

    /**
     * The command-list join is {@code CMJOB = JMJOB}, and the fixture's history key
     * is an invented guess (NOTES.md §12, R1). Both are asserted here so that a
     * later "tidy-up" of the fixture cannot remove the columns those two decisions
     * depend on without a test failing.
     */
    @Test
    @DisplayName("the fixture keeps the columns the join and the history key depend on")
    void joinAndKeyColumnsExist() throws SQLException {
        assertThat(columnsOf("QAIJS1CM")).contains("CMJOB", "CMSEQ", "CMCMD");
        assertThat(columnsOf("QAIJSHST")).contains("HSJOB", "HSSEQ", "HSDTE", "HSTME");
    }

    /** Splits a mapper's column constant into bare upper-case column names. */
    private static Set<String> columnNamesFrom(String selectColumns) {
        Set<String> names = new LinkedHashSet<>();
        for (String token : selectColumns.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // Strip a table alias ("m.JMJOB" -> "JMJOB") and any AS alias.
            int dot = trimmed.lastIndexOf('.');
            String name = dot >= 0 ? trimmed.substring(dot + 1) : trimmed;
            names.add(name.trim().toUpperCase(Locale.ROOT));
        }
        return names;
    }

    /** Column names of a QUSRIJS table as the fixture actually created it. */
    private Set<String> columnsOf(String table) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (var connection = jdbc.getDataSource().getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet rs = meta.getColumns(null, "QUSRIJS", table, null)) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
                }
            }
        }
        return columns;
    }
}
