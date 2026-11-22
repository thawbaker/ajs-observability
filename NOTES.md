# Implementation Notes — AJS Observability

Working recommendations distilled from `req.txt`, plus verified facts about this
machine. Read this before changing direction.

## Core architectural recommendation (from req.txt §3, Option A + C)

- **Primary data source: direct read-only SQL over QUSRIJS physical files**
  (`QAIJSMST`, `QAIJSHST`, `QAIJS1CM`) reached over the **jt400 JDBC driver**.
  NOT the jt400 `JobList`/`CommandCall` Java API (that stays as fallback = Option B).
- **Complement with QSYS2 SQL services** for live state:
  `ACTIVE_JOB_INFO()`, `JOB_INFO()`, `GET_JOB_INFO()`.
- No REST/JSON API assumed from AJS. No software may be installed on the IBM i.
- Stack: Spring Boot + Thymeleaf (server-rendered), Spring MVC, JdbcTemplate,
  jt400 JDBC. Read-only IBM i service profile. No SPA.

## Verified facts about this machine

| Item | Status |
|------|--------|
| Java | 27 (Temurin) — but **compile target pinned to 21 LTS** |
| Gradle | 9.8.1 |
| Maven | not installed — use Gradle |
| Docker | installed, **unusable without password** (`zeke` not in `docker` group) |
| jt400 | 21.0.7 on Maven Central, driver registers standalone |
| H2 | 2.3.232 available (DB2 compatibility mode) |

### Critical constraint discovered

**The jt400 driver only accepts `jdbc:as400:` URLs.** Verified empirically:

```
Driver loaded: AS/400 Toolbox for Java JDBC Driver
acceptsURL jdbc:as400://host;naming=system = true
```

Consequence: you cannot point the app at H2/SQLite and call it a test. A
repository seam (`AjsRepository` interface) is **mandatory**, not stylistic. This
is the mechanism that makes req.txt §6 "no dependence on live i" achievable.

## Testing tiers (no iSeries required)

1. **Unit** — date/sentinel decoders. Pure logic, highest value. Risk R2.
2. **Repository** — H2 `MODE=DB2` with self-authored DDL mirroring QUSRIJS.
3. **Web** — `@WebMvcTest` + MockMvc rendering real Thymeleaf pages.
4. **Optional real Db2** — needs Docker; note Db2 **LUW ≠ DB2 for i**, and LUW
   does not exercise the jt400 DRDA path at all.

### What local testing CANNOT cover (be honest with stakeholders)

- R1: actual `QAIJSMST`/`QAIJSHST` column layouts (IBM support notes, not a contract)
- R2: real `CYYMMDD` encoding + `9999999`/`0` sentinel behaviour per release
- R4: presence/PTF level of `QSYS2.ACTIVE_JOB_INFO` on your release
- The jt400 DRDA/host-server path end to end

**Highest-leverage ask: one read-only connection to one real IBM i for one
afternoon, before Phase 1 ends.** Cheaper than rework, more valuable than any
local scaffolding.

## Domain semantics that must be encoded in the UI (req.txt §3)

- `JMSTS` — `*READY` vs `*HELD`
- `JMLCC` — `'N'` normal, `'A'` abnormal
- `JMLRD` — 6-digit `YYMMDD`, `0` = never run
- `JMDTE` — 7-digit `CYYMMDD`, `9999999` = no next run
- Recovery codes: `*NOSBM` skipped-due-to-system-down is **not a job failure**.
  Do not surface raw rows as failures without this filter.

## Risks to keep visible (req.txt §7)

R1 schema drift · R2 legacy date encodings · R3 privileged monitoring account ·
R4 running-now not in AJS master · R5 DRDA may be firewalled · R6 polling load ·
R7 AJS licensing separate from QUSRIJS access · R8 multi-partition drift.

**R1 now has a concrete open question** raised by the timeline work: the key and
granularity of `QAIJSHST`. The fixture assumes `(HSJOB, HSSEQ, HSDTE, HSTME)`
with one row per run; the original authored key `(HSJOB, HSSEQ)` could not hold
history at all. If the real file is keyed differently, or carries one row per
*step* rather than per run, the timeline's bars would be wrong in a way tests
against H2 can never reveal. One `DSPFFD` against a real partition retires this.

---

## How to run

```bash
# Full test suite — offline, no IBM i, no Docker.
./gradlew test

# Run the real application against fabricated data (H2), for UI work / demos.
./gradlew bootRun --args='--spring.profiles.active=local'
# then browse http://localhost:8080

# Production build. H2 is NOT included (verified); jt400 IS (verified).
./gradlew build
```

### Toolchain note

`build.gradle` requires a **Java 21** toolchain while this machine defaults to
JDK 27. The `foojay-resolver-convention` settings plugin auto-provisions 21, so
a fresh clone builds without manual intervention. A local JDK 21 was installed
via mise during development.

---

## Bugs the offline tests actually caught

Recorded because each one would have reached a stakeholder as a wrong dashboard.

1. **YYMMDD pivot was inverted-ish.** A naive `yy <= currentYear % 100` rule
   turned a next-run date of `2026-01-01` into **1926-01-01**. Replaced with:
   prefer 20xx unless that is implausibly far in the future.
2. **CYYMMDD century flag misread in a test expectation.** `1991231` is
   `2099-12-31`, not 1999 — the leading digit is a century flag.
   `0991231` (numerically `991231`) is the value that means 1999. Now asserted
   explicitly so nobody "fixes" it back.
3. **`QAIJS1CM` sequence confusion.** The command query filtered
   `CMSEQ = JMSEQ`. Those are unrelated concepts: `JMSEQ` is the job's sequence,
   `CMSEQ` is each command's position. The command list returned 1 row instead
   of 2. Do not reintroduce that filter.
4. **CHAR columns are blank-padded.** `CMCMD` (`CHAR(200)`) came back padded to
   full width; templates and CSV exports would have carried the padding. Padding
   is now stripped in the repository.
5. **`QAIJSHST` was keyed so a job could only ever have one run.** The authored
   schema declared `PRIMARY KEY (HSJOB, HSSEQ)` — no date component — which
   permits exactly one history row per job for all time. The second run of any
   job collided. This was caught by the fixture, not by review: seeding a single
   day of realistic history produced
   `Unique index or primary key violation ... (HSJOB, HSSEQ)`. The key is now
   `(HSJOB, HSSEQ, HSDTE, HSTME)`. **This is a guess about a file we cannot
   inspect** — confirm the real key with `DSPFFD` before the timeline SQL is
   treated as settled.
6. **A Thymeleaf parse error returned HTTP 200 with a truncated page.** A nested
   ternary in `th:classappend` failed to parse, and because the response had
   already begun streaming, the client received a half-rendered page: gutter
   rows present, every bar and the entire detail table missing. The MockMvc test
   passed anyway, because it only asserted strings that appeared *before* the
   failure. Two fixes: the outcome-to-class mapping moved into
   `TimelineViewHelper` (Thymeleaf's parser rejects the parenthesised nested
   conditional outright), and the test now asserts the template's *tail*
   (`Runs in window`, `tl-legend`, `</html>`) so a mid-page failure cannot pass.
   Lesson: on a server-rendered chart, "assert something rendered" is not the
   same as "assert it finished rendering".
7. **`!warning` on a `String` in the controller.** A compile error, but worth
   noting because the intent was a null check and the code said negation.

---

## Run timeline (`/timeline`)

Added after the first four dashboards. Answers a question none of the others
could: *when did jobs actually run, and how long did each take?*

- **Encoding:** each run is one bar. Left edge = start time, width = duration.
  A scheduling pile-up or a runaway step is visible without reading numbers.
- **Dynamic time scale:** `Auto` / `15 min` / `Hour` / `6 hour` / `Day`. `Auto`
  picks the finest bucket that keeps the tick count readable, so the scale
  adapts to the window. The scale is validated against a fixed set in
  `DashboardController` rather than trusted, because it drives tick arithmetic.
- **Data:** `AjsRepository.findRunsBetween(from, to)`, a new method reading
  `QAIJSHST`. The window is **half-open `[from, to)`** so that paging through
  days does not double-count a run starting exactly on a boundary.
- **Empty vs. missing:** jobs are laned from the *master file*, not from history
  rows, so a job that was expected to run and produced nothing still appears, as
  a dashed `not run` marker. Deriving lanes from history alone would hide
  exactly the case an operator is looking for.
- **A run with no elapsed time is treated as still running**, not as duration
  zero. The alternative draws an in-flight job as an instantaneous success,
  which is a false all-clear.
- **Deliberate visual lie:** bars have a 0.35% minimum width
  (`TimelineViewHelper.MIN_WIDTH_PCT`). Without it, a short run in a wide window
  rounds to zero pixels and a job that *did* run looks like one that did not.
  The tooltip and the detail table carry the true duration.
- **No JavaScript required to render.** Bar geometry is computed server-side in
  `TimelineViewHelper`. The only script on the page draws axis ticks; with it
  disabled the bars stay correct and only the labels disappear.
- Percentage arithmetic is in `TimelineViewHelper`, not in template expressions.
  The first attempt put nested `BigDecimal` expressions in a `th:style`; it was
  unreadable and untestable, and it is what produced bug 6 above.

### Fixture history

`data-ajs.sql` seeds 20 `QAIJSHST` rows across 2025-11-19..21 with a nightly
batch cluster that overlaps, one 47-minute archive job, a failure that is
retried successfully, and repeated short jobs to exercise the 15-minute scale.
Every `HSJOB` names a job present in `QAIJSMST`, because a history row for an
unknown job is silently undisplayable. **Whether the real `QAIJSHST` holds one
row per run or one row per *step* within a run is not confirmed** — the fixture
assumes per-run.

---

## Is it feasible to define, trigger, and schedule jobs?

Asked directly, so answered directly. **Yes, technically feasible — but it is a
different product with a different risk profile, and it is not a feature to bolt
onto this app.** This is open question 1 in `req.txt` §9, so it needs a decision
rather than an implementation.

### The three capabilities are not equally hard

| Capability | Mechanism | Difficulty | Main obstacle |
| --- | --- | --- | --- |
| **Trigger** (run a job now) | `SBMJOB` / AJS submit, or `RLSJOBJS` | Low | Authority only |
| **Hold / release** | `HLDJOBJS` / `RLSJOBJS` via `CommandCall` | Low | Authority only |
| **Define** (create/change a job) | `ADDJOBJS` / `CHGJOBJS` | **High** | ~50 parameters, no transactional safety |
| **Schedule** (change when it runs) | `CHGJOBJS` SCDCDE | **High** | Same as define |

Trigger and hold/release are a real, small win. Define and schedule are where the
cost and the risk actually live.

### The blocking constraint is authority, not code

This is the part that matters most, and it is not obvious.

**AJS enforces its own authority model on top of IBM i object authority.** Having
`*USE` on the command is *not sufficient*. IBM documents this as message
`IJS1098`: a user with `*USE` to `ADDJOBJS` still gets
`Not authorized to function *ADDJOB`. The fix is to grant authority through AJS's
*own* configuration:

```
GO JS  ->  Option 5 (System Controls)  ->  Option 4 (Work with Function Authorities)
       ->  Option 2 (Edit Function Authority)  ->  grant *USE to the *ADDJOB function
```

Consequences for this project:

- **The read-only service profile cannot do any of this, by design.** Its whole
  value is that it has no `*JOBCTL` and no AJS function authority. Writing means a
  **second, privileged profile** with its own credential, rotation, and audit
  story.
- That directly contradicts the security posture the app already encodes:
  "Dedicated read-only IBM i user profile (no `*ALLOBJ`, no `*JOBCTL`)"
  (`req.txt` §6) and the augmented authority model in the auth layer.
- `ADDJOBJS`, `CHGJOBJS`, `HLDJOBJS`, and `RLSJOBJS` are all
  **`Threadsafe: No`**. `CommandCall` calls are therefore serialised. A naive
  "loop over jobs and hold them" will interleave badly under concurrency; needs
  an explicit single-threaded command queue.
- `*JOBCTL` may also be required for commands that act on other users' jobs.
  Confirm per command on the actual release — treat as unknown.

### Define is not just more fields — it is a different problem

`ADDJOBJS` takes on the order of fifty parameters (schedule code `SCDCDE` with
its `*DAILY`/`*CALENDAR`/`*MINUTE`/`*MONTHEND` variants, calendars, holiday and
fiscal calendars, job queue, job description, run-as profile, message queue,
recovery action, command sequences...). Two specific hazards:

1. **No transactional safety.** There is no "preview" mode and no rollback. A
   half-valid definition is accepted field-by-field; a wrong calendar silently
   schedules a job on the wrong days, and nobody notices until it does not run.
   That is strictly worse than a validation error, because it is a silent wrong
   answer rather than a loud one.
2. **Command-sequence injection.** A job's command text is operator-supplied CL.
   A web form that accepts a command sequence is a remote-code-execution surface
   on the production partition. This needs allowlisting and review, not escaping.

### The CLI approach does not avoid the problem

Two tempting shortcuts, both dead ends:

- **Writing rows directly to `QAIJSMST`.** AJS is a licensed product with its own
  internal consistency (sequence numbers, calendars, dependencies). Writing its
  tables behind its back is unsupported and corrupts state in ways `DSPFFD` will
  not warn you about. Do not.
- **Driving `WRKJOBJS` by screen-scraping.** The command is
  `Where allowed to run: Interactive environments (*INTERACT *IPGM *IREXX *EXEC)`
  and `Threadsafe: No`. It is a panel, not an API, and `CommandCall` explicitly
  does not support interactive commands. There is no supported non-interactive
  path through the panels.

### Recommendation

**Keep the observability app read-only.** Reasons, in order of weight:

1. It is a v1 monitoring tool whose entire trust story is "this cannot break
   production". Adding write authority destroys that, and the value of the write
   features is far smaller than the value of the guarantee.
2. The write path needs a second privileged profile and a new audit surface,
   which is Phase 3 hardening work at minimum, not a feature branch.
3. **Trigger and hold/release are the only write operations with a good
   risk-to-value ratio** — bounded, reversible, and easy to audit. If any write
   capability is wanted, do that one, behind explicit confirmation and an
   append-only audit log recording who, what, when, on which partition.
4. **Define and schedule should not be built into a web app.** If genuinely
   needed, the safer shape is reviewed, version-controlled CL scripts or a
   request/approval workflow — a human approves the change and it is
   source-controlled — not a form that calls `ADDJOBJS` directly.

If this changes, it changes as a deliberate Phase 4/5 project with its own
security review. It should not be added opportunistically to `/jobs`.

### What this means for the current code

Nothing to change — the read-only guarantee is intact and worth keeping explicit:
`AjsRepository`'s contract says every method is read-only, `JdbcAjsRepository`'s
javadoc says every statement is a `SELECT`, and a test asserts no write reaches
the fixture. Those are the right invariants. If write support is ever added, it
should arrive as a **separate interface** with its own datasource and profile, so
the read-only path cannot be widened by accident.

### Sources

- AJS function authority / `IJS1098`:
  https://www.ibm.com/support/pages/message-ijs1098-advanced-job-scheduler
- `WRKJOBJS` environments and threadsafety:
  https://www.ibm.com/docs/en/i/7.4.0?topic=w-work-jobs-using-advanced-job-scheduler
- jt400 `CommandCall` (non-interactive commands only):
  https://javadoc.io/static/net.sf.jt400/jt400-jdk8/11.1/com/ibm/as400/access/CommandCall.html
- HLDJOBJS/RLSJOBJS as the hold/release pair:
  https://www.ibm.com/support/pages/scheduling-advanced-job-scheduler-job-reset-another-advanced-job-scheduler-job

### Two infrastructure traps worth remembering

- **`application-test.yml` in `src/test/resources` is invisible to `bootRun`.**
  Activating `--spring.profiles.active=test` from the main classpath finds no
  such file, silently falls back to the jt400 datasource, and dies on
  `UnknownHostException: ibmi-host`. That is why `application-local.yml` lives in
  `src/main/resources`, and why the fixture SQL moved to
  `src/main/resources/fixture/` (shared by both offline profiles).
- **`testImplementation` dependencies are not on the `bootRun` classpath.**
  Hence the dedicated `localRuntimeOnly` configuration. H2 stays out of the
  production jar — verified: 0 H2 entries, 1 jt400 entry in the boot jar.

---

## Test results (verified)

**61 tests, 0 failures** on a forced `--rerun-tasks` run.

| Suite | Tests | Covers |
|-------|-------|--------|
| `AjsDateDecodersTest` | 30 | Tier 1 — CYYMMDD, YYMMDD, HHMMSS, sentinels, display |
| `JdbcAjsRepositoryTest` | 12 | Tier 2 — SQL + row mapping against H2 `MODE=DB2` |
| `AjsObservabilityServiceTest` | 7 | View logic, `*NOSBM` rule, grouping |
| `DashboardControllerTest` | 12 | Tier 3 — real Thymeleaf rendering over MockMvc |

### Smoke test (verified, not assumed)

`./gradlew bootRun --args='--spring.profiles.active=local'` then, over real HTTP:

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

Those counter values are correct against the fixture, which is worth stating
because it is the check that the `*NOSBM` exclusion really is applied: 8 jobs,
1 held, **1** genuine failure (PAYROLL2, with PAYROLL3 excluded), 2 never run.

---

## Safety measure added beyond the requirements

`FixtureProfileGuard` refuses to start the application on the `local` or `test`
profile unless `AJS_ALLOW_FIXTURE_PROFILES=true` is set. Rationale: the `local`
profile shows **fabricated** job data, and an operator believing invented
scheduler state is a worse outcome than most bugs in this codebase. Default-deny.
The Gradle `test` task sets the opt-in; nothing in `application.yml` does.

## Still cannot be tested here

Restating because it is the whole point of the exercise: the fixture is
**authored**, so passing tests prove the application, not the IBM i. Before
Phase 1 exits, on one real partition:

1. `DSPFFD QUSRIJS/QAIJSMST`, `QAIJSHST`, `QAIJS1CM` — diff against
   `src/main/resources/fixture/schema-ajs.sql`. **This diff retires risk R1.**
2. Confirm real `JMDTE`/`JMLRD` encodings and the `9999999` / `0` sentinels.
3. Confirm `QSYS2.ACTIVE_JOB_INFO` exists at the installed PTF level (R4).
4. Confirm the jt400 DRDA path end to end (`jdbc:as400:`) actually connects (R5).
5. **If write actions are ever in scope:** confirm on the real host which AJS
   function authorities are needed, and whether `*JOBCTL` is required, for
   `HLDJOBJS`/`RLSJOBJS`/`SBMJOB`. Note that `*USE` on the command alone is
   provably not enough (message `IJS1098`) — see the feasibility section above.

Run the app with `ajs.schema-validation.enabled=true` against that partition: it
logs exactly which expected columns are missing.

---

# Authentication, roles and permissions

Ported from `../my-dashboard` (Next.js + better-sqlite3) to Spring Boot, keeping
Thymeleaf throughout. Same table shapes, same bcrypt algorithm, same JWT cookie
name (`auth-token`), same denylist permission model.

## Two datasources, deliberately

| Store | Purpose | Failure mode |
|-------|---------|--------------|
| **SQLite** (`data/auth.db`) | users, roles, apps, permissions | local, always available |
| **jt400 → IBM i** | AJS job data | may be unreachable |

Auth is **not** bound to the IBM i connection. If it were, an iSeries outage
would lock operators out of the tool they use to diagnose that outage. SQLite is
marked non-`@Primary`; the IBM i datasource is `@Primary` so Actuator health
still reports IBM i connectivity rather than quietly reporting SQLite.

## Schema (`src/main/resources/auth/schema-auth.sql`)

`users` (email UNIQUE, bcrypt `password_hash`, role CHECK `user|admin`,
`disabled`), `applications`, and `user_applications` as a **denylist**.

### The denylist inversion

**A row in `user_applications` means the user CANNOT access that app.** No rows =
full access. New apps are granted automatically because nobody has a deny row
for them yet. Inverting this turns "restrict one app" into "revoke everything".
Pinned by 9 tests in `PermissionDenylistTest`.

## How to run

```bash
# Local, no IBM i, fabricated AJS data.
./gradlew bootRun --args='--spring.profiles.active=local'
#   bootstrap admin: admin@localhost / localdev1234  (local profile only)

# Production runs need a real secret, or JwtService refuses to start:
export JWT_SECRET=$(openssl rand -base64 48)
```

On a fresh database `AuthBootstrap` registers the five dashboards as apps and
creates one administrator. If `AUTH_BOOTSTRAP_ADMIN_PASSWORD` is unset it
generates a random password and logs it **once** at WARN — deliberately, because
a fixed default password is how these accounts reach production unchanged.

## Security properties, each verified against the running app

| Property | Verified result |
|----------|-----------------|
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

### A real JWT vulnerability found and fixed

**Appending a character to a valid token's signature was accepted by jjwt
0.12.6.** Measured, not theorised: jjwt base64url-*decodes* the signature before
comparing, and the trailing character contributes only padding bits, which are
discarded — so the same signature bytes result and verification succeeds.

`JwtService` now requires each segment to be canonical: valid base64url that
round-trips exactly through decode→encode. A segment carrying discarded padding
bits cannot round-trip, so it is rejected. Appending `x` now yields 401. Flipping
a signature character and forging a payload were already rejected by the
signature check; this closed the remaining gap. Pinned by
`JwtServiceTest.appendedToSignature`.

### The token is not authoritative

The JWT carries the role so gating is cheap, but it is **not** trusted. Every
request re-reads the user from SQLite and rejects a missing or disabled account,
and authorities are built from the **database** role. That is why disabling or
demoting takes effect immediately instead of whenever the 24-hour token expires.

## Two security-relevant near-misses worth remembering

1. **MockMvc did not install the security filter chain.** With the default
   `@AutoConfigureMockMvc` setup, unauthenticated requests returned **200** in
tests while the running app correctly returned 401. "The gate is closed" was
being asserted against a harness with no gate. Fixed by building MockMvc with
`.apply(SecurityMockMvcConfigurers.springSecurity())`, and `SecurityWiringTest`
now asserts the chain exists in the context. This was found only because the
live app was smoke-tested over real HTTP.
2. **The denylist was stored but not enforced.** The admin UI saved and displayed
   "no access" for an app, and the user could open it anyway. Fixed with
   `AppPermissionInterceptor`, which maps routes to slugs, covers sub-paths, and
   exempts `/sign-in`, `/admin/**` and Actuator so a misconfiguration cannot lock
everyone out. Enforced behaviour is pinned by
   `AppPermissionEnforcementTest`.

> A restriction that is recorded but not applied is worse than no restriction,
> because it reads as a working control.

## Trade-offs accepted knowingly

- **CSRF is disabled**, inherited from the reference implementation. Protection
  rests on `SameSite=Lax` cookies. If write endpoints are added, this must be
  revisited — the AJS side stays read-only, but user administration is not.
- **Sign-in reveals whether an email exists** ("no account found" vs "incorrect
  password"), matching the reference so users get actionable errors. This allows
  account enumeration. Collapse the two messages before facing the public
  internet; the distinction is logged server-side either way.
- **`spring-boot-starter-security` is on the classpath**, so the production jar
  grew to ~51 MB. Fine for an internal tool; worth noting if image size matters.
- **`usersDenied()` on the Apps page is O(users × apps) queries.** Acceptable at
  this scale; it would need a join before the user count grows materially.

## Test results (verified)

**118 tests, 0 failures** on a forced `--rerun-tasks` run.

| Suite | Tests | Covers |
|-------|-------|--------|
| `AjsDateDecodersTest` | 30 | CYYMMDD, YYMMDD, HHMMSS, sentinels |
| `JwtServiceTest` | 18 | sign/verify, expiry, tampering, secret validation |
| `AuthFlowTest` | 19 | real sign-in/up/out, role gating, disabled, tamper |
| `DashboardControllerTest` | 13 | Thymeleaf rendering + auth required |
| `JdbcAjsRepositoryTest` | 12 | SQL + mapping vs H2 `MODE=DB2` |
| `PermissionDenylistTest` | 9 | denylist semantics, cascade, per-user scoping |
| `AjsObservabilityServiceTest` | 7 | view logic, `*NOSBM` rule |
| `AppPermissionEnforcementTest` | 6 | denylist actually enforced on routes |
| `SecurityWiringTest` | 3 | chain installed, bcrypt is real bcrypt |
| `MockMvcSecurityDiagnosticTest` | 1 | isolates harness vs application security |

### Verified production jar composition

H2: **0** entries (correctly absent — the shipped artifact can only talk to a
real IBM i). SQLite: **4** entries present.

## Still cannot be tested here (auth)

- Nothing here validates against the real `my-dashboard` database. The schemas
  are *believed* compatible (bcrypt `$2a$10$`, same columns) but no test moves a
  row between the two. If sharing a user database is intended, that needs an
  explicit cross-verification.
- TLS: `setSecure(false)` on the session cookie, because local HTTP. **Must be
  flipped to `true` behind HTTPS** before any real deployment, or the token is
  sent in clear text.
