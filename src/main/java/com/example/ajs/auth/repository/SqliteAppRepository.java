package com.example.ajs.auth.repository;

import com.example.ajs.auth.domain.App;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * SQLite implementation of {@link AppRepository}.
 *
 * <p>The denylist inversion is the thing to be careful about here. Read the
 * {@link AppRepository} javadoc before editing: a row in
 * {@code user_applications} means <em>denied</em>, and
 * {@link #accessibleApps(long)} is the enabled set minus the deny set.
 */
public class SqliteAppRepository implements AppRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public SqliteAppRepository(JdbcTemplate jdbc, TransactionTemplate transactionTemplate) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
    }

    public static final String SELECT_COLUMNS = "id, name, slug, icon, url, enabled";

    public static final RowMapper<App> ROW_MAPPER = (rs, rowNum) -> new App(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("slug"),
            rs.getString("icon"),
            rs.getString("url"),
            rs.getInt("enabled") != 0);

    @Override
    public List<App> findAllApps() {
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM applications ORDER BY name, id",
                ROW_MAPPER);
    }

    @Override
    public List<App> findEnabledApps() {
        return jdbc.query("SELECT " + SELECT_COLUMNS
                + " FROM applications WHERE enabled = 1 ORDER BY name, id", ROW_MAPPER);
    }

    @Override
    public Optional<App> findAppById(long id) {
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM applications WHERE id = ?",
                ROW_MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<App> findAppBySlug(String slug) {
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM applications WHERE slug = ?",
                ROW_MAPPER, slug).stream().findFirst();
    }

    @Override
    public App createApp(String name, String slug, String icon, String url) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO applications (name, slug, icon, url) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, slug);
            ps.setString(3, icon == null || icon.isBlank() ? "layout-grid" : icon);
            ps.setString(4, url == null || url.isBlank() ? "#" : url);
            return ps;
        }, keys);

        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("SQLite returned no generated key for new app");
        }
        return findAppById(key.longValue())
                .orElseThrow(() -> new IllegalStateException("Newly created app could not be read back"));
    }

    @Override
    public boolean setAppEnabled(long id, boolean enabled) {
        return jdbc.update("UPDATE applications SET enabled = ? WHERE id = ?",
                enabled ? 1 : 0, id) > 0;
    }

    @Override
    public boolean deleteApp(long id) {
        // user_applications rows cascade via the foreign key, which is enabled
        // per connection in SqliteDataSourceConfig. Without that pragma this
        // would leave deny rows pointing at a deleted app.
        return jdbc.update("DELETE FROM applications WHERE id = ?", id) > 0;
    }

    @Override
    public List<Long> blockedAppIds(long userId) {
        return jdbc.queryForList(
                "SELECT app_id FROM user_applications WHERE user_id = ? ORDER BY app_id",
                Long.class, userId);
    }

    @Override
    public void setBlockedAppIds(long userId, List<Long> appIds) {
        // Delete-then-insert must be atomic: a failure between the two would
        // leave the user with NO deny rows, i.e. full access. The transaction is
        // the difference between "restricted" and "unrestricted".
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM user_applications WHERE user_id = ?", userId);
            if (appIds != null) {
                for (Long appId : appIds) {
                    if (appId == null) {
                        continue;
                    }
                    jdbc.update(
                            "INSERT OR IGNORE INTO user_applications (user_id, app_id) VALUES (?, ?)",
                            userId, appId);
                }
            }
        });
    }

    @Override
    public List<App> accessibleApps(long userId) {
        Set<Long> denied = new HashSet<>(blockedAppIds(userId));
        // Enabled apps MINUS the deny set. Newly added apps have no deny row and
        // are therefore granted to everyone, which is the intended semantics.
        return findEnabledApps().stream()
                .filter(app -> !denied.contains(app.id()))
                .toList();
    }
}
