package com.example.ajs.auth.repository;

import com.example.ajs.auth.domain.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * SQLite implementation of {@link UserRepository} over {@link JdbcTemplate}.
 *
 * <p>Every statement is parameterised. Email is normalised to lowercase on write
 * and on lookup so that {@code Admin@Example.com} and {@code admin@example.com}
 * cannot become two accounts — a real source of duplicate-account confusion, and
 * a way for a disabled account to be bypassed by signing in with different case.
 */
public class SqliteUserRepository implements UserRepository {

    private final JdbcTemplate jdbc;

    public SqliteUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Shared with the test fixture so both agree on the column list. */
    public static final String SELECT_COLUMNS =
            "id, email, password_hash, name, role, disabled, created_at";

    public static final RowMapper<User> ROW_MAPPER = (rs, rowNum) -> new User(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("name"),
            // Routed through Role.from so an unexpected stored value degrades to
            // least privilege instead of throwing or granting admin.
            User.Role.from(rs.getString("role")),
            rs.getInt("disabled") != 0,
            rs.getString("created_at"));

    /** Case- and whitespace-insensitive key used for storage and lookup. */
    public static String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        String normalised = normaliseEmail(email);
        if (normalised == null || normalised.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM users WHERE email = ?",
                ROW_MAPPER, normalised).stream().findFirst();
    }

    @Override
    public Optional<User> findById(long id) {
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM users WHERE id = ?",
                ROW_MAPPER, id).stream().findFirst();
    }

    @Override
    public List<User> findAll() {
        return jdbc.query("SELECT " + SELECT_COLUMNS
                + " FROM users ORDER BY created_at, id", ROW_MAPPER);
    }

    @Override
    public User create(String email, String passwordHash, String name, User.Role role) {
        String normalised = normaliseEmail(email);
        KeyHolder keys = new GeneratedKeyHolder();

        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO users (email, password_hash, name, role) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, normalised);
            ps.setString(2, passwordHash);
            ps.setString(3, name);
            ps.setString(4, (role == null ? User.Role.USER : role).dbValue());
            return ps;
        }, keys);

        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("SQLite returned no generated key for new user");
        }
        // Re-read rather than constructing from inputs, so defaults applied by
        // the database (created_at) are reflected.
        return findById(key.longValue())
                .orElseThrow(() -> new IllegalStateException("Newly created user could not be read back"));
    }

    @Override
    public boolean setDisabled(long id, boolean disabled) {
        return jdbc.update("UPDATE users SET disabled = ? WHERE id = ?",
                disabled ? 1 : 0, id) > 0;
    }

    @Override
    public boolean updateRole(long id, User.Role role) {
        return jdbc.update("UPDATE users SET role = ? WHERE id = ?", role.dbValue(), id) > 0;
    }

    @Override
    public boolean updatePasswordHash(long id, String passwordHash) {
        return jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?",
                passwordHash, id) > 0;
    }

    @Override
    public boolean delete(long id) {
        return jdbc.update("DELETE FROM users WHERE id = ?", id) > 0;
    }

    @Override
    public int countActiveAdmins() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role = 'admin' AND disabled = 0",
                Integer.class);
        return count == null ? 0 : count;
    }
}
