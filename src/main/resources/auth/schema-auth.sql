-- ============================================================================
-- Authentication and administration schema -- SQLite.
--
-- Ported from ../my-dashboard/db/schema.sql. The table shapes, the role CHECK
-- constraint and the DENYLIST semantics are deliberately kept identical so the
-- two applications stay conceptually aligned and can share a mental model.
--
-- Differences from the Next.js version, and why:
--   * created_at uses CURRENT_TIMESTAMP rather than datetime('now') -- identical
--     output, but portable and it is what JDBC callers expect.
--   * No PRAGMA/foreign_keys here; foreign key enforcement is enabled per
--     connection in SqliteDataSourceConfig (SQLite defaults it OFF, which would
--     silently let orphaned permission rows accumulate).
--
-- This file is idempotent and safe to run on every startup.
-- ============================================================================

CREATE TABLE IF NOT EXISTS users (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  email         TEXT    NOT NULL UNIQUE,
  password_hash TEXT    NOT NULL,
  name          TEXT    NOT NULL,
  role          TEXT    NOT NULL DEFAULT 'user' CHECK (role IN ('user','admin')),
  disabled      INTEGER NOT NULL DEFAULT 0,
  created_at    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Declared here rather than relying on the UNIQUE constraint, because SQLite
-- creates an implicit index for UNIQUE but its name is not controllable, and
-- email lookup happens on every sign-in.
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email ON users(email);

CREATE TABLE IF NOT EXISTS applications (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  name       TEXT    NOT NULL,
  slug       TEXT    NOT NULL UNIQUE,
  icon       TEXT    NOT NULL DEFAULT 'layout-grid',
  url        TEXT    NOT NULL DEFAULT '#',
  enabled    INTEGER NOT NULL DEFAULT 1,
  created_at TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- DENYLIST, not an allowlist. A row here means the user CANNOT access the app.
-- No rows means full access, and newly created apps are granted automatically.
--
-- This inversion is easy to get backwards, and getting it backwards turns
-- "restrict one app" into "revoke everything". It is asserted in
-- PermissionDenylistTest so the behaviour cannot silently flip.
CREATE TABLE IF NOT EXISTS user_applications (
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  app_id  INTEGER NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
  PRIMARY KEY (user_id, app_id)
);

CREATE INDEX IF NOT EXISTS idx_user_apps_user ON user_applications(user_id);
