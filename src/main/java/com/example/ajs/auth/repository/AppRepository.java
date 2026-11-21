package com.example.ajs.auth.repository;

import com.example.ajs.auth.domain.App;

import java.util.List;
import java.util.Optional;

/**
 * Application and per-user permission persistence, ported from the
 * {@code applications} and {@code user_applications} functions in
 * {@code my-dashboard/src/lib/db.ts}.
 *
 * <h2>The denylist inversion — read this before changing anything</h2>
 * {@code user_applications} is a <strong>denylist</strong>. A row means the user
 * <strong>cannot</strong> access that app. No rows means full access.
 *
 * <p>Consequently:
 * <ul>
 *   <li>{@link #blockedAppIds(long)} returns apps to <em>remove</em>, not to keep.</li>
 *   <li>A newly created app is automatically available to everyone, because
 *       nobody has a deny row for it yet. That is intended.</li>
 *   <li>{@link #accessibleApps(long)} is "all enabled apps MINUS the denylist".</li>
 * </ul>
 *
 * <p>Inverting this would silently revoke every user's access instead of
 * restricting one app. {@code PermissionDenylistTest} pins the behaviour.
 */
public interface AppRepository {

    List<App> findAllApps();

    /** Enabled apps only — what a normal user may be shown. */
    List<App> findEnabledApps();

    Optional<App> findAppById(long id);

    Optional<App> findAppBySlug(String slug);

    App createApp(String name, String slug, String icon, String url);

    boolean setAppEnabled(long id, boolean enabled);

    boolean deleteApp(long id);

    /** App ids the user is DENIED. Not the permitted set. */
    List<Long> blockedAppIds(long userId);

    /** Replaces the user's deny rows wholesale. */
    void setBlockedAppIds(long userId, List<Long> appIds);

    /** Enabled apps minus the user's deny rows. New apps are granted by default. */
    List<App> accessibleApps(long userId);
}
