# AJS Observability — design, decisions and handoff

A read-only web dashboard for **IBM Advanced Job Scheduler (AJS)** on IBM i.

It answers the questions a 5250 session makes tedious: what is due today, what
failed, what is held or never ran, and — via the timeline — when jobs actually
ran and how long each took.

This is the only prose document in the repository. It is written as a single
narrative on purpose: the project's history is a sequence of decisions, and the
order in which they were made is usually the fastest way to understand why the
code looks the way it does. `req.txt` holds the original requirements, including
the questions that are still open.

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

Data comes from the AJS physical files — `QAIJSMST` (definitions), `QAIJSHST`
(run history), `QAIJS1CM` (command lists) — read over the jt400 JDBC driver.
Nothing is installed on the IBM i; these are the same tables the 5250 panels
read. No REST/JSON API is assumed from AJS, and no SPA is involved.

---

## 2. Running it

```bash
# Full test suite — offline, no IBM i, no Docker.
./gradlew test

# Run the real application against fabricated data (H2), for UI work and demos.
./gradlew bootRun --args='--spring.profiles.active=local'
#   bootstrap admin: admin@localhost / localdev1234   (local profile only)
# then browse http://localhost:8080

# Production build. H2 is NOT included; jt400 IS.
./gradlew build

# Production runs need a real secret, or JwtService refuses to start:
export JWT_SECRET=$(openssl rand -base64 48)
```

Requires **Java 21**. `build.gradle` pins a Java 21 toolchain while this machine
defaults to JDK 27; the `foojay-resolver-convention` settings plugin provisions
21 automatically, so a fresh clone builds with no manual step.

On a fresh database `AuthBootstrap` registers the five dashboards as applications
and creates one administrator. If `AUTH_BOOTSTRAP_ADMIN_PASSWORD` is unset it
generates a random password and logs it **once** at WARN — deliberately, because
a fixed default password is how these accounts reach production unchanged.

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

**137 tests, 0 failures**, re-run with `--rerun-tasks` so nothing was cached.

| Suite | Tests | Tier | Covers |
|-------|-------|------|--------|
| `AjsDateDecodersTest` | 30 | 1 | CYYMMDD, YYMMDD, HHMMSS, sentinels, display |
| `AuthFlowTest` | 19 | 4 | Real sign-in/up/out, role gating, disabled, tamper |
| `JwtServiceTest` | 18 | 4 | Sign/verify, expiry, tampering, secret validation |
| `JdbcAjsRepositoryTest` | 16 | 2 | SQL + mapping vs H2 `MODE=DB2`, run window |
| `DashboardControllerTest` | 16 | 3 | Thymeleaf rendering, auth required, timeline page |
| `AjsObservabilityServiceTest` | 11 | 1 | View logic, `*NOSBM` rule, gaps, lane building |
| `PermissionDenylistTest` | 9 | 4 | Denylist semantics, cascade, per-user scoping |
| `TimelineViewHelperTest` | 8 | 1 | Bar geometry, gaps, minimum width, edges |
| `AppPermissionEnforcementTest` | 6 | 4 | Denylist actually enforced on routes |
| `SecurityWiringTest` | 3 | 4 | Chain installed, bcrypt is real bcrypt |
| `MockMvcSecurityDiagnosticTest` | 1 | 4 | Isolates harness vs application security |

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
| Public sign-up | creates `USER` only; no path to admin |

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

- **CSRF is disabled.** Protection rests on the `SameSite=Lax` cookie. This is
  the most serious item in the repository and is covered in full in §8, finding
  C1.
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
picks the finest bucket that keeps the tick count readable, so the scale adapts
to the window. The scale is validated against a fixed set in `DashboardController`
rather than trusted, because it drives tick arithmetic.

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

Percentage arithmetic lives in `TimelineViewHelper` rather than in template
expressions. It is logic, and logic in a `th:style` attribute cannot be tested —
the first attempt put nested `BigDecimal` expressions there, and it produced the
truncated-page bug described in §8.

### Fixture history

`data-ajs.sql` seeds 20 `QAIJSHST` rows across 2025-11-19..21: an overlapping
nightly batch cluster, one 47-minute archive job, a failure that is retried
successfully, and repeated short jobs to exercise the 15-minute scale. Every
`HSJOB` names a job present in `QAIJSMST`, because a history row for an unknown
job is silently undisplayable.

**No screenshot of the timeline exists yet** — the images in `docs/screenshots/`
predate the view. Regenerate them with `docs/tools/`.

---

## 8. Design review

An honest critique of the current design, ordered by how much each issue matters
rather than how easy it is to fix. Every finding was verified against the code,
with file and line references so each can be checked.

**Verdict:** the core architecture is right and I would not change it. Read-only
SQL over `QUSRIJS`, a repository interface with two implementations,
server-rendered Thymeleaf, domain semantics in tested Java — those decisions are
holding up, and the offline test tier is the reason the bugs in this document
were caught at all. The problems are concentrated in the security layer's
reasoning and in query strategy. Nothing is an emergency; **C1 and C2 should be
fixed before this is exposed beyond a handful of trusted users.**

| # | Issue | Severity | Effort |
| --- | --- | --- | --- |
| C1 | CSRF disabled; the justification in the comment is false | **High** | S |
| C2 | `findRunsBetween` selects the entire history table | **High** | M |
| C3 | Admin actions are not audited at all | Medium | S |
| C4 | Permission denylist defaults could fail open | Medium | S |
| C5 | Several service methods each re-read the whole master file | Low | S |
| C6 | No caching, and no actual poller | Low | M |
| C7 | `LocalDate.now()` in domain code limits testability | Low | S |

### C1 — CSRF is disabled, and the stated reason was untrue

**High. Fix before wider exposure. Small effort.**

`SecurityConfig.filterChain` disables CSRF with this justification:

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

**Done so far:** the false comment has been replaced with an accurate one naming
the real weakness. Re-enabling CSRF is a behaviour change, so it is left to
whoever owns the security posture.

### C2 — the timeline reads the entire history file on every request

**High for scale. Medium effort.**

`JdbcAjsRepository.findRunsBetween` builds:

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

Worth deciding at the same time: the timeline does not paginate, so a wide window
gives an unbounded response. `req.txt` §6 targets "a few thousand AJS jobs" at
"< 2s p95". Fetching the whole history file per page view will not meet that, and
the failure will present as a slow DRDA transfer rather than an error — the
hardest kind to diagnose.

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

### C4 — permission denylist defaults

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

**Low now, Medium later.**

`req.txt` §6 asks for adaptive polling and caching (R6: excessive polling can
consume IBM i CPU). There is no poller and no cache: every page view is a fresh
set of queries. That is simpler and safer than a poller, and for interactive use
arguably better — but load is proportional to human page views, with no rate
limit. A user holding F5 hammers the partition.

If real-time views are added (Phase 4), this becomes the central design problem:
**one scheduled poller with a cache and an adaptive interval, not per-request
queries.** Do not add polling per page.

### C7 — `LocalDate.now()` in domain code

**Low. Small effort.**

`AjsObservabilityService.overview()` and the controllers call `LocalDate.now()`
directly. `AjsDateDecoders.decodeYymmdd` already does the right thing, with a
package-visible variant taking an injected pivot so tests are deterministic. The
service layer does not follow that pattern, so date-relative behaviour is testable
only relative to "today". Worth injecting a `Clock` if such behaviour grows.

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
| R6 | Polling load on the partition |
| R7 | AJS licensing is separate from `QUSRIJS` access |
| R8 | Multi-partition drift |

**R1 now has a concrete open question** raised by the timeline work: the key and
granularity of `QAIJSHST`. The fixture assumes `(HSJOB, HSSEQ, HSDTE, HSTME)` with
one row per run; the original key `(HSJOB, HSSEQ)` could not hold history at all.
If the real file is keyed differently, or holds one row per *step* rather than per
run, the timeline's bars would be wrong in a way tests against H2 can never
reveal. One `DSPFFD` retires this.

### To settle on a real partition, before Phase 1 exits

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

## 13. Hand-off

If you are picking this up cold, read this document in order, then `req.txt` for
the original requirements. `docs/README.pdf` is an illustrated walkthrough of the
running application, generated by `docs/tools/` from real HTTP responses.

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

### First three things I would do

1. **Fix C1** — re-enable CSRF, or at minimum keep the corrected comment. Highest
   risk-to-effort ratio in the repository.
2. **Fix C2** — bound the history query. It is the only thing here that gets worse
   as the system succeeds.
3. **Run `DSPFFD`** and retire R1. Everything about the data model rests on an
   authored schema.

---

## Licence

No licence file is included, which means all rights are reserved by default. Add
one before accepting external contributions.
