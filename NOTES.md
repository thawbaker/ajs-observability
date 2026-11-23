# AJS Observability — design, decisions and handoff

A read-only web dashboard for **IBM Advanced Job Scheduler (AJS)** on IBM i.

It answers the questions a 5250 session makes tedious: what is due today, what
failed, what is held or never ran, and — via the timeline — when jobs actually
ran and how long each took.

This is the only prose document in the repository. It is written as a single
narrative on purpose: the project's history is a sequence of decisions, and the
order in which they were made is usually the fastest way to understand why the
code looks the way it does. It also carries the illustrated walkthrough — the
seventeen screenshots in §4a are the evidence behind the claims made here, so
there is no separate illustrated README to keep in step. `req.txt` holds the
original requirements, including the questions that are still open.

> **This application never writes to the IBM i.** Every query is a `SELECT`, and
> the service profile it is designed to use has no `*JOBCTL` and no AJS function
> authority. Hold, release, submit and job-definition changes are deliberately
> out of scope. Why that is a design decision rather than a limitation is argued
> in [Can it define, trigger and schedule jobs?](#can-it-define-trigger-and-schedule-jobs).

---

## 1. What it does

| View | Path | Answers |
| --- | --- | --- |
| **Overview** | `/overview` | Counts: ready, held, due today, genuine failures, never run |
| **Due** | `/calendar` | Jobs scheduled on a given date |
| **Timeline** | `/timeline` | Every run in a date/time window, as a bar chart |
| **Failures** | `/failures` | Abnormal completions, with `*NOSBM` skips shown separately |
| **Inventory** | `/jobs` | All job definitions, grouped by application group |
| **Job detail** | `/jobs/{name}` | One job's definition plus its command list |
| **Needs attention** | `/attention` | Held and never-run jobs |
| **Administration** | `/admin/**` | Users, apps, per-app view permissions (ADMIN only) |

What it does *not* yet do, and what would be worth adding, is §12c: job logs for a
failed run, run history on the job page, and the rest, ranked by value and by how
much each one depends on a host nobody has reached yet.

Data comes from the AJS physical files — `QAIJSMST` (definitions), `QAIJSHST`
(run history), `QAIJS1CM` (command lists) — read over the jt400 JDBC driver.
Nothing is installed on the IBM i; these are the same tables the 5250 panels
read. No REST/JSON API is assumed from AJS, and no SPA is involved.

---

## 2. Running it

Requires **Java 21**. `build.gradle` pins a Java 21 toolchain while this machine
defaults to JDK 27; the `foojay-resolver-convention` settings plugin provisions
21 automatically, so a fresh clone builds with no manual step.

There are exactly two ways to run the application, and they are chosen by the
Spring profile. The default profile talks to a real IBM i; the `local` profile
loads the fabricated fixture and needs no host at all. Nothing else switches it.

| | `local` — no IBM i | default — real IBM i |
| --- | --- | --- |
| AJS data | H2 in-memory, fabricated fixture | `QUSRIJS` over jt400 |
| Command | `./gradlew bootRun --args='--spring.profiles.active=local'` | `./gradlew bootRun` |
| Auth store | `./data/auth-local.db` | `./data/auth.db` |
| Administrator | `admin@localhost` / `localdev1234` | `AUTH_BOOTSTRAP_ADMIN_EMAIL` / random or set password |
| `JWT_SECRET` | fixed dev value in `application-local.yml` | **required**, or startup fails |
| Schema validation | off (the fixture matches by construction) | on, warns on drift |
| H2 on the classpath | yes (`localRuntimeOnly`) | no — verified absent from the jar |
| Production jar | **must not be used** — no H2, so it cannot start | the only supported mode |
| Schema drift on first look | — | default warns and starts; the `ibmi` profile **refuses to start** |

### No IBM i: the `local` profile

```bash
./gradlew test                                    # 173 tests, offline
./gradlew bootRun --args='--spring.profiles.active=local'
#   bootstrap admin: admin@localhost / localdev1234
#   browse http://localhost:8080
```

That single command is the whole setup: `bootRun` puts H2 on the classpath, sets
`AJS_ALLOW_FIXTURE_PROFILES=true`, and the profile seeds the fixture. Run it
*outside* Gradle and the guard refuses to start — which is the point, because a
deployed environment must never show invented scheduler state.

Two things are easy to get wrong here:

- **The auth database persists.** `./data/auth-local.db` survives restarts, so
  deleting it is how you get a clean bootstrap. It is gitignored, and it is a
  different file from production's `./data/auth.db`.
- **Nothing is printed to the console on the local profile.** The password is
  fixed at `localdev1234` in `application-local.yml`; the "generated and logged
  once" behaviour is the production path only.

### Real IBM i: the default profile

```bash
export IBMI_JDBC_URL='jdbc:as400://ibmi-host/QUSRIJS;naming=system;libraries=QUSRIJS;translate binary=true;prompt=false;errors=full;read only=true'
export IBMI_USER='AJSREAD'
export IBMI_PASSWORD='...'
export JWT_SECRET=$(openssl rand -base64 48)        # or startup fails
export AUTH_BOOTSTRAP_ADMIN_PASSWORD='...'          # or read it from the WARN log once
export AUTH_DATABASE_PATH=/var/lib/ajs/auth.db      # or ./data/auth.db

./gradlew bootRun            # development
./gradlew build && java -jar build/libs/*.jar   # or the jar, in deployment
```

**The first connection to a partition should use the `ibmi` profile**, not the
default one:

```bash
export JWT_SECRET=$(openssl rand -base64 48)   # the gate still needs one
./gradlew bootRun --args='--spring.profiles.active=ibmi'
```

`ibmi` sets `ajs.schema-validation.fail-on-missing-columns=true`, so a schema
mismatch is a failed start with a precise list of missing columns rather than a
dashboard that renders empty. The default profile warns and starts — right for a
host you have already inspected, wrong for the first look. Verified both ways:
with no host reachable, `ibmi` exits with `AJS schema drift detected ...
QAIJSMST unreadable (The application requester cannot establish the connection.
(ibmi-host))`.

Every value above has a default in `application.yml`; only `JWT_SECRET` has no
usable one. The JDBC URL's `read only=true` is a request to the driver, not a
guarantee — the read-only posture is actually enforced by the service profile's
authority, which is why the account, not the URL, is the control that matters.

**If the host is unreachable**, the application still starts: authentication,
sign-in and `/admin/**` are served from SQLite, and only the dashboards fail.
That is deliberate (§3), and it means "the dashboard is broken" and "the whole
app is down" are different diagnoses.

**First start against a real partition** is the moment to turn on the drift
check's teeth: `ajs.schema-validation.enabled=true` is already the default, and
`fail-on-missing-columns` should be flipped to `true` once `DSPFFD` has confirmed
the layouts (§12).

### Switches worth knowing

| Setting | Default | When to change it |
| --- | --- | --- |
| `spring.profiles.active` | none (production) | `local` for offline work |
| `AJS_ALLOW_FIXTURE_PROFILES` | unset | set only by Gradle, never in a deployment |
| `IBMI_JDBC_URL` | `jdbc:as400://ibmi-host/...` | always, in a real deployment |
| `IBMI_USER` / `IBMI_PASSWORD` | `CHANGEME` | always |
| `JWT_SECRET` | empty | always — startup fails without it |
| `AUTH_DATABASE_PATH` | `./data/auth.db` | when the working directory is not writable |
| `AUTH_BOOTSTRAP_ADMIN_PASSWORD` | empty → random, logged once | when automating a first deploy |
| `ajs.schema-validation.fail-on-missing-columns` | `false` | `true` once R1 is retired |

On a fresh auth database `AuthBootstrap` registers the six dashboards as
applications — overview, calendar, timeline, failures, jobs, attention — and
creates one administrator. If `AUTH_BOOTSTRAP_ADMIN_PASSWORD` is
unset it generates a random password and logs it **once** at WARN — deliberately,
because a fixed default password is how these accounts reach production unchanged.

---

## 3. Architecture

Two **entirely separate** datasources. Conflating them is the easiest mistake to
make here, and it is the reason the application still works when the IBM i does
not.

```
        AJS data (READ-ONLY)                 Accounts (read-write)
   ┌──────────────────────────────┐    ┌──────────────────────────────┐
   │ jdbc:as400://  →  QUSRIJS    │    │ SQLite file                  │
   │ H2 MODE=DB2 in tests         │    │ H2 is NOT used here          │
   │ AjsRepository (interface)    │    │ UserRepository/AppRepository │
   └──────────────────────────────┘    └──────────────────────────────┘
```

| Store | Purpose | Failure mode |
|-------|---------|--------------|
| **SQLite** (`data/auth.db`) | users, roles, apps, permissions | local, always available |
| **jt400 → IBM i** | AJS job data | may be unreachable |

Authentication is **not** bound to the IBM i connection. If it were, an iSeries
outage would lock operators out of the tool they use to diagnose that outage.
The IBM i datasource is `@Primary` so Actuator health reports IBM i connectivity
rather than quietly reporting SQLite.

| Layer | Location |
| --- | --- |
| Domain + legacy date decoding | `src/main/java/com/example/ajs/domain/` |
| SQL (read-only) | `.../repository/` |
| View logic (tested, not in templates) | `.../service/` |
| Controllers | `.../web/` |
| Accounts, JWT, permissions | `.../auth/` |
| Thymeleaf templates | `src/main/resources/templates/` |

### Why `AjsRepository` is an interface

This is the single most consequential structural decision in the codebase, and it
was forced by a verified constraint rather than chosen for tidiness.

**The jt400 driver accepts only `jdbc:as400:` URLs.** Measured, not assumed:

```
Driver loaded: AS/400 Toolbox for Java JDBC Driver
acceptsURL jdbc:as400://host;naming=system = true
```

So you cannot point the application at H2 and call it tested. Satisfying
`req.txt` §6 — "no dependence on live i" — therefore requires a seam at the SQL
boundary with a second implementation. That abstraction **is** the offline test
strategy. Do not simplify it away.

### Verified facts about this machine

| Item | Status |
|------|--------|
| Java | 27 (Temurin) — compile target pinned to **21 LTS** |
| Gradle | 9.8.1 |
| Maven | not installed — use Gradle |
| Docker | installed, **unusable without password** (`zeke` not in `docker` group) |
| jt400 | 21.0.7 on Maven Central; driver registers standalone |
| H2 | 2.3.232 (DB2 compatibility mode) |

---

## 4. Testing

Four tiers, all offline. `./gradlew test` runs the lot.

1. **Unit** — legacy date decoding, view logic, timeline geometry. Pure logic,
   highest value per line, and where risk R2 is contained.
2. **Repository** — real SQL against H2 in `MODE=DB2`, standing in for `QUSRIJS`.
3. **HTTP / template** — MockMvc rendering the real Thymeleaf templates.
4. **Security** — authentication, role gating, permission enforcement.

An optional fifth tier (real Db2 in Docker) is deliberately not wired up: Db2
**LUW is not DB2 for i**, and LUW does not exercise the jt400 DRDA path at all,
so it would add setup cost while validating almost nothing.

**173 tests, 0 failures**, re-run with `--rerun-tasks` so nothing was cached.

| Suite | Tests | Tier | Covers |
|-------|-------|------|--------|
| `AjsDateDecodersTest` | 39 | 1 | CYYMMDD decode **and encode**, YYMMDD, HHMMSS, sentinels, display |
| `AuthFlowTest` | 19 | 4 | Real sign-in/up/out, role gating, disabled, tamper |
| `JdbcAjsRepositoryTest` | 19 | 2 | SQL + mapping vs H2 `MODE=DB2`, run window, **bounded query (C2)** |
| `DashboardControllerTest` | 19 | 3 | Thymeleaf rendering, auth required, timeline page, **window cap** |
| `JwtServiceTest` | 18 | 4 | Sign/verify, expiry, tampering, secret validation |
| `AjsObservabilityServiceTest` | 17 | 1 | View logic, `*NOSBM` rule, gaps, lanes, **run cap and honest totals** |
| `PermissionDenylistTest` | 9 | 4 | Denylist semantics, cascade, per-user scoping |
| `TimelineViewHelperTest` | 8 | 1 | Bar geometry, gaps, minimum width, edges |
| `AppPermissionEnforcementTest` | 8 | 4 | Denylist, **disabled-app**, and **timeline** enforcement on routes |
| `CsrfProtectionTest` | 9 | 4 | CSRF is on; token-less POST 403s; first visit issues the cookie; token renders |
| `SecurityWiringTest` | 3 | 4 | Chain installed, bcrypt is real bcrypt |
| `MockMvcSecurityDiagnosticTest` | 1 | 4 | Isolates harness vs application security |
| `FixtureSchemaConsistencyTest` | 4 | 2 | Fixture and mappers agree on columns; validator's requirements exist |

> Counts are per top-level test class; JUnit nested classes
> (`AjsDateDecodersTest$Cyymmdd` and friends) are folded into their parent.

### Smoke test, over real HTTP

Tests against H2 prove the logic; this proves the stack. Run the `local` profile
and drive it with `curl`:

| Path | Result |
|------|--------|
| `/overview` | 200 — counters `8 7 1 0 1 2 1` = total, ready, held, due today, genuine failures, never run, unscheduled |
| `/failures` | 200 — genuine section shows `PAYROLL2` only; skipped section `PAYROLL3` |
| `/jobs` | 200 — grouped by application group |
| `/attention` | 200 — held `ARCHIVE1`, never-run `ARCHIVE1` + `REPORT1` |
| `/calendar?date=2025-11-03` | 200 — 4 ready jobs due |
| `/jobs/REPORT1` | 200 — renders `9999999 — not scheduled` |
| `/jobs/PAYROLL3` | 200 — renders `not counted as a failure` |
| `/jobs/PAYROLL1` | 200 — both commands, padding stripped |
| `/jobs/NOPE` | 200 — helpful not-found page, not a stack trace |
| `/actuator/health` | 200 |

**CSRF smoke test**, also over real HTTP and through headless Chromium rather than
MockMvc, because the two defects in C1 were invisible to the harness:

| Action | Result |
|--------|--------|
| Browser sign-in, CSRF on | 302 → `/overview` |
| All 8 admin/dashboard POST forms | carry a real `_csrf` token |
| `POST /auth/sign-in` with no token (forged cross-site) | **403** |
| `POST /auth/sign-in` with a mismatched token | **403** |
| Ordinary user → disabled app (browser) | **403** |
| Administrator → same disabled app (browser) | 200, so it can be re-enabled |

Every one of those rows was driven against the running `local` profile, not
inferred from the unit tests.

Those counters are the check that the `*NOSBM` exclusion is genuinely applied:
8 jobs, 1 held, **1** genuine failure (PAYROLL2, with PAYROLL3 excluded), 2 never
run. The live smoke test is also what caught two of the near-misses in §7 — the
test harness alone would not have.

### Verified production jar composition

H2: **0** entries (correctly absent — the shipped artifact can only talk to a
real IBM i). SQLite: **4** entries present.

### What local testing cannot cover

The fixture is **authored**, not extracted, so passing tests prove the
application, not the IBM i. Restating the gap plainly because it is the whole
point of the exercise:

- R1: the actual `QAIJSMST`/`QAIJSHST` column layouts (IBM support notes, not a
  published contract).
- R2: real `CYYMMDD` encoding and `9999999`/`0` sentinel behaviour per release.
- R4: presence and PTF level of `QSYS2.ACTIVE_JOB_INFO`.
- R5: the jt400 DRDA/host-server path end to end.
- The auth schema is *believed* compatible with the reference `my-dashboard`
  database (bcrypt `$2a$10$`, same columns), but no test moves a row between the
  two.

**Highest-leverage ask: one read-only connection to one real IBM i for one
afternoon, before Phase 1 ends.** Cheaper than rework, more valuable than any
local scaffolding. See §9.

---

## 4a. What it looks like, figure by figure

The seventeen figures below are real captures of the running application, taken
through the real sign-in flow by `docs/tools/capture.mjs`. The data in them is
the **fabricated fixture**, so they show the application, not any particular
scheduler. They are here rather than in a separate illustrated document because
a picture of a page is evidence about the page, and evidence belongs next to the
claim it supports. Regenerate with `docs/tools/README.md`; the PNGs live in
`docs/screenshots/`.

### Authentication and sign-in

Auth is held in a **separate SQLite database**, deliberately not bound to the
IBM i connection. If the two were merged, an iSeries outage would lock operators
out of the very tool they use to diagnose that outage. The application can
therefore authenticate, render sign-in and be administered even when the host is
unreachable.

#### Sign in

![Figure 1 — Sign-in](screenshots/01-sign-in.png)

**Figure 1 — Sign-in.** Standalone page with no navigation, since everything
behind it requires a session. Errors are shown inline and preserve what was
typed.

![Figure 2 — Self-service sign-up](screenshots/02-sign-up.png)

**Figure 2 — Self-service sign-up.** Always creates an ordinary user. There is
no path from this form to an administrator account; only the admin UI can grant
that.

### Dashboards

![Figure 3 — Overview](screenshots/03-overview.png)

**Figure 3 — Overview.** Headline counters for the partition. The amber note
explains the gap between raw abnormal completions and the genuine failure count,
so the numbers can be reconciled without reading SQL. Note the _Users_ and
_Apps_ links in the navigation, and the _Administrator / ADMIN_ identity at top
right.

![Figure 4 — Timeline](screenshots/17-timeline.png)

**Figure 4 — Timeline.** Every run in a window, as a bar whose width is its
duration. Lanes come from the _job master file_, not from history, so a job that
was due but produced no run still appears (dashed _not run_ marker) — the case
an operator is looking for. The window is capped at 31 days and each lane at 200
runs, and when a lane is capped the summary still reports the true totals and
states the truncation, so a bounded page can never read as a false all-clear.

![Figure 5 — Failures](screenshots/06-failures.png)

**Figure 5 — Failures.** Split into two tables that must not be conflated.
_Genuine failures_ (PAYROLL2, 88s elapsed) are alerts. _Skipped, not failed_
(PAYROLL3, `*NOSBM`) were not submitted by AJS — typically because the system
was unavailable — and are listed for transparency but are not alerts.

![Figure 6 — Jobs due](screenshots/04-calendar-due.png)

**Figure 6 — Jobs due.** Schedules for a chosen date, defaulting to today. A
malformed date shows a warning rather than a 400 error page, since on a
monitoring tool that would be a support call rather than a helpful error.

![Figure 7 — Jobs due on a specific date](screenshots/05-calendar-a-date.png)

**Figure 7 — Jobs due on a specific date.** The last-result column is derived
from the completion code, so `N`/`A` is never shown raw.

![Figure 8 — Inventory](screenshots/07-inventory.png)

**Figure 8 — Inventory.** Every AJS job definition visible to the account,
grouped by application group. The least volatile view, and the natural candidate
for caching so the host is not polled harder than necessary.

![Figure 9 — Needs attention](screenshots/11-attention.png)

**Figure 9 — Needs attention.** Held jobs (`*HELD`) and jobs that have never run
(`JMLRD = 0`). Held is not an error state, but a job held longer than expected
usually is.

### Job detail, and the legacy-date handling

AJS stores dates in packed legacy formats with sentinel values, and a naive cast
raises SQL0181 / CPD4019. All decoding is centralised in one tested class, and
the pages name the sentinel rather than rendering a meaningless date.

![Figure 10 — Task with no scheduled next run](screenshots/09-job-sentinel.png)

**Figure 10 — Task with no scheduled next run.** `JMDTE` holds the sentinel
`9999999`, which is surfaced as “_(JMDTE sentinel 9999999 — not scheduled)_”
rather than being cast to a date, and `JMLRD = 0` renders as “Never”.

![Figure 11 — Recovery action explained in context](screenshots/10-job-nosbm.png)

**Figure 11 — Recovery action explained in context.** The `*NOSBM` recovery
action triggers an explicit note that the abnormal completion is most likely a
skip, not a failure.

![Figure 12 — Task definition and command list](screenshots/08-job-detail.png)

**Figure 12 — Task definition and command list.** Definition fields are mapped
straight from `QAIJSMST` and the command list from `QAIJS1CM` (joined on `CMJOB
= JMJOB`). Fixed-width character columns arrive blank-padded from IBM i, so
padding is stripped at the repository boundary rather than leaking into the
display or a CSV export.

![Figure 13 — Unknown job](screenshots/12-not-found.png)

**Figure 13 — Unknown job.** A helpful page rather than a stack trace, and it
distinguishes “no such job” from “your profile lacks authority to see it”, which
are the two real causes.

### Administration

Administration mirrors the reference implementation in `my-dashboard`: the same
table shapes, the same bcrypt algorithm (so a password hash can move between the
two without a reset) and the same permission model.

![Figure 14 — Users](screenshots/13-admin-users.png)

**Figure 14 — Users.** Create accounts, change roles, enable or disable, and
open per-user permissions. Disabling takes effect on the target's _next
request_, not when their 24-hour token expires, because the account is re-read
from the database every request.

**The permission model is a denylist — this is the easiest thing to misread**

A row in `user_applications` means the user **cannot** access that app. No rows
means **full access**, and a newly added app is granted to everyone
automatically because nobody holds a deny row for it yet. Inverting this turns
“restrict one app” into “revoke everything”.

That is why the page says _“0 denied of 5”_ rather than “0 granted”, and why the
checkbox is labelled _Denied_.

![Figure 15 — Per-user permissions](screenshots/14-admin-permissions.png)

**Figure 15 — Per-user permissions.** Checked means _denied_. The page states
the direction explicitly, shows the current effect per app, and lists the
resulting effective access so the outcome is visible before saving.

![Figure 16 — Application registry](screenshots/15-admin-apps.png)

**Figure 16 — Application registry.** The apps users can be denied. The _Denied
for_ column names the users holding a deny row, so a restriction is never
invisible from the app's own page.

![Figure 17 — The same dashboard as an ordinary user](screenshots/16-overview-as-user.png)

**Figure 17 — The same dashboard as an ordinary user.** Compare with Figure 3:
the identity reads _Viewer / USER_, and the _Users_ and _Apps_ links are absent.
Navigation is rendered from the database-backed authority, so a demoted
administrator loses those links on their next request.

The figures stop here. The states that matter most are not photogenic — a 403 on
a denied app, a 401 on a next request after a disable, a 302 on a forged POST —
and are asserted in §4 and §6 rather than illustrated.

---

## 5. Domain semantics

These rules are the reason the application exists rather than a plain table dump,
and all of them are enforced in tested Java rather than in template expressions.

- `JMSTS` — `*READY` vs `*HELD`.
- `JMLCC` — `'N'` normal, `'A'` abnormal.
- `JMLRD` — 6-digit `YYMMDD`, `0` = never run.
- `JMDTE` — 7-digit `CYYMMDD`, `9999999` = no next run.
- Recovery codes: **`*NOSBM` skipped-due-to-system-down is not a job failure.**
  Surfacing raw rows as failures without this filter produces false alarms, and
  false alarms destroy trust in a dashboard faster than missing a real failure.

### Legacy date handling

All of it lives in `AjsDateDecoders` and nowhere else. That is risk R2's
containment boundary, and it is enforced by convention plus tests.

Two subtle behaviours, both of which were wrong first:

- **CYYMMDD's leading digit is a century flag** (0 = 19xx, 1 = 20xx). So
  `1991231` is `2099-12-31`, not 1999. `0991231` — numerically `991231` — is the
  value that means 1999.
- **YYMMDD has no century, so the pivot matters.** A naive
  `yy <= currentYear % 100` rule turns a next-run date of `2026-01-01` into
  **1926-01-01**. The rule used instead: prefer 20xx unless that is implausibly
  far in the future.

The decoders are deliberately strict — malformed input throws rather than
returning a plausible wrong date, because a wrong date in a job monitor produces
a false "job ran fine" signal.

---

## 6. Security

### Model

Accounts live in SQLite with bcrypt hashes (`$2a$10$`, matching the reference
implementation). Sessions are stateless JWT cookies named `auth-token`.
Dashboards require authentication; `/admin/**` requires the `ADMIN` role. Sign-up
creates `USER` only — there is no path to admin.

**The token is not authoritative.** It carries the role so gating is cheap, but
every request re-reads the user from SQLite and rejects a missing or disabled
account; authorities are built from the *database* role. That is why disabling or
demoting takes effect on the very next request rather than whenever the 24-hour
token expires.

### Permissions are a denylist

`user_applications` is a **denylist**: a row means the user **cannot** access
that app. No rows means full access, and new apps are granted automatically
because nobody has a deny row for them yet. Inverting this turns "restrict one
app" into "revoke everything". Pinned by 9 tests in `PermissionDenylistTest`.

### Properties verified against the running app

| Property | Result |
|----------|--------|
| Anonymous dashboard access | 302 → `/sign-in` (browser), 401 (API client) |
| Valid session cookie | 200, identity + role shown in nav |
| **Appended-to-signature token** | **401 — rejected** (see below) |
| Cookie for a disabled user | 401 on the very next request |
| Cookie for a deleted user | 401 on the very next request |
| Demoted admin | 403 on the very next request |
| Ordinary user → `/admin/**` | 403 |
| Self-disable / self-demote | refused with an explanatory message |
| Disabling the last admin | refused |
| Denied app route | 403 in the same session, other routes unaffected |
| Denied app **sub-path** | 403 (cannot bypass via `/jobs/PAYROLL1`) |
| Admin vs denylist | admin bypasses; would otherwise be unable to administer |
| Denied app route **with CSRF on** | still 403; the two controls are independent |
| Disabled app route, ordinary user | 403 (browser-verified; see C4) |
| Disabled app route, admin | 200, so it can be re-enabled |
| POST with no CSRF token | 403 (**browser-verified**, not just MockMvc) |
| POST with a mismatched CSRF token | 403 |
| Legitimate browser sign-in, CSRF on | 302 → `/overview` |
| Public sign-up | creates `USER` only; no path to admin |

### CSRF

Protection is **enabled**, with `CookieCsrfTokenRepository` rather than the
default session-backed repository — the latter cannot work on a stateless chain
and fails in a way that renders a correct-looking form. All ten POST forms carry a
token via `th:action`; no template needed editing.

This matters to the threat model more than it looks. Before the fix, the *only*
thing preventing forgery of `POST /admin/users/{id}/delete` and its siblings was
`SameSite=Lax` on the session cookie — a browser-side control that silently
disappears if the cookie policy is ever relaxed for a cross-site deployment, and
which was recorded nowhere. It is now defence in depth rather than the sole
mitigation. See design review C1 for how the fix was verified, including the
second defect it uncovered.

**Not yet done:** the `Set-Cookie` for the session still uses `setSecure(false)`,
which must be flipped to `true` behind HTTPS. Noted in §13.

### The JWT bug worth remembering

**Appending a character to a valid token's signature was accepted by jjwt
0.12.6.** Measured, not theorised: jjwt base64url-*decodes* the signature before
comparing, and the trailing character contributes only padding bits — which are
discarded, so the same signature bytes result and verification succeeds.

`JwtService` now requires every segment to be canonical: valid base64url that
round-trips exactly through decode→encode. A segment carrying discarded padding
bits cannot round-trip and is rejected. Appending `x` now yields 401. Flipping a
signature character and forging a payload were already rejected; this closed the
remaining gap. Pinned by `JwtServiceTest.appendedToSignature`.

### Two near-misses worth remembering

1. **MockMvc did not install the security filter chain.** With the default
   `@AutoConfigureMockMvc` setup, unauthenticated requests returned **200** in
   tests while the running app correctly returned 401. "The gate is closed" was
   being asserted against a harness with no gate. Fixed by building MockMvc with
   `.apply(SecurityMockMvcConfigurers.springSecurity())`, and `SecurityWiringTest`
   now asserts the chain exists. Found only because the live app was smoke-tested
   over real HTTP.
2. **The denylist was stored but not enforced.** The admin UI saved and displayed
   "no access" for an app, and the user could open it anyway. Fixed with
   `AppPermissionInterceptor`, which maps routes to slugs, covers sub-paths, and
   exempts `/sign-in`, `/admin/**` and Actuator so a misconfiguration cannot lock
   everyone out.

> A restriction that is recorded but not applied is worse than no restriction,
> because it reads as a working control.

### Trade-offs accepted knowingly

- **CSRF is enabled** (it was disabled; see §8 finding C1). The remaining related
  gap is `setSecure(false)` on the session cookie below, which must be flipped
  behind HTTPS. `SameSite=Lax` is retained as defence in depth rather than as the
  only mitigation.
- **Sign-in reveals whether an email exists** ("no account found" vs "incorrect
  password"), matching the reference so users get actionable errors. This allows
  account enumeration. Collapse the two messages before facing the public
  internet; the distinction is logged server-side either way.
- **`spring-boot-starter-security` puts the jar at ~51 MB.** Fine for an internal
  tool; worth noting if image size matters.
- **`usersDenied()` on the Apps page is O(users × apps) queries.** Acceptable at
  this scale; needs a join before the user count grows materially.
- **`setSecure(false)` on the session cookie** because development is local HTTP.
  **Must be flipped to `true` behind HTTPS**, or the token is sent in clear text.

---

## 7. The timeline view

Added last, and the only view with real visual design in it. It answers what none
of the others could: *when did jobs actually run, and how long did each take?*

**Encoding:** one bar per run. **Left edge = start time, width = duration.** A
scheduling pile-up or a runaway step is visible without reading numbers.

**Dynamic time scale:** `Auto` / `15 min` / `Hour` / `6 hour` / `Day`. `Auto`
picks the finest interval whose labels still fit across the plot, so the scale
adapts to the window *and* to the viewport. The scale is validated against a fixed
set in `DashboardController` rather than trusted, because it drives tick
arithmetic.

Behaviours that are deliberate and should not be "simplified":

- **A job that was expected to run but produced nothing still appears**, as a
  dashed `not run` marker. Lanes are built from the *job master file*, not from
  history rows. Deriving them from history would hide exactly the case an
  operator is looking for.
- **A run with no elapsed time is treated as still running**, not as duration
  zero. The alternative draws an in-flight job as an instantaneous success, which
  is a false all-clear.
- **Bars have a 0.35% minimum width** (`TimelineViewHelper.MIN_WIDTH_PCT`). This
  is a deliberate visual lie: without it, a short run in a wide window rounds to
  zero pixels and a job that *did* run looks like one that did not. The tooltip
  and the detail table carry the true duration.
- **The window is half-open `[from, to)`**, so paging through days does not
  double-count a run starting exactly on a boundary.
- **No JavaScript is needed to render.** Bar geometry is computed server-side in
  `TimelineViewHelper`; the only script on the page draws axis ticks, and with it
  disabled the bars stay correct and only the labels disappear. On a monitoring
  page, "goes blank when a script fails" is a real failure mode.

### Axis labels never collide

Tick spacing is chosen by **measurement, not by a hardcoded count**. This was a
real bug: the first version capped ticks at 100 and picked an interval from the
window length alone, ignoring how much room the labels had. A 4-day window chose
the 60-minute interval, drew 96 labels into ~825px, and the axis became an
unreadable smear. The defect survived review because it only appears on wide
windows — a one-day window looks fine.

The fix measures, at render time: the plot's client width, and one representative
label's width in the axis's own font (a `visibility: hidden` probe span, since
`"12:34 PM"` and `"19 Nov"` differ and the font is theme-inherited). From those
it picks the smallest interval from a list of human-readable steps — 1, 2, 5, 10,
15, 30 minutes, then hours and days — whose labels fit at `labelWidth + 14px`
spacing.

Two decisions worth keeping:

- **Ticks and labels are decoupled.** The grid stays at the finest sensible
  interval so bars can be read against a dense grid, while labels are thinned to
  whatever fits (`i % labelEvery`). Choosing `Hour` on a 30-day window legitimately
  produces ~720 gridlines and about a dozen labels; refusing the user's scale, or
  drawing 720 labels, would both be worse.
- **Ticks align to wall-clock boundaries** — multiples of the interval since local
  midnight — so an hour mark is on the hour rather than 11 minutes past it.

Verified by measuring, not by eye: a script logged into the app and counted label
pairs whose bounding boxes intersect, over 2-hour, 24-hour, 4-day and 30-day
windows, at 1100px and 520px viewport widths, plus the explicit `hour` scale on a
4-day window. Zero overlaps in every case; the 4-day window went from ~96 colliding
labels to 9.

**Two overflow bugs the screenshots exposed.** A tick at exactly 100% drew its 1px
border outside the axis box, and a label there hung past the right edge — together
they made the whole plot scroll horizontally for no reason. The last tick is now
skipped (the window end is stated in the header line above the chart anyway) and a
label in the final ~6% is right-aligned against its tick instead of left.

Separately, a bar's *duration* label (`.tl-lbl`) always hung to the right of its
bar, so a run starting near the end of the window pushed its label outside the
chart shell, where `overflow: hidden` cut it to a fragment — one figure showed
`2:` instead of `2m 25s`. That decision depends on bar position, so it lives in
`TimelineViewHelper.labelFlipped` and is unit-tested either side of its threshold,
following the rule that percentage arithmetic does not belong in a `th:style`.

Percentage arithmetic lives in `TimelineViewHelper` rather than in template
expressions. It is logic, and logic in a `th:style` attribute cannot be tested —
the first attempt put nested `BigDecimal` expressions there, and it produced the
truncated-page bug described in §8.

### The response is bounded, and truncation is never silent

A bounded query is not a bounded page. C2's SQL fix stopped reading the whole
history table, but a five-year window still put every run in it into one HTML
document — measured at **200,000 bars and 200,000 table rows** against a seeded
fixture. Two limits are now in place:

- **Window capped at 31 days** (`DashboardController.MAX_WINDOW`). A wider request
  is narrowed to the last 31 days and *says so* in a warning. It is narrowed rather
  than rejected because a 400 on a monitoring page becomes a support call, and a
  blank chart reads as "nothing ran".
- **Runs capped per lane at 200** (`AjsObservabilityService.MAX_RUNS_PER_LANE`),
  keeping the **newest**. A per-job cap, not a page: lanes are derived from the
  master file, so capping the lane count would hide jobs.

**Why pagination was rejected here, despite `req.txt` §6 saying "paginate lists".**
Two independent reasons, both specific to this view: bar positions are percentages
*of the window*, so a page of runs drawn against the full window leaves the plot
nearly empty for a job that ran constantly — a smaller view, not a smaller *wrong*
view; and `gap from prior` is inherently sequential, so the first row after a page
boundary has no truthful value. The existing `Previous/Next 24h` controls remain the
way to step through time, because a window is a meaningful unit and a page of runs
is not.

**The cap must not create a false all-clear.** `TimelineResult` carries the drawn
lanes *and* the true totals, computed over every run **before** capping. So the
summary can read `43200 runs · 8 jobs · 1 abnormal` while the chart draws 200 bars.
This was verified end-to-end, not just in unit tests: a window was seeded with
43,200 runs where the *oldest* — and therefore discarded — run was the only
abnormal one. The chart drew 0 abnormal bars, and the summary still reported
`1 abnormal`. A truncated view cannot understate activity.

Both bounds are proven to be genuinely tested: removing the window cap fails the
controller test, and removing the per-lane cap fails three service tests.

### Visual treatment

The chart uses the shared theme's tokens (`--surface`, `--line`, lane colours,
monospace for data and sans for prose), adapted from the sssf visualizer's palette
but kept CSS-only — no build step, no CDN, no framework. Bars grow left-to-right
because width *is* the meaning. A `now` marker is drawn only when the window
actually contains now, so a historical window never shows a misleading line.

The bar vocabulary is `ok` / `abnormal` / `long` / `running` / `not run`. The
`long` class is a real domain classification (> 30 minutes, in Java), not a style
applied in the template — the same rule as everywhere else in this view.

### Light and dark themes

There is a sun/moon toggle in the header of every page, and floating in the corner
of the two standalone auth pages (sign-in, sign-up), which have no header. The
choice is remembered in `localStorage` under `ajs-theme`.

The implementation follows the rule the palette in `layout.html` already stated:
**re-point the tokens, do not override rules**. The light theme is one
`html[data-theme="light"]` block that re-declares `--bg`, `--panel`, `--fg`,
`--accent` and the status colours. No template had to change, which is the same
argument the original palette comment made for keeping the token names.

Four colours were literal hex values inside rule bodies and had to be lifted into
tokens to make the switch work: `--row-hover`, `--on-accent` (text on the accent
fill), `--header-bg` (the sticky header's translucent backdrop) and the two
`--aurora-*` washes. These name colours that were already there; they are not new
design decisions. The tag outlines also became tokens (`--tag-ok` and friends),
because a border mixed for a dark background is invisible on a light one.

The light palette is not an inversion. The aurora wash is dialled right back (a
violet glow on white reads as a smudge) and the accent and status colours are
darkened so they keep contrast against white rather than glowing.

Two implementation notes worth keeping:

- **The theme is applied in `<head>`, before the body paints.** A script at the end
  of the body — where the timeline's tick script lives — would show a white flash
  to a dark-mode user on every page load. The precedence is an explicit stored
  choice, falling back to `prefers-color-scheme`, so a user who never pressed the
  button keeps following their OS when it switches at sunset.
- **Both glyphs are always in the DOM** and CSS hides one based on `data-theme`,
  so the icon is correct on first paint and does not wait for the script. With
  scripting disabled the page still renders in the default dark theme, which is
  the same no-JS deal the timeline makes: a missing enhancement degrades the
  appearance, not the content.

`localStorage` access is wrapped in `try`/`catch` because it throws in some
private-browsing modes; a user in those modes gets a working toggle that simply
forgets their choice.

**Known defect seen while verifying, not caused by this change:** with a wide
window (e.g. 4 days), the timeline's auto scale picked the 60-minute bucket and
rendered up to ~96 tick labels in a single row, where they overlapped into an
unreadable smear. This was present in both themes. **Since fixed** — see "Axis
labels never collide" in §7.

### Fixture history

`data-ajs.sql` seeds 20 `QAIJSHST` rows across 2025-11-19..21: an overlapping
nightly batch cluster, one 47-minute archive job, a failure that is retried
successfully, and repeated short jobs to exercise the 15-minute scale. Every
`HSJOB` names a job present in `QAIJSMST`, because a history row for an unknown
job is silently undisplayable.

`docs/screenshots/17-timeline.png` is the current figure, and `capture.mjs` now
captures it. It uses a **tall viewport rather than `fullPage`**, because the theme's
`background-attachment: fixed` produces a solid band in a full-page capture — the
first attempt produced an unusable image that looked correct in the exit code.

All seventeen figures were regenerated after the theme and axis work, so they now
show the current UI. Two things about the capture process are worth knowing:

- **`capture.mjs` pins the theme to dark** (`AJS_CAPTURE_THEME`, default `dark`).
  Without it the figures came out light, because headless Chromium reports a light
  `prefers-color-scheme` while the rest of the documentation is dark. It pins by
  writing `localStorage.ajs-theme` via `evaluateOnNewDocument` — the same key the
  toggle writes — so the choice survives navigation and the page's head script sees
  it before first paint.
- **`trim.mjs` samples the background from each image** rather than using a
  constant. It used to carry `#0f1419`, described as "matches --bg in layout.html",
  which was true of neither theme (dark `--bg` is `#06080f`, light is `#f6f8fc`).
  It appeared to work only because the same constant was the colour `-trim` cropped
  against. The first light-theme capture exposed it: nothing matched, `-trim` was a
  no-op, and the script reported success on images it had not resized (1800px tall
  going in, 1800px coming out). Reading the corner pixel fixes both themes.

  Two related behaviours worth knowing: the operation is now a **fixed point**, so
  re-running it does not grow the image (width is taken from the crop, not from the
  previous pass's padding), and a file with no dead space gets a warning instead of
  a silent "trimmed". Failure to crop is a fact about the input, not a detail to
  hide — the same principle as the timeline's honest truncation.

---

## 7a. What this costs the partition

The requirement behind this section is risk R6: *"polling too aggressively can
consume IBM i jobs/CPU"*. The question an operator actually asks is narrower —
"will putting this dashboard in front of production make production slower?" — so
it is answered with the load shape rather than with reassurance.

**There is no poller.** Grep for it: no `@Scheduled`, no `@EnableScheduling`, no
`ExecutorService`, no `Thread`, no client-side `setInterval`. Every query is issued
because a user opened a page. Zero users means zero queries — a stronger guarantee
than any interval, and one that no configuration mistake can break.

**The `ajs.polling.*` names are cache lifetimes, not a fetch cadence.** They are
called "polling" because `req.txt` §6 uses that word, but nothing polls. An
operator reading a page may see data up to that old. This distinction matters: an
earlier revision of `application.yml` carried these keys with no code reading them
at all, which is worse than having no config, because the next person reasons from
it.

### Measured, not asserted

The claim "caching works" was tested by counting actual SQL statements with
`JdbcTemplate` at DEBUG, over a fixed sequence of seven page views (three
`/overview`, two `/failures`, two `/jobs`) after one sign-in:

| | `QAIJSMST` reads | `QAIJSHST` reads |
| --- | --- | --- |
| Cache disabled (`ajs.polling.cache-enabled=false`) | **10** | 0 |
| Cache enabled (default) | **2** | 0 |

The no-cache figure matches the predicted query shape exactly: 3 + (2 × 2 for
failures, which read the completion code twice) + 2 = 9, plus one during the
post-login landing. The enabled figure is 1 during landing plus 1 across all seven
views.

A `SELECT ... FROM users` appears once per request throughout. That is the **local
SQLite auth store**, a different datasource — it never touches the IBM i, and
counting it as partition load would be a category error.

### The self-invocation trap, found by this measurement

Caching the repository was not sufficient on its own, and the counting above is
what caught it. `findScheduledOn` builds its answer by calling `findAllJobs()`
**inside the same class**. Spring's `@Cacheable` works through a proxy, so a
self-invocation runs the method body directly and skips the cache entirely.

Measured: **five calendar views for five dates produced five master-file reads**,
where the design intended one. Nothing failed, nothing logged a warning, and every
page rendered correctly — which is exactly why it survived a code review.

`findScheduledOn` now carries its own `@Cacheable` keyed by date. Verified after
the change: six repeat views of *one* date produced **zero** reads. Five views of
five *different* dates still produce five, which is correct — each date is a
distinct result.

A regression test pins this (`calendarIsCachedPerDate`), and it was confirmed to
fail when the annotation is removed. The test exists because the failure mode is
invisible: the page still works, it is just quietly expensive.

This is worth stating as a general rule for this codebase: **any cached repository
method that another method in the same class calls gets no caching from the
annotation.** Either cache the caller as well, or move the call to the service
layer.

### Where the load could still grow

`findAllJobs()` has **no `WHERE` clause**: it reads the whole master file. That is
deliberate and load-bearing, because it is what allows next-run dates to be
decoded in Java rather than compared in SQL (CYYMMDD versus a SQL date raises
SQL0181, risk R2). It is justified only by `req.txt` §6's "a few thousand jobs".

So the assumption is checked rather than trusted. The row count is compared
against `JdbcAjsRepository.ASSUMED_SCALE_JOBS` (5,000) on every read, and a warning
is logged once if exceeded. If that warning ever appears, the fix is to project
only the columns the list views need and/or push the date filter into SQL — **not**
to raise the constant.

### Three things that cannot be settled without a partition

Stated plainly, because "theoretically fine" is not the same as "verified".

1. **Whether `QAIJSHST.HSDTE` has an access path.** The timeline's `BETWEEN` is
   index-usable *if one exists*. Without a logical file over that column, DB2 for i
   scans the file — cheap for a bounded range on a few hundred thousand rows, but
   still a scan. Check with `DSPDBR FILE(QUSRIJS/QAIJSHST)` or `DSPFD`, or look for
   an index containing `HSDTE`, before go-live.
2. **The real row count in `QAIJSMST`.** The scale guard reports it at runtime; the
   cheapest way to know in advance is `SELECT COUNT(*)` as the service profile, or
   `DSPFD FILE(QUSRIJS/QAIJSMST)`. If the real figure is far above a few thousand,
   the unqualified read is the first thing to change.
3. **Actual DB2 for i timings.** Everything above is structural: statement counts
   and bounds. It says nothing about milliseconds on the target hardware. The way
   to convert this from theory to measurement is a short `STRDBMON` collection (or
   `QSYS2.SQL_STATEMENT_STATISTICS` after the fact) while browsing each view once.

That third point is the one to act on. The rest of this section reduces the number
of statements and bounds each one; only the partition can say what a statement
costs.

---

## 8. Design review

An honest critique of the current design, ordered by how much each issue matters
rather than how easy it is to fix. Every finding was verified against the code,
with file and line references so each can be checked.

**Verdict:** the core architecture is right and I would not change it. (This
section judges the *design*; §12a judges the *deliverable* and proposes what to
do next.) Read-only
SQL over `QUSRIJS`, a repository interface with two implementations,
server-rendered Thymeleaf, domain semantics in tested Java — those decisions are
holding up, and the offline test tier is the reason the bugs in this document
were caught at all. The problems are concentrated in the security layer's
reasoning and in query strategy. Nothing is an emergency; **C1 and C2 should be
fixed before this is exposed beyond a handful of trusted users.**

| # | Issue | Severity | Status |
| --- | --- | --- | --- |
| C1 | CSRF disabled; the justification in the comment is false | **High** | **Fixed** |
| C2 | `findRunsBetween` selects the entire history table | **High** | **Fixed** |
| C3 | Admin actions are not audited at all | Medium | Open |
| C4 | A disabled app is hidden but still reachable (found while fixing C1) | Medium | **Fixed** |
| C5 | Several service methods each re-read the whole master file | Low | Open |
| C6 | No caching, and no actual poller | Low | **Caching fixed; no poller is deliberate** |
| C7 | `LocalDate.now()` in domain code limits testability | Low | Open |
| C8 | The timeline was not a governable app, so it could not be denied per user (found while documenting) | Medium | **Fixed** |

Fixing C1 and C4 each uncovered a second defect that the original review had not
identified, and both were the same shape: a control that read as enforced but was
not. Details are in the sections below. Neither was caught by the test suite as it
stood, which is the point — a passing suite is not evidence that a gate is closed.

### C1 — CSRF is disabled, and the stated reason was untrue — FIXED

**Fixed.** CSRF is now enabled with `CookieCsrfTokenRepository`.

Two defects were found here, not one.

**The first was the one the review predicted:** protection was off, and the comment
justifying it was false.

**The second was only visible once protection was switched on.** The first attempt
used `csrf(Customizer.withDefaults())`, which is the obvious fix and is what the
guidance in this document originally recommended. It was wrong for this
application. The default repository is `HttpSessionCsrfTokenRepository`, and the
filter chain is `SessionCreationPolicy.STATELESS` — so the token was written into
a session that is never retained. The token still **rendered correctly into every
form**, because Thymeleaf's `th:action` reads it during rendering, which means the
HTML looked perfect while every submission would have 403ed for a real user. The
fix is `CookieCsrfTokenRepository.withHttpOnlyFalse()`, which is the supported
pairing for a stateless chain.

That second defect is worth dwelling on because of how it presented: the test
suite was **green** in both states. A disabled filter makes a POST succeed, and so
does a working filter; the existing tests only ever asserted the success case. This
is the same trap as §9 bug 6 — "assert something rendered" is not "assert it
finished rendering" — and the answer was the same: assert the failure mode, and
verify against a real browser rather than the harness.

**What is in place now:**

- `CsrfProtectionTest` (7 tests) asserts the *inverted* case, so re-disabling
  protection fails loudly: a token-less POST must 403, as must a token-less
  sign-out. It also asserts the `_csrf` field actually renders on the sign-in and
  sign-up pages, and that a token-bearing POST is accepted.
- Every one of the ten POST forms was confirmed to carry a real token, driven
  through headless Chromium against the running `local` profile, not through
  MockMvc. Recorded results: sign-in returned 302 to `/overview`; all eight
  admin/dashboard forms reported `csrf: true`; a forged cross-site POST with no
  token returned **403**, as did one with a mismatched token.
- `SameSite=Lax` on the session cookie is retained as defence in depth, but it is
  no longer load-bearing. See §6.

**A limit of the MockMvc coverage, stated so it is not over-trusted:** the
`XSRF-TOKEN` cookie is written lazily by Spring Security 6, when the deferred token
is first accessed, so whether it appears on a given GET depends on harness state.
MockMvc therefore verifies rendering and acceptance, not the browser round trip.
Prefer the real-browser check whenever the CSRF wiring changes.

### C1 (original finding, kept for the record)

`SecurityConfig.filterChain` disabled CSRF with this justification:

> CSRF is disabled because the session is a cookie-authenticated stateless API
> and there is no HTML form POST from a browser session in the classic sense.

That was **factually wrong**, and it is worth being blunt about because a wrong
comment is worse than no comment: it stops the next person checking. There are
**ten** browser form POSTs, including destructive admin actions:

```
src/main/resources/templates/auth/admin-users.html:61   POST /admin/users/{id}/delete
src/main/resources/templates/auth/admin-users.html:57   POST /admin/users/{id}/disabled
src/main/resources/templates/auth/admin-users.html:38   POST /admin/users/{id}/role
src/main/resources/templates/auth/admin-permissions.html:23
src/main/resources/templates/auth/admin-apps.html:46,56
src/main/resources/templates/auth/sign-in.html:17
src/main/resources/templates/auth/sign-up.html:12
src/main/resources/templates/layout.html:158            POST /auth/sign-out
```

Ten `@PostMapping` handlers exist (`AdminController`, `AuthController`).

**Why this is not already exploitable:** the session cookie is set `SameSite=Lax`
(`AuthController:131`), which stops it being sent on a cross-site `POST`. So the
practical risk today is low — but that means **`SameSite=Lax` is the single
defense protecting every state-changing endpoint**, and that fact was recorded
nowhere. Two consequences:

- Any browser that does not honour `SameSite`, or any future relaxation to
  `SameSite=None`, silently makes admin user deletion CSRF-able.
- The comment told reviewers the risk was already handled, making the real
  dependency invisible.

**Recommendation:** re-enable CSRF. The templates are server-rendered Thymeleaf,
so this is genuinely easy — `th:action` injects the hidden `_csrf` field
automatically and Spring Security's default token repository works unchanged;
login and logout POSTs are handled by the same integration. Then delete the
comment rather than rewording it.

This finding is now closed; see the fixed section above.

### C2 — the timeline reads the entire history file on every request — FIXED

**Fixed, in two parts** — the query and the response, which turned out to be
separate problems. The date range is now a SQL predicate and the whole-table read
is gone; the page size is separately bounded, because a bounded query still
produced an unbounded page.

`JdbcAjsRepository.findRunsBetween` previously built:

```java
String sql = "SELECT " + JobRunRowMapper.SELECT_COLUMNS + " FROM " + HISTORY + " h";
```

No `WHERE` clause. It selects **every row in `QAIJSHST`** — on a real partition,
the complete run history, plausibly hundreds of thousands of rows growing forever
— transfers all of it over DRDA, maps every row in Java, and only then filters to
the window.

The window filter cannot trivially be pushed into SQL, and the existing code is
right about why: `HSDTE` is packed CYYMMDD, so comparing it to a timestamp raises
SQL0181/CPD4019 (risk R2). But "cannot compare timestamps directly" does not mean
"must read the whole table":

1. **Bound on the packed value.** `HSDTE` is numerically ordered within a century
   — `1251120` < `1251121`. A `WHERE h.HSDTE BETWEEN ? AND ?` on *decoded CYYMMDD
   integers* is a valid range restriction that can use an index. Only the window
   edges need decoding, in Java, before the query. This keeps date arithmetic out
   of SQL while making the query selective.
2. **`ORDER BY h.HSDTE, h.HSTME` in SQL** rather than sorting in Java, so the
   database can use an index for ordering.
3. At minimum, an explicit upper bound so the query cannot grow without limit.

**What was implemented.** Option 1 and option 2 above, together:

```java
Integer fromDate = AjsDateDecoders.encodeCyymmdd(from.toLocalDate());
Integer toDate   = AjsDateDecoders.encodeCyymmdd(to.toLocalDate());

String sql = "SELECT " + JobRunRowMapper.SELECT_COLUMNS
        + " FROM " + HISTORY + " h"
        + " WHERE h.HSDTE IS NOT NULL"
        + " AND h.HSDTE BETWEEN ? AND ?"
        + " ORDER BY h.HSDTE, h.HSTME";
```

Two details in that snippet are where the risk actually sits.

**The encoder had to be written, and correctness depends on it being a true
inverse.** `AjsDateDecoders` had decoders only. `encodeCyymmdd` was added to the
same class — the R2 containment boundary — deliberately, so the century flag logic
exists in exactly one place. The range is compared on *encoded* values, so an
off-by-one or a mishandled century flag would silently move a window edge and drop
or add a day of history. That is a wrong-answer bug, not an error, which is the
class this project is most careful about. `AjsDateDecodersTest` now pins the round
trip on both sides of 2000, asserts the encoded ordering that makes `BETWEEN`
valid at all, and asserts that years outside 1900..2099 throw rather than
guess a century.

**The bounds are inclusive of both whole days on purpose.** The method's contract
is a half-open `[from, to)` on timestamps, so encoding `to` as its date and using
`<=` over-fetches by at most one day, and the Java filter below still applies the
exact bound. Widening in SQL and narrowing in Java can only over-read; the
reverse would silently lose runs. `JdbcAjsRepositoryTest` asserts that two adjacent
day-windows partition the history with nothing lost at the shared boundary and
nothing double-counted.

**How the fix is tested, and why structurally.** A three-way split, because only
the first is a claim about performance:

1. **The SQL shape is asserted directly.** `historyQueryIsBounded` wraps the
   `JdbcTemplate` in a CGLIB proxy, records the statement the repository actually
   issues, and requires `WHERE`, `HSDTE`, `BETWEEN` and `ORDER BY`. This was
   verified to be a real test by reverting the query to the unbounded form: it
   fails, and passes again once restored. Timing assertions were rejected — a
   20-row fixture would pass either way.
2. **Boundary behaviour** is asserted behaviourally (adjacent windows, an empty
distant window).
3. **The encoder** is covered in the decoder suite as above.

Worth noting what the behavioural tests *cannot* show: they pass with the old
unbounded query too, which is precisely why assertion 1 exists.

**The response bound — also fixed, and it was a separate problem from the query.**
Bounding the SQL did not bound the page, because every run is a bar *and* a table
row. Measured before the fix, by seeding 200,000 runs into the local profile: the
query returned them in ~0.4 s while the *page* would have carried 400,000 DOM
elements. The query was never the bottleneck; the rendering was.

Two bounds, for two different reasons:

1. **A window is capped at 31 days** (`DashboardController.MAX_WINDOW`). A wider
   request is narrowed from the far past — keeping the right-hand edge, since a
   monitoring page is read from "now" leftwards — and the page says so in a
   warning. Not a 400: on a monitoring tool a 400 is a support call, and a blank
   chart reads as "nothing ran".
2. **Runs are capped per lane** at `MAX_RUNS_PER_LANE` (200), keeping the
   **newest** runs.

The second is the interesting decision, and it is deliberately *not* pagination,
which `req.txt` §6 asks for in general terms. Pagination is wrong here for two
independent reasons:

- **The chart is a density view.** Bar positions are percentages *of the window*,
  so drawing a page of runs against the full window would leave the plot nearly
  empty for a job that actually ran constantly. Pagination would not make the view
  smaller, it would make it misleading — the exact misreading the page exists to
  prevent.
- **`gap from prior` is sequential.** The first row of any page after the first has
  no truthful value, because its predecessor is on another page.

So the bound is applied per lane, and truncation is **reported with its exact
figure** rather than applied silently. This was the part worth getting right: a
silent cap would understate the run count and could hide failures, which is worse
than a slow page. `TimelineResult` therefore returns the drawn lanes *and* the true
totals for the window, and the counters are computed **before** the cap.

**Verified end to end**, not just in unit tests. With 200,000 runs seeded for one
job and a 31-day window containing 43,200 of them, driven through headless
Chromium against the running `local` profile:

| Measure | Result |
| --- | --- |
| Bars drawn | **200** (the cap), not 43,200 |
| Table rows drawn | **200** |
| Page render | **1.2 s**, 152 KB |
| Summary line | `43200 runs · 8 jobs · 1 abnormal` — the true totals |
| Notice | "Showing the most recent 200 of 43,200 runs in this window" |

And the honesty claim tested directly: the seeded window's **oldest** run was made
abnormal, so the cap discarded it. The chart drew **0 abnormal bars** while the
summary still reported **1 abnormal**. A truncated view therefore cannot produce a
false all-clear, which is the property that matters.

**Behavioural guard:** `AjsObservabilityServiceTest` covers that failures and
still-running runs are counted before capping, that the cap keeps the newest runs,
and that a history row with no matching lane is *not* counted as an omission
(so `omittedRuns` means only "dropped by the cap"). Both bounds were verified to
fail when removed: disabling the window cap fails the controller test, and
disabling the per-lane cap fails three service tests.

**Not changed:** the chart still does not paginate between windows, and the
quick ranges ("Previous 24h", "Next 24h") remain the way to step through time —
which is the right granularity for this view, since a window is a meaningful unit
and a page of runs is not.

The same pattern exists in `findScheduledOn`, which calls `findAllJobs()` and
filters in Java. That one is defensible and documented as a deliberate trade-off
at a few thousand jobs; the history table is a different order of magnitude.

### C3 — admin actions leave no trace

**Medium. Small effort.**

`AdminController` can create users, change roles, disable accounts, delete
accounts, and rewrite per-app permissions. None of it is logged. For an
application whose entire value proposition is "you can trust this view of
production", "who demoted the admin, and when" should be answerable.

This is the cheapest high-value fix here: a structured log line per admin
mutation (actor, action, target, outcome) is about twenty lines. Full audit
tables are a Phase 3 concern; a log line is not.

### C4 — a disabled app was hidden but still reachable — FIXED

**Fixed.** `AppPermissionInterceptor` now denies a disabled app.

This was not the finding as originally written. The original concern was that the
denylist could "fail open" — but `PermissionDenylistTest` already pinned that
behaviour as *intended* ("no rows means full access, not no access"), so there was
no defect there. Writing the test the finding asked for — a user with no permission
rows — is what exposed a real one elsewhere.

**The defect.** Disabling an app in `/admin/apps` updated the database and removed
it from `AppRepository.accessibleApps()`, but `AppPermissionInterceptor` never
consulted `enabled`. It only checked the denylist. So the route kept answering
**200** to any signed-in user: the admin UI said "disabled", the navigation still
worked if you typed the URL, and nothing enforced the setting. Disabling an app was
cosmetic. That is the same failure shape as C1 — a control that reads as enforced
and is not — and it is worse than not offering the control, because it is trusted.

The gap was found by writing the failing test first; it reported
`expected:<403> but was:<200>` before any production change was made.

**The fix** adds an `enabled` check ahead of the denylist check. Administrators are
exempt, for the same reason they bypass the denylist: re-enabling a disabled app is
an admin action, and locking admins out of the page they need to fix it would have
no in-product remedy.

**Verified in a real browser**, both sides of the exemption, against the running
`local` profile:

| Actor | Route | Result |
| --- | --- | --- |
| Ordinary user | enabled app (`/overview`) | **200** |
| Ordinary user | disabled app (`/failures`) | **403** |
| Administrator | disabled app (`/failures`) | **200**, so it can be re-enabled |

`AppPermissionEnforcementTest` covers both cases, including the assertion that the
disabled app is genuinely absent from `accessibleApps()` — that half was already
correct, which is exactly why the missing half went unnoticed.

### C4 (original finding, kept for the record)

**Medium. Small effort.**

A denylist that fails *open* shows a user jobs they are not entitled to see,
which can leak job names, library names and command text. Failing *closed* shows
an empty page and prompts a question. Empty page is the correct default. Worth
asserting explicitly that a user with no permission rows sees nothing, rather
than only asserting that explicit denials work.

### C5 — `overview()` re-reads everything to compute counters

**Low. Small effort, only if it matters.**

`overview()` calls `findAllJobs()` once and derives eight counters in one pass,
which is fine. But counters are recomputed per request, and `allJobs()`,
`byApplicationGroup()` and `timeline()` each call `findAllJobs()` again, while
`countJobs()` issues a separate `SELECT COUNT(*)`. A single page render can issue
several full master-file reads.

Acceptable at this scale. Noted so nobody optimises one method in isolation and
misses that the pattern is systemic; the real fix is per-request caching (C6).

### C6 — no caching, and no actual poller

**Low now, Medium later. Caching half fixed; the "no poller" half is a decision,
not a defect.**

`req.txt` §6 asks for adaptive polling and caching (R6: excessive polling can
consume IBM i CPU). The original finding was that neither existed, so load was
proportional to human page views with no limit — a user holding F5 hammered the
partition.

**Fixed: caching.** The repository now caches the master-file reads, with two TTLs
(`ajs.polling.inventory-seconds`, `live-seconds`). Measured with SQL statement
counts over a fixed seven-view sequence: **10 reads without the cache, 2 with it**.
It also collapses a duplicate that predated it — the failures page read the
completion code twice per view. See §7a for the method and the numbers.

One trap worth recording: `spring.cache.type=none` **does not disable this cache**.
`CacheConfig` declares a `@Primary` `CacheManager`, which wins over the
auto-configuration that property drives. Verified by running with the property set
and watching the cache keep serving. The switch that works is
`ajs.polling.cache-enabled=false`, which is tested.

**Not fixed, on purpose: there is still no poller.** That remains the right answer
for this application. A poller costs partition work when nobody is looking, which
is the one thing a read-only observer should never do. A TTL cache is
demand-driven and caps a burst, which is the actual failure mode. This diverges
from `req.txt` §6's "adaptive polling" wording deliberately, and §7a records why.

If a view is ever added that must be current *without* a user asking — a wall
display, an alert — then it becomes a real design problem and the shape is **one
scheduled poller with a cache and an adaptive interval, not per-request queries**.
Do not add polling per page.

### C7 — `LocalDate.now()` in domain code

**Low. Small effort.**

`AjsObservabilityService.overview()` and the controllers call `LocalDate.now()`
directly. `AjsDateDecoders.decodeYymmdd` already does the right thing, with a
package-visible variant taking an injected pivot so tests are deterministic. The
service layer does not follow that pattern, so date-relative behaviour is testable
only relative to "today". Worth injecting a `Clock` if such behaviour grows.

### C8 — the timeline could not be denied per user — FIXED

**Medium. Fixed. Small effort.**

The Timeline dashboard was added to the navigation after the permission model was
built, and was never registered as an app. Three separate places had to agree, and
all three were missing it:

1. `AuthBootstrap.BUILT_IN_APPS` — so no `applications` row existed to deny.
2. `AppPermissionInterceptor.ROUTE_TO_SLUG` — so no path mapped to a slug.
3. `WebMvcConfig`'s `addPathPatterns` — so the interceptor was not even *invoked*
   for `/timeline`.

Consequence: an administrator could not restrict the timeline for any user. The
admin UI offered no such row, and had one been inserted by hand the interceptor
would still never have run. It was the one dashboard whose permission control did
not exist — the same shape as C4, but caused by omission rather than by a missing
check.

Found while capturing the figures in §4a, when the screenshot tooling had to be
extended to cover the view and the registered-apps list did not contain it.
Verified end-to-end: with a deny row on `timeline`, an ordinary user gets **403**
and `/overview` still returns 200; an administrator gets 200, so it can be
re-enabled. The enforcement test fails when the route mapping is removed.

**Lesson:** three places encode "which routes are governed", and nothing kept them
in step. The test now covers the timeline specifically, but the structural fix would
be to derive the path patterns from the slug map rather than repeat them.

### What is good, and should not be "cleaned up"

Listed so a future refactor does not undo it:

- **Domain semantics in Java, not in templates.** The `*NOSBM` rule, never-run
  and held classification, and sentinel decoding are all in tested Java. This is
  why the timeline's outcome logic moved into `TimelineViewHelper` — and that move
  is what fixed the truncated-page bug.
- **`AjsSchemaValidator` reports drift rather than refusing to start**, by
  default. A monitoring tool that will not boot is worse than one that boots and
  shouts.
- **The read-only invariant is stated in three places** — interface contract,
  repository javadoc, and a test asserting no write reaches the table. The
  redundancy is deliberate.
- **Comments explain *why*, including rejected alternatives.** The `CMSEQ` vs
  `JMSEQ` note and the YYMMDD pivot reasoning are the most valuable documentation
  in the repo. The exception is C1, where a comment was confidently wrong — the
  lesson being that an unverified "why" is more dangerous than none.
- **The timeline renders without JavaScript.**

---

## 9. Bugs the offline tests caught

Recorded because each would have reached a stakeholder as a wrong dashboard.

1. **YYMMDD pivot was wrong.** A naive `yy <= currentYear % 100` rule turned a
   next-run date of `2026-01-01` into **1926-01-01**. See §5.
2. **CYYMMDD century flag misread in a test expectation.** `1991231` is
   `2099-12-31`, not 1999. Now asserted explicitly so nobody "fixes" it back.
3. **`QAIJS1CM` sequence confusion.** The command query filtered
   `CMSEQ = JMSEQ`. Those are unrelated: `JMSEQ` is the job's sequence, `CMSEQ` is
   each command's position. The command list returned 1 row instead of 2. Do not
   reintroduce that filter.
4. **CHAR columns are blank-padded.** `CMCMD` (`CHAR(200)`) came back padded to
   full width, which templates and CSV exports would have carried. Stripped in
   the repository.
5. **`QAIJSHST` was keyed so a job could only ever have one run.** The authored
   schema declared `PRIMARY KEY (HSJOB, HSSEQ)` — no date component — permitting
   exactly one history row per job for all time. The second run of any job
   collided: seeding one day of realistic history produced
   `Unique index or primary key violation ... (HSJOB, HSSEQ)`. The key is now
   `(HSJOB, HSSEQ, HSDTE, HSTME)`. **This is a guess about a file we cannot
   inspect** — see §9's open questions.
6. **A Thymeleaf parse error returned HTTP 200 with a truncated page.** A nested
   ternary in `th:classappend` failed to parse; because the response had begun
   streaming, the client received a half-rendered page — gutter rows present,
   every bar and the entire detail table missing. The MockMvc test passed anyway,
   because it only asserted strings appearing *before* the failure. Two fixes:
   the mapping moved into `TimelineViewHelper` (Thymeleaf's parser rejects the
   parenthesised nested conditional outright), and the test now asserts the
   template's *tail* so a mid-page failure cannot pass.
   **Lesson: on a server-rendered page, "assert something rendered" is not the
   same as "assert it finished rendering."**
7. **`!warning` on a `String`** — a compile error, noted only because the intent
   was a null check and the code said negation.

Two more were found later, while fixing the design review, and both are recorded
here because they share a lesson with bug 6 rather than with bugs 1-5:

8. **The CSRF token rendered correctly but no browser could ever submit a form.**
   Enabling CSRF with the default `HttpSessionCsrfTokenRepository` on a
   `STATELESS` filter chain left the token unverifiable: it renders into the HTML
   (so the page looks perfect) but is stored in a session that is never retained.
   Every POST would have 403ed for a real user. Fixed with
   `CookieCsrfTokenRepository`. **The test suite was green before and after** —
   a disabled filter and a working filter both let a POST succeed, and nothing
   asserted the failure case. Found by driving a real browser.
9. **A disabled app was hidden from the navigation but still reachable.**
   `AppPermissionInterceptor` checked the denylist and never `enabled`, so
   disabling an app in `/admin/apps` changed a boolean and a menu entry and
   nothing else: `GET /failures` still returned 200 for any signed-in user. Found
   by writing the test first; it reported `expected:<403> but was:<200>`.
10. **The timeline could not be denied per user at all.** It was never registered
    as a built-in app, was absent from the interceptor's route map, and was absent
    from `WebMvcConfig`'s path patterns — three independent omissions that all had
    to be fixed. An administrator could not restrict the view, and no UI row
    offered the option. Found while extending the screenshot tooling to cover the
    view. Verified: deny row → 403 for an ordinary user, 200 for an admin.

**The shared lesson, which is bug 6's lesson widened.** Bugs 1-5 were *wrong
answers* the tests could see, because something was asserted and the assertion was
false. Bugs 6, 8 and 9 were *unenforced controls* the tests could not see, because
a control that is switched off produces the same observable behaviour as one that
is switched on — a page that renders, a POST that succeeds. Green tests are not
evidence a gate is closed. Assert the rejection, and confirm the test fails when
the gate is removed.

### Two infrastructure traps worth remembering

- **`application-test.yml` in `src/test/resources` is invisible to `bootRun`.**
  Activating `--spring.profiles.active=test` from the main classpath finds no such
  file, silently falls back to the jt400 datasource, and dies on
  `UnknownHostException: ibmi-host`. Hence `application-local.yml` lives in
  `src/main/resources`, and the fixture SQL in `src/main/resources/fixture/` is
  shared by both offline profiles.
- **`testImplementation` dependencies are not on the `bootRun` classpath.** Hence
  the dedicated `localRuntimeOnly` configuration, which keeps H2 out of the
  production jar — verified: 0 H2 entries, 1 jt400 entry.

---

## 10. Safety measure beyond the requirements

`FixtureProfileGuard` refuses to start the application on the `local` or `test`
profile unless `AJS_ALLOW_FIXTURE_PROFILES=true` is set.

Rationale: the `local` profile shows **fabricated** job data, and an operator
believing invented scheduler state is a worse outcome than most bugs in this
codebase. Default-deny. The Gradle `test` task sets the opt-in; nothing in
`application.yml` does.

---

## 11. Can it define, trigger and schedule jobs?

Asked directly, so answered directly. **Yes, technically feasible — but it is a
different product with a different risk profile, and it is not a feature to bolt
onto this app.** This is open question 1 in `req.txt` §9, so it needs a decision
rather than an implementation.

### The capabilities are not equally hard

| Capability | Mechanism | Difficulty | Main obstacle |
| --- | --- | --- | --- |
| **Trigger** (run a job now) | `SBMJOB`, or `RLSJOBJS` | Low | Authority only |
| **Hold / release** | `HLDJOBJS` / `RLSJOBJS` via `CommandCall` | Low | Authority only |
| **Define** (create/change a job) | `ADDJOBJS` / `CHGJOBJS` | **High** | ~50 parameters, no transactional safety |
| **Schedule** (change when it runs) | `CHGJOBJS` SCDCDE | **High** | Same as define |

Trigger and hold/release are a real, small win. Define and schedule are where the
cost and the risk actually live.

### The blocking constraint is authority, not code

**AJS enforces its own authority model on top of IBM i object authority.** Having
`*USE` on the command is *not sufficient*. IBM documents this as message
`IJS1098`: a user with `*USE` to `ADDJOBJS` still gets
`Not authorized to function *ADDJOB`. The grant has to be made inside AJS's own
configuration:

```
GO JS  ->  Option 5 (System Controls)  ->  Option 4 (Work with Function Authorities)
       ->  Option 2 (Edit Function Authority)  ->  grant *USE to the *ADDJOB function
```

Consequences:

- **The read-only service profile cannot do any of this, by design.** Its value is
  that it has no `*JOBCTL` and no AJS function authority. Writing means a
  **second, privileged profile** with its own credential, rotation and audit story.
- That contradicts the posture the app encodes: "Dedicated read-only IBM i user
  profile (no `*ALLOBJ`, no `*JOBCTL`)" (`req.txt` §6).
- `ADDJOBJS`, `CHGJOBJS`, `HLDJOBJS` and `RLSJOBJS` are all **`Threadsafe: No`**,
  so `CommandCall` invocations are serialised. A naive "loop over jobs and hold
  them" will interleave badly under concurrency; it needs an explicit
  single-threaded command queue.
- `*JOBCTL` may also be required for commands acting on other users' jobs.
  Confirm per command on the actual release — treat as unknown.

### Define is not more fields, it is a different problem

`ADDJOBJS` takes on the order of fifty parameters (schedule code `SCDCDE` with
its `*DAILY`/`*CALENDAR`/`*MINUTE`/`*MONTHEND` variants, calendars, job queue,
job description, run-as profile, message queue, recovery action, command
sequences...). Two specific hazards:

1. **No transactional safety.** There is no preview and no rollback. A half-valid
   definition is accepted field-by-field; a wrong calendar silently schedules a
   job on the wrong days and nobody notices until it fails to run. That is
   strictly worse than a validation error, being a silent wrong answer rather
   than a loud one.
2. **Command-sequence injection.** A job's command text is operator-supplied CL.
   A web form accepting a command sequence is a remote-code-execution surface on
   the production partition. This needs allowlisting and review, not escaping.

### Two tempting shortcuts that do not work

- **Writing rows directly to `QAIJSMST`.** AJS is a licensed product with its own
  internal consistency (sequence numbers, calendars, dependencies). Writing its
  tables behind its back is unsupported and corrupts state in ways `DSPFFD` will
  not warn about. Do not.
- **Driving `WRKJOBJS` by screen-scraping.** It is
  `Where allowed to run: Interactive environments (*INTERACT *IPGM *IREXX *EXEC)`
  and `Threadsafe: No` — a panel, not an API — and `CommandCall` explicitly does
  not support interactive commands. There is no supported non-interactive path
  through the panels.

### Recommendation

**Keep the observability app read-only.** In order of weight:

1. This is a v1 monitoring tool whose entire trust story is "it cannot break
   production". Write authority destroys that, and the value of the write
   features is far smaller than the value of the guarantee.
2. The write path needs a second privileged profile and a new audit surface —
   Phase 3 hardening work, not a feature branch.
3. **Trigger and hold/release are the only writes with a good risk-to-value
   ratio** — bounded, reversible and easy to audit. If any write capability is
   wanted, do that one, behind explicit confirmation and an append-only audit log
   recording who, what, when and on which partition.
4. **Define and schedule should not go into a web app.** If genuinely needed, the
   safer shape is reviewed, version-controlled CL scripts or a request/approval
   workflow — a human approves and the change is source-controlled — not a form
   calling `ADDJOBJS` directly.

If this changes, it changes as a deliberate Phase 4/5 project with its own
security review. It should not be added opportunistically to `/jobs`.

If write support is ever added, it should arrive as a **separate interface with
its own datasource and profile**, so the read-only path cannot be widened by
accident. The current invariants are the right ones: `AjsRepository`'s contract
says every method is read-only, `JdbcAjsRepository`'s javadoc says every statement
is a `SELECT`, and a test asserts no write reaches the fixture.

### Sources

- AJS function authority / `IJS1098`:
  https://www.ibm.com/support/pages/message-ijs1098-advanced-job-scheduler
- `WRKJOBJS` environments and threadsafety:
  https://www.ibm.com/docs/en/i/7.4.0?topic=w-work-jobs-using-advanced-job-scheduler
- jt400 `CommandCall` (non-interactive commands only):
  https://javadoc.io/static/net.sf.jt400/jt400-jdk8/11.1/com/ibm/as400/access/CommandCall.html
- `HLDJOBJS`/`RLSJOBJS` as the hold/release pair:
  https://www.ibm.com/support/pages/scheduling-advanced-job-scheduler-job-reset-another-advanced-job-scheduler-job

---

## 12. Risks and open questions

| Risk | Description |
|------|-------------|
| R1 | Schema drift — AJS physical files are not a published contract |
| R2 | Legacy date encodings and sentinels, per release |
| R3 | Privileged monitoring account |
| R4 | "Running now" is not in the AJS master file |
| R5 | DRDA may be firewalled |
| R6 | Polling load on the partition — *no poller exists by design; a TTL cache bounds bursts. Measured in §7a, with the checks that still need a host.* |
| R7 | AJS licensing is separate from `QUSRIJS` access |
| R8 | Multi-partition drift |

**R1 now has a concrete open question** raised by the timeline work: the key and
granularity of `QAIJSHST`. §12b is the plan for settling it without the host, and
the script for settling it with one. The fixture assumes `(HSJOB, HSSEQ, HSDTE, HSTME)` with
one row per run; the original key `(HSJOB, HSSEQ)` could not hold history at all.
If the real file is keyed differently, or holds one row per *step* rather than per
run, the timeline's bars would be wrong in a way tests against H2 can never
reveal. One `DSPFFD` retires this.

### To settle on a real partition, before Phase 1 exits

§12b turns this list into a scripted capture session, because the host is on an
internal network and access is scarce. The five questions below are the ones
worth spending it on.

1. `DSPFFD QUSRIJS/QAIJSMST`, `QAIJSHST`, `QAIJS1CM` — diff against
   `src/main/resources/fixture/schema-ajs.sql`. **This diff retires risk R1**, the
   largest assumption in the project.
2. Confirm real `JMDTE`/`JMLRD` encodings and the `9999999` / `0` sentinels.
3. Confirm `QSYS2.ACTIVE_JOB_INFO` exists at the installed PTF level (R4).
4. Confirm the jt400 DRDA path end to end connects (R5).
5. **If write actions are ever in scope:** confirm which AJS function authorities
   are needed, and whether `*JOBCTL` is required, for
   `HLDJOBJS`/`RLSJOBJS`/`SBMJOB`. `*USE` on the command alone is provably not
   enough (message `IJS1098`).

Run the app with `ajs.schema-validation.enabled=true` against that partition — it
logs exactly which expected columns are missing.

Also unresolved, from `req.txt` §9: multi-partition support is not designed for
(q3), and neither SSO/Kerberos nor local accounts has been decided (q8).

---

## 12a. Honest critique, and the path forward

§8 reviewed the design as it stood and found real defects. This section is a
different question, asked later and answered less comfortably: **what is still
wrong with this project as a deliverable, and what should happen next?** Nothing
here is a defect in the sense of C1–C8; most of it is the gap between "a
well-argued codebase" and "something an operations team can run".

### What I would say if asked to review it cold

**The engineering is better than the product's evidence base.** The invariants
are real (read-only, two datasources, offline test tier), the bugs in §9 are the
kind a careful team finds, and the security findings in §8 were found by
insisting on evidence rather than by accident. But the entire AJS layer rests on
an **authored** schema (§4), and the fixture's data model is one guess deep: the
`QAIJSHST` key is invented, and the timeline's correctness depends on it. That is
the single largest piece of unearned confidence in the repo, and no amount of
local testing can retire it.

**The auth store is the least finished part of the system, and it is the part
that faces the network.** The AJS side is read-only, so its worst failure is a
wrong number on a screen. The auth side writes — users, roles, permissions — and
it has no migrations, no audit trail (C3), no session revocation, and a
bootstrap administrator whose password may be sitting in a log. Security was
treated as a set of properties to verify rather than as a lifecycle to operate,
and the gap shows at exactly the boundaries an operator would hit first.

**There is no way to change a password.** `UserRepository.updatePasswordHash`
exists and has no caller — no controller, no form, no admin action. The bootstrap
log message says "Sign in and change it", and there is nowhere to do that. The
only remedies today are deleting `data/auth.db` (losing every user) or editing
the SQLite file by hand. That is not a missing nicety; for an account whose
password was printed to a log, it is the difference between a control and a
ritual. It is also the clearest example of the pattern in this section: the
hard part (bcrypt, JWT, CSRF, the denylist) is done and tested, and the mundane
part that makes it usable was never finished.

**The "no IBM i" story is good and the "first IBM i" story is not.** The local
profile is genuinely well built — guard, fixture, one command. What is missing is
the transition: there is no readiness gate that says "this build may not be
pointed at production until `DSPFFD` has confirmed the layouts", and the drift
checker warns rather than blocks by default. A tool whose value proposition is
"trust this view of production" should be strict about the one thing that
determines whether the view is correct.

**The real constraint is access, not effort.** The IBM i sits on an internal
network and is not reachable from where this was built. That is not a scheduling
inconvenience — it means the entire AJS layer has only ever been exercised
against H2, and no amount of local work can substitute for one connection. So the
honest recommendation is not "build more offline confidence"; it is "spend the
access we do not have on the fewest possible questions". §12b lists exactly which
questions those are, and what can be built in the meantime without guessing.

**The documentation is now proportionate, but it is one 1,400-line document.**
Merging the illustrated walkthrough into `NOTES.md` removed the drift problem
and made the figures sit next to the claims they support. It also made the file
long enough that a new reader will not read it. The numbered sections help; a
reader who only wants "how do I run this" needs §2 and nothing else.

### Where the risk actually is, ranked

1. **R1 — the schema is a guess.** Everything downstream is a plausible
   elaboration of an unverified assumption. One `DSPFFD` retires it, and the
   highest-value hour in this project is still an hour on a real partition.
2. **The auth store's operational surface.** No way to change a password, no
   migration path, no audit log, no way to revoke a session, and a bootstrap
   password that may reach a log file. Cheap to fix, and the fixes are boring —
   which is why they get deferred.
3. **A false all-clear that the bounds do not prevent.** The window and per-lane
   caps report truncation honestly, but they still *bound* what an operator sees.
   An operator who reads "1 abnormal" from a capped summary is reading a true
   number about a deliberately limited window. That is defensible, but it is a
   design decision resting on the operator noticing the notice.
4. **The unexercised DRDA path (R5).** The driver is on the classpath and the URL
   is right; nothing has ever opened a socket to a real host server from this
   application. "It compiled and the tests pass" is not evidence here.
5. **C3, C5/C6, C7.** Real, small, and genuinely low priority next to the above.

### The path forward

In order. Each step is chosen because it either retires an assumption or removes
a class of failure — not because it is interesting.

**Phase 0 — one afternoon, one partition.** The IBM i is on an internal network
and not reachable from here, so this is the one phase that cannot be done by
anyone with only this repository. It needs read-only credentials and one session:
`DSPFFD` on `QAIJSMST`, `QAIJSHST` and `QAIJS1CM`, diff against
`src/main/resources/fixture/schema-ajs.sql`, then run the app with
`ajs.schema-validation.enabled=true` and `fail-on-missing-columns=true`. This
retires R1, confirms R2's encodings against real data, and — the part that is
easy to skip — **exercises the DRDA path for the first time** (R5). If the
`QAIJSHST` key differs from the fixture's, the timeline's geometry needs a
re-think before anything else is built on it. Do this before Phase 1 exits.

Because that session is scarce, it should be prepared as a single scripted run
rather than an exploration: §12b is the checklist, and the capture format matters
more than the conclusions.

**Phase 1 — make the auth store operable.** In order of effort, not importance:
wire up a change-password form (the repository method already exists and is
unused, so this is a controller and a template, not a design); log admin
mutations (C3, ~20 lines); add a schema migration path so the SQLite store can
change without a manual `ALTER` (currently the schema is `CREATE TABLE IF NOT
EXISTS` on every start, which silently cannot evolve); add a "revoke all
sessions" action so a leaked token can be invalidated before its 24 hours
expire; and stop the bootstrap password from being the only credential in the
logs — require `AUTH_BOOTSTRAP_ADMIN_PASSWORD` in any environment whose logs are
retained. Audit tables are Phase 3; a log line is not.

**Phase 2 — one cached snapshot per request (C5/C6).** Not for speed; for
predictability. A single `findAllJobs()` per request, with an adaptive interval,
is the prerequisite for any live-update work, and it is also what makes the
polling load argument (R6) answerable with a number instead of a shrug.

**Phase 3 — decide, do not build.** `req.txt` §9 question 1 (write support) is
still open. §11 argues to keep the app read-only; if that decision is taken, say
so in the document and close the question. If write support is wanted, it is a
separate product with a privileged profile and its own review — not a branch on
this one.

**Deliberately not on the list:** a REST/JSON API (nothing needs one yet), a
SPA (the server-rendered pages are the reason the timeline works without
JavaScript), multi-partition support (undesigned, and the drift risk multiplies),
and Docker (Db2 LUW is not DB2 for i, so a container validates nothing about the
host path).

### What I would not change

The read-only invariant, the repository interface with two implementations, the
domain rules in tested Java rather than in templates, the honest-truncation
design in the timeline, and the default-deny fixture guard. Those are the five
decisions that make the rest of this document defensible, and a refactor that
touches any of them should be argued for rather than assumed.

---

## 12b. Working without the host: what H2 can and cannot prove

The IBM i is on an internal network and is not reachable from the machine this
was built on. The tempting response is "so build a better mock". That is the
wrong instinct, and it is worth being precise about why, because the difference
between a mock that earns its keep and one that quietly fabricates confidence is
the whole subject of this section.

### The layer already in place, and what it is actually doing

The seam is not new. `AjsRepository` is an interface with the JDBC
implementation behind it, and the `local`/`test` profiles swap in H2 in
`MODE=DB2` with an authored schema. That is already the "mock layer", and it is
better than most, for three specific reasons:

- **It exercises the real SQL**, not a Java stub. `JdbcAjsRepository` issues the
  same statements against H2 as against `QUSRIJS`, so the query *shape* — the
  `HSDTE BETWEEN` predicate, the `ORDER BY`, the `QAIJS1CM` join, the trimming
  of `CHAR` padding — is genuinely tested. A Mockito stub would have proven none
  of that.
- **It exercises the real templates and the real filter chain**, over MockMvc,
  and the smoke-test rows in §4 were driven against the running `local` profile.
- **It is honest about its own limits.** `JdbcAjsRepositoryTest` says in its
  class javadoc that the schema is authored and not extracted. That sentence is
  doing more work than any test in the file.

What it cannot prove is a short list, and every item on it is a property of the
**host**, not of the application:

| Cannot prove offline | Why not | What would prove it |
| --- | --- | --- |
| The real column set of `QAIJSMST`/`QAIJSHST`/`QAIJS1CM` | H2 is running a schema we wrote | `docs/host-capture/01-columns.sql` + `diff-against-fixture.sh` |
| The real key of `QAIJSHST` | the fixture's key is invented | `docs/host-capture/02-keys.sql` |
| Real `CYYMMDD`/`YYMMDD` encodings and sentinels per release | our decoder assumes a convention | `docs/host-capture/03-date-encodings.sql` |
| That DRDA/host server works end to end | H2 is a local file | `docs/host-capture/05-drda.sql` — **partly answered against PUB400, see below** |
| jt400's own type mapping (packed decimal, `CHAR` padding) | H2's `INTEGER`/`CHAR` are not DB2 for i's | real rows through the real driver |
| Authority behaviour (`*USE`, AJS function authority) | no authority model offline | a real profile |
| Performance on a real history file | 20 fixture rows | a real partition, or a copy |

Note what is *not* on that list: the domain logic, the `*NOSBM` rule, the
timeline geometry, the permission model, CSRF, the denylist, the truncation
honesty. Those are all app-side and are genuinely tested. The offline suite is
not weak because it is offline; it is weak in exactly one place, and that place
is the host's schema.

### A public host: PUB400, and what it actually settles

There is one free, public IBM i: **PUB400.com** (RZKH GmbH, IBM i 7.5, free
`*PGMR` profile, 1 GB). It is worth being precise about what it does and does not
answer here, because "just test against PUB400" is the obvious suggestion and it
is only partly right.

**What it settles — measured, not assumed.** From this machine:

| Probe | Result |
| --- | --- |
| TCP to `pub400.com:8471` (as-database / DRDA) | **open** |
| TCP to `pub400.com:446` (older DRDA) | open |
| jt400 21.0.7 signon against it | completed a full DRDA exchange in ~0.8 s |
| The rejection | `SQLState 08004`, "Password is incorrect" for the user we sent |

That last row is the useful one. A firewall or network fault fails differently —
timeout, connection refused, or a dropped socket. Getting a *protocol-level
authentication rejection* means the driver, the port, the host server and the
signon exchange all worked. **R5's network question is therefore answered: DRDA
from an ordinary internet-connected machine to a real IBM i works, and the
application's driver stack is not the problem.** That is genuinely valuable and
cost nothing.

**What it does not settle.** PUB400 is a real IBM i, but it is not *our* IBM i,
and it almost certainly lacks the two things that matter most:

- **AJS itself.** Advanced Job Scheduler is licensed program 5770-JS1 (no charge
  on current releases, but still a licensed program that has to be installed).
  PUB400's published description is a general-purpose playground — RPG, COBOL,
  CL, SQL — and says nothing about AJS. Without 5770-JS1 there is no `QUSRIJS`
  library and no `QAIJSMST`/`QAIJSHST`/`QAIJS1CM`, so **R1 cannot be retired
  there.** That is the whole reason we care.
- **Our authority model.** PUB400 grants `*PGMR` and explicitly refuses anything
  higher. That cannot reproduce the read-only service profile's authority
  behaviour (R3), which is a property of *our* profile, not of IBM i.

So PUB400 is a good answer to "can this driver talk to a real host at all"
(the answer is yes, and we now know it) and a poor answer to "what do the AJS
files actually look like". If AJS turns out to be installed there — worth asking
the operators, or checking `DSPSFWRSC` for `5770JS1` after signing up — then it
becomes the best available substitute for the internal partition, and
`docs/host-capture/` runs against it unchanged. If it is not installed, the
internal network remains the only way to retire R1.

One caveat if you do sign up: PUB400 is a shared public system. Do not run the
capture script's sample-row queries against other users' data, and do not point
the application at it with anything but a throwaway account. The read-only
posture is still the right one, and a public playground is not a partition whose
scheduler state anyone should be drawing conclusions from.

### Where more mocking would actively hurt

Three tempting upgrades, each of which would make the repo look better and the
evidence worse:

1. **A hand-written fake `AjsRepository` returning canned `AjsJob` records.**
   This is the one to avoid. It bypasses the SQL, the row mapper and the decoder
   — precisely the three things most likely to be wrong about a real host — and
   replaces them with Java objects that are correct by construction. Every test
   against it would pass on a partition where the app returns HTTP 500. The
   existing H2 implementation is strictly better for the same effort.
2. **Recorded fixtures from a real host, captured as SQL seed rows.** This is the
   right idea *if the capture is real* — and it is the best thing that can come
   out of the Phase 0 session. It is the wrong idea if the rows are invented to
   look like a capture. The distinction must be written down in the file, or a
   future reader cannot tell an extraction from a fabrication. §12a's Phase 0
   should produce exactly this: a `schema-ajs-real.sql` and `data-ajs-real.sql`
   from `DSPFFD` and a few `SELECT`s, committed beside the authored fixture, with
   the tests parameterised over which one is in use.
3. **A DB2-for-i container to "test against".** Db2 LUW is not DB2 for i, and it
   does not exercise the jt400 DRDA path at all. It would add setup cost and
   validate nothing that H2 does not already validate.

### What to do with the access we do not have

Ordered by value per unit of guesswork. The first three need no host.

**1. Make the schema assumption visible instead of implicit. — DONE.**
`FixtureSchemaConsistencyTest` reads the column lists from the mappers' own
`SELECT_COLUMNS` constants and from `AjsSchemaValidator`'s required sets, and
asserts the fixture provides every one of them. It also pins the columns the
`CMJOB = JMJOB` join and the invented `QAIJSHST` key depend on. Verified the way
§9 says to verify a gate: deleting a column from the fixture makes all four tests
fail. This does not prove the *real* files have these columns — only `DSPFFD` can —
but it stops the fixture and the code drifting apart while nobody is looking.
*(Built without a host.)*

**2. Turn the readiness gate into code. — DONE.**
`src/main/resources/application-ibmi.yml` is the first-connection profile: it sets
`ajs.schema-validation.fail-on-missing-columns=true`, so drift is a failed start
with a precise list of missing columns rather than an empty dashboard. The default
profile still warns and starts, which is right for a host already inspected and
wrong for the first look. Verified against no host at all: `ibmi` exits with
`AJS schema drift detected ... QAIJSMST unreadable (The application requester
cannot establish the connection. (ibmi-host))`. *(Built without a host.)*

**3. Write the Phase 0 capture script now, so the scarce session is scripted. — DONE.**
`docs/host-capture/` holds five SQL files — columns, keys, date encodings, sample
rows, DRDA — a `README.md` with the run order and what to save, and
`diff-against-fixture.sh`, which compares a capture against the authored fixture
and reports both directions of disagreement. The diff script was rehearsed against
a synthetic capture built from the fixture (reports "subset", correct) and against
one with a column removed and another added (reports the exact mismatch, correct).
The session is now execute-and-save rather than explore-and-conclude.
*(Built without a host; running it needs one.)*

**4. Improve the offline suite's weakest real gap: the row mapper against real
types.** The mapper reads `JMDTE` as an `Integer` and `JMJOB` as a padded
`CHAR`. On a real host these may arrive as `BigDecimal` or a differently padded
string. A unit test that feeds the mapper the *types* jt400 returns — as recorded
from the driver's metadata, once available — closes a gap H2 cannot. *(Needs one
host read to be accurate; until then, this is a known unknown, not a task.)*

**5. Keep the fixture honest and keep saying so.** The `local` profile's
banner, the `FixtureProfileGuard`, the authored-schema javadoc and §4's "What
local testing cannot cover" are all doing the same job: making it impossible to
mistake the fixture for the host. That redundancy is the correct response to
having no host, and it should not be simplified away.

### The recommendation, stated plainly

**Do not build a bigger mock. The gate, the capture script and the honest labels
are built; spend the first host session on schema and encodings rather than on
features.**

Concretely: items 1–3 above are done and host-free, so the offline work left is
Phase 1 (auth operability, §12a). After that, stop adding to the AJS layer until
one read-only session on the internal network has produced `DSPFFD` output and a
handful of real rows. `docs/host-capture/` is that session, written in advance.
Anything built on top of the AJS layer before that session is an elaboration of
an assumption, and the further it goes the more expensive the eventual correction
becomes.

If the internal network can be reached through a jump host or a VPN, the single
highest-value thing this project can do is spend one hour there. If it cannot, try
PUB400 first (§ above): it retires the DRDA question for free, and if AJS happens
to be installed there it retires much more. If it cannot be
reached at all, then the correct position is the one this document already
takes: the app is a well-tested elaboration of a documented schema, it says so,
and it should not be deployed against a partition whose layout has never been
inspected.

---

## 12c. Functionality worth building — recommendations, ranked

Nothing below is implemented. This section exists because the obvious next
question after §12b is "then what *would* be worth building?", and the honest
answer is narrower than a feature list. Every item here is grounded in data the
application already reads or in services that need no new authority — so none of
them reopens §11's read-only decision. They are ranked by value per unit of
guesswork, not by interest.

The test each item had to pass to appear here: **does it answer an operator
question that today requires a 5250 session, using data we can already reach?**
Ideas that need a second privileged profile (`HLDJOBJS`, `SBMJOB`) are §11's
argument, not this one, and are excluded.

| # | Recommendation | Answers | New host dependency | Blocked by |
| --- | --- | --- | --- | --- |
| 1 | **View the job log for a run** | "*why* did it fail?" | `QSYS2.JOB_INFO` / `JOBLOG_INFO` | R1, authority |
| 2 | **Run history on the job page** | "has this been getting worse?" | none — `QAIJSHST` already read | C5/C6 |
| 3 | **Failure-to-cause link** | "show me the error, not the status" | same as #1 | #1, R1 |
| 4 | **Saved/shareable window state on the timeline** | "send this view to a colleague" | none | none |
| 5 | **Export a view to CSV** | "put this in a report" | none | C5/C6 |
| 6 | **Per-job expected-runtime baseline** | "is this run unusually slow?" | none — historical `HSELAP` | data volume |
| 7 | **Live refresh of the timeline** | "is it running *now*?" | none | C5/C6, R6 |
| 8 | **Scheduled digest / threshold alert** | "tell me without me looking" | none | #2, and an outbound channel |

**Rerun is deliberately absent.** "Rerun a job" is the one recommendation an
operator will ask for first, and it is the one this document most firmly
declines: rerunning is `SBMJOB`/`RLSJOBJS`, which is a **write** to production,
needs a privileged profile, a confirmation step and an append-only audit log —
§11's whole argument, not a convenience feature. If it is ever built it arrives
as the separate write service §11 describes, with its own credentials, never as
a button on `/failures`.

### 1. View the job log for a run — the highest-value gap

The application can tell an operator that a job ended abnormally and cannot tell
them why. That is the single most common reason to open a 5250 session, and it is
the gap that most reduces the app's usefulness. AJS's own files do not hold the
job log; the IBM i does, through SQL services that are **read-only and need no
`*JOBCTL`**:

- `QSYS2.JOB_INFO` — resolve an AJS run to a system job (name, user, number,
  qualified job name) using the job's start time and run-as profile.
- `QSYS2.JOBLOG_INFO` — the messages for that qualified job name, in order.

This is the item with the most value and the most unearned confidence, and the
two are the same fact: it depends on **correlating an AJS `QAIJSHST` row with a
system job**, and that correlation key is not in the authored schema. It is R1
again, one layer deeper. Before building it, the Phase 0 session must also
answer: *given a `QAIJSHST` row, how do we identify the system job it produced?*
If the answer is "we cannot, reliably", the feature is not buildable as stated,
and a "show recent messages for this job name's runs" approximation should be
labelled as such rather than presented as the log of that run.

Two design constraints, both consequences of the read-only posture:

- **It must render as escaped text, never as HTML.** Job logs contain
  operator-supplied message text and command strings. Escaping is not optional;
  see §11's note on command-sequence injection for why this class of content is
  treated as hostile.
- **It must page or bound.** `JOBLOG_INFO` on a long-running job can return
  thousands of rows. The timeline's honest-truncation discipline (§7) is the
  precedent: bound the response and say so in the UI.

### 2. Run history on the job page — the cheapest real win

`/jobs/{name}` shows the last run as four scalar fields. `QAIJSHST` already holds
every run and the repository already reads it for the timeline; the job page just
never asks. A last-N-runs table — start, outcome, elapsed, and a link to the gap
in the timeline — answers "is this job degrading?" and "when did it last
succeed?" without touching a new table, a new query shape, or the host beyond
what the timeline already reads.

This is the recommendation to do first if Phase 0 is delayed, precisely because
it adds no new schema assumption. It should fetch through the same per-request
snapshot as the rest of the page (§4's C5/C6 work), not with a second
`findRunsBetween` per render.

### 3. Failure-to-cause link

`/failures` lists abnormal completions; #1 supplies the log. The feature is
joining them: each failure row links to its run's log, and the overview's
"genuine failures" counter links to the rows behind it. Small, and it is what
turns two working views into one workflow. It inherits every constraint of #1.

### 4. Saved and shareable window state on the timeline

The timeline already renders from URL-only state (`from`, `to`, `scale`) — that
is stated in §13's live-updates note and it is the whole reason this is cheap.
What is missing is the operator conveniences around it: named windows ("last
night", "month end"), and the badge counts copied into a permalink so a pasted
URL shows the same thing the sender saw. No query changes, no schema risk, and it
makes the existing view reachable in the situations where it is actually used.

### 5. Export a view to CSV

Operators paste dashboard contents into incident reports and spreadsheets today
by hand. A read-only CSV of the rows already on screen — failures, jobs due, a
timeline lane set — is small, and it respects the read-only posture because it
reads exactly what the page reads. The entity-tagging of the response matters
(§7): a truncated export must say it is truncated, in the file, not only on
screen. Treat the export as the same query as the view, not a second one.

### 6. Per-job expected-runtime baseline

"This run took 40 minutes" means nothing without "and it usually takes 6". The
elapsed times are already in `QAIJSHST` and already decoded. A per-job median or
p90 over the same window the timeline uses would let `/timeline` and the run
history mark a slow run, and would eventually give the digest (#8) something
worth alerting on. **Do not build this before the host session**: the baseline is
only meaningful over real history, and computing it over fabricated fixture rows
would produce a number that looks like evidence and is not. It is listed here so
it is not reinvented as a "nice idea" later on top of a guessed schema.

### 7. Live refresh of the timeline

Already argued in §13, and listed here for completeness rather than as new
advice: the mechanism is a page timer reading one cached snapshot (C5/C6), not a
per-tab poll against DRDA (R6). The look transfers from the sssf visualizer; the
poll target does not. The fixed-window caveat in §13 stands — a refresh must not
move the bars under the reader.

### 8. Scheduled digest or threshold alert

"Tell me without me looking" is the natural endpoint, and it is the only item
here that leaves the application. It depends on #2 and #6 for its content and on
some outbound channel (mail, a webhook, an existing notification system) for its
delivery — and that channel is an operational decision, not a code decision. Its
one hard requirement is that an alert be *honest about its window*: "1 abnormal"
from a capped or truncated scan is a true number about a limited window (§12a,
risk 3), and an alert that omits the bound is worse than no alert, because it is
read at face value.

### What is not on this list, and why

- **Rerun / hold / release / submit** — writes. §11. Not a feature, a second
  product with a second profile.
- **An editing UI for AJS job definitions** — §11's define discussion: ~50
  parameters, no transactional safety, and a command-sequence field that is a
  remote-code-execution surface on production.
- **A REST/JSON API** — nothing consumes one yet; the server-rendered pages are
  what makes the timeline work without JavaScript. §12a already excludes it.
- **Multi-partition support** — undesigned, and it multiplies R1 by the number of
  partitions while the first one is still unverified.
- **A real-time active-job view from `ACTIVE_JOB_INFO`** — attractive, and a
  different product: it answers "what is the system doing now", not "what did
  the scheduler do". §12b's "what this session cannot do" applies — it is a
  reading of live state, and it needs the host before it needs code.

### The recommendation, stated plainly

**Do #2 now, because it assumes nothing new. Build #1 next, but only against a
real partition and only after the correlation key is known. Treat #4 and #5 as
the same afternoon. Leave #6, #7 and #8 until there is real history to make them
meaningful, and leave rerun to §11's decision.**

The ordering is the point: the items that add value without adding a schema
assumption come first, and the item everyone wants most (#1, and its cousin
rerun) is the one that most depends on the host nobody can reach yet.

---

## 13. Hand-off

If you are picking this up cold, read this document in order, then `req.txt` for
the original requirements. §4a is the illustrated walkthrough of the running
application — real captures of real pages, generated by `docs/tools/` from real
HTTP responses. §12b is the one to read before adding anything to the AJS layer:
it says what the offline suite can and cannot prove, and why the host is the only
thing that can close the gap.

### Invariants to preserve

- **Nothing in this application writes to the IBM i.** Every repository statement
  is a `SELECT`. See §11 before considering otherwise.
- **`AjsRepository` stays an interface with two implementations.** That split is
  the entire offline test strategy, because jt400 only speaks `jdbc:as400:`.
- **Legacy date arithmetic lives only in `AjsDateDecoders`.** Risk R2's
  containment boundary.
- **Domain rules belong in Java, not in Thymeleaf expressions.** Template
  expressions are untestable and, as §9 bug 6 showed, can fail in ways that return
  HTTP 200 with a partial page.
- **`AjsSchemaValidator` reports drift rather than refusing to start**, by default.

### Landmines

- **A Thymeleaf parse error returns HTTP 200 with a truncated page**, because the
  response streams before it fails. Assert the template's *tail* renders
  (`</html>`), not just that an expected string appears.
- **Multi-line expressions in `th:*` attributes fail to parse.** Keep them on one
  line.
- **`application-test.yml` in `src/test/resources` is invisible to `bootRun`.**
- **`testImplementation` deps are not on the `bootRun` classpath.**
- **`setSecure(false)` on the session cookie must be flipped to `true`** behind
  HTTPS.
- **There is no change-password form.** `UserRepository.updatePasswordHash` is
  implemented and never called. The bootstrap WARN message tells the operator to
  change the password; nothing in the UI can. §12a lists this first in Phase 1.
- **CSRF uses `CookieCsrfTokenRepository`, deliberately.** Switching to the default
  repository silently breaks every form on a stateless chain *while still rendering
  a valid-looking token*. If you touch the CSRF config, verify with a real browser,
  not with MockMvc — see C1 for why the harness cannot see this.
- **A green test suite does not mean a control is enforced.** C1, C4 and C8 were
  all "configured but not effective" defects that the suite passed through. When
  adding a gate, assert the *rejection* case, and prove the test fails when the
  gate is removed.
- **Adding a dashboard means editing three places, and all three fail silently.**
  `AuthBootstrap.BUILT_IN_APPS` (the app row), `AppPermissionInterceptor.ROUTE_TO_SLUG`
  (the slug), and `WebMvcConfig.addPathPatterns` (the interceptor's path). Miss the
  third and the interceptor never runs, so the page is ungoverned while looking
  perfectly normal. The timeline was in that state (C8).
- **`background-attachment: fixed` breaks `fullPage` screenshots.** The theme uses
  it for the aurora background; a full-page capture renders it as a solid band.
  Capture the timeline with a tall viewport instead.

### First three things I would do

C1, C2, C4 and C8 are now done. The sequenced plan — including the auth-store
work and the "first real partition" gate — is §12a; this is the short version:

1. **Run `DSPFFD`** and retire R1. Everything about the data model rests on an
   authored schema, and C2's date-range predicate now depends on that schema's
   assumptions as well. This is the highest-value remaining action, and it is
   offline-incompatible: it needs a real partition. It is also the first time the
   DRDA path is exercised at all (R5).
2. **Fix C3** — log admin mutations (actor, action, target, outcome). About twenty
   lines, and the application's whole proposition is "trust this view of
   production". Pair it with a migration path for the SQLite store, which
   currently cannot evolve without a manual `ALTER`.
3. **Fix C5/C6** — per-request caching of the master file. The page bound is now in
   place, but a single render can still issue several full `findAllJobs()` reads;
   the real fix is one cached snapshot per request, not per-method tuning.

`req.txt` §9 question 1 — whether write support is ever wanted — still needs a
decision rather than an implementation; §11 argues for keeping the app read-only.

What to build once Phase 0 is done, and in what order — job logs, run history per
job, CSV export and the rest — is §12c, with the same host-dependency caveat
applied to each recommendation.

### Live updates (asked, not yet built)

The timeline does not refresh without a page reload. The question was raised and
the answer is deliberately "not yet, and not as a per-page timer":

- **The look transfers; the mechanism does not.** The sssf visualizer polls with a
  cursor against a local database. This app's equivalent poll would hit IBM i over
  DRDA on every tick, per open tab — precisely the load risk R6 constrains.
- **C6 is the prerequisite.** Once there is one cached snapshot with an adaptive
  interval, a page timer can read the cache instead of the partition. Doing it
  before that bakes in the anti-pattern.
- **A partial swap is cheap and safe.** `/timeline` already renders from a URL-only
  state (`from`, `to`, `scale`), so the chart region could be lifted into a
  Thymeleaf fragment and re-fetched on window/scale change with no extra queries.
- **Beware the moving chart.** Auto-refresh on a relative window moves the bars
  under the reader. Any refresh must hold the window fixed, or a run that completed
  two minutes ago silently shifts position.

### Regenerating the screenshots in §4a

The figures are generated, not hand-drawn. See `docs/tools/README.md` for the
capture pipeline. The timeline figure (`17-timeline.png`) is captured with a
**tall viewport**, not `fullPage`: the shared theme's
`background-attachment: fixed` renders as a solid band in a full-page capture,
which silently produced an unusable figure on the first attempt. If you add a
figure, check the image, not just the exit code.

---

## Licence

No licence file is included, which means all rights are reserved by default. Add
one before accepting external contributions.
