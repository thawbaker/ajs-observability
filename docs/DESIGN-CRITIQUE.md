# Design critique and handoff

Written at the point the timeline view was finished and the application was made
public. Two audiences:

1. **A reviewer** deciding whether the current design is sound.
2. **A new maintainer** picking this up cold.

Ordered by how much the issue actually matters, not by how easy it is to fix.
Every finding was verified against the code, not inferred — file and line
references are given so each can be checked.

---

## Verdict

The core architecture is right and I would not change it. Read-only SQL over
`QUSRIJS`, a repository interface with two implementations, server-rendered
Thymeleaf, domain semantics in tested Java rather than in template expressions —
those decisions are holding up well, and the offline test tier is the reason
three real bugs were caught.

The problems are concentrated in two places: **the security layer's reasoning**
(one comment is factually wrong and the reasoning behind it is the only thing
protecting state-changing endpoints), and **query strategy** (two methods fetch
whole tables and filter in Java, which does not scale to the stated target).

Nothing here is an emergency. Two items should be fixed before this is exposed
to more than a handful of trusted users: **C1** and **C2**.

| # | Issue | Severity | Effort |
| --- | --- | --- | --- |
| C1 | CSRF disabled; justification in the comment is false | **High** | S |
| C2 | `findRunsBetween` selects the entire history table | **High** | M |
| C3 | Admin actions are not audited at all | **Medium** | S |
| C4 | Permission denylist defaults could fail open | Medium | S |
| C5 | `overview()` recomputes by re-reading everything | Low | S |
| C6 | No caching; poll interval is per-request, not scheduled | Low | M |
| C7 | `LocalDate.now()` makes some behaviour untestable | Low | S |

---

## C1 — CSRF is disabled, and the stated reason is untrue

**High. Fix before wider exposure. Small effort.**

`SecurityConfig.filterChain` disables CSRF with this justification:

> CSRF is disabled because the session is a cookie-authenticated stateless API
> and there is no HTML form POST from a browser session in the classic sense.

That is **factually wrong**, and it is worth being blunt about because a wrong
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

**Why this is not already a live vulnerability:** the session cookie is set with
`SameSite=Lax` (`AuthController:131`), which prevents it being sent on a
cross-site `POST`. So the practical risk today is low — but that means
**`SameSite=Lax` is the single defense protecting every state-changing endpoint**,
and that fact is nowhere recorded. Two consequences:

- Any browser that does not honour `SameSite` — or any future relaxation to
  `SameSite=None` — silently makes admin user deletion CSRF-able.
- The comment tells a reviewer the risk is already handled, so the real
  dependency is invisible.

**Recommendation:** re-enable CSRF protection. The templates are server-rendered
Thymeleaf, so this is genuinely easy: Thymeleaf's `th:action` automatically
injects a hidden `_csrf` field, and Spring Security's default token repository
works unchanged. Login and logout POSTs also need the token — which is exactly
what the Thymeleaf integration handles for free. Then delete the misleading
comment rather than rewording it.

If CSRF stays disabled for now, **the comment must be corrected** to state that
`SameSite=Lax` is the only mitigation and is load-bearing. That is a five-minute
change and it is the difference between a reviewer seeing the risk and not.

---

## C2 — the timeline reads the entire history file on every request

**High for scale. Medium effort.**

`JdbcAjsRepository.findRunsBetween` builds:

```java
String sql = "SELECT " + JobRunRowMapper.SELECT_COLUMNS + " FROM " + HISTORY + " h";
```

No `WHERE` clause. It selects **every row in `QAIJSHST`** — which on a real
partition is the complete run history, plausibly hundreds of thousands of rows
growing forever — transfers all of it over DRDA, maps each row in Java, and only
then filters to the window.

The window filter *cannot* trivially be pushed into SQL, and the existing code is
right about why: `HSDTE` is packed CYYMMDD, so comparing it to a timestamp raises
SQL0181/CPD4019 (risk R2). But "cannot compare timestamps directly" does not mean
"must read the whole table". Options, in order of preference:

1. **Bound on the packed value.** `HSDTE` is numerically ordered within a century
   — `1251120` < `1251121`. A `WHERE h.HSDTE BETWEEN ? AND ?` on *decoded
   CYYMMDD integers* is a valid range restriction and uses an index if one
   exists. Only the window edges need decoding, in Java, before the query. This
   preserves the "no date arithmetic in SQL" rule while making the query
   selective.
2. **`ORDER BY h.HSDTE, h.HSTME` in SQL** rather than sorting in Java, so the
   database can use an index for ordering.
3. At minimum, an explicit upper bound so the query cannot grow without limit.

Related and worth deciding at the same time: the timeline does not paginate. On
a wide window with many jobs, the response is unbounded. `req.txt` §6 targets
"a few thousand AJS jobs" with "< 2s p95". Fetching the whole history file per
page view will not meet that, and the failure will appear as a slow DRDA transfer
rather than an error — the hardest kind to diagnose.

The same pattern exists in `findScheduledOn`, which calls `findAllJobs()` and
filters in Java. That one is *defensible* and is documented as a deliberate
trade-off at the job scale of a few thousand — I would leave it, but the timeline
history table is a different order of magnitude.

---

## C3 — admin actions leave no trace

**Medium. Small effort.**

`AdminController` can create users, change roles, disable accounts, delete
accounts, and rewrite per-app permissions. None of it is logged. `NOTES.md`
claims "audit logging" as part of Phase 3, but there is currently no audit trail
at all — `git grep` finds no logging in `AdminController`.

For an app whose entire value proposition is "you can trust this view of
production", "who demoted the admin and when" should be answerable. This is the
cheapest high-value fix on the list: a structured log line per admin mutation
(actor, action, target, outcome) is maybe twenty lines. Full audit tables are a
Phase 3 concern; a log line is not.

---

## C4 — permission denylist defaults

**Medium. Small effort.**

`NOTES.md` records a "denylist inversion" and there is a dedicated
`PermissionDenylistTest`, so this was thought about. Flagging it anyway as the
thing to re-verify on any change: for a *monitoring* tool, failing open means
showing a user jobs they are not entitled to see, which may leak job names,
library names, and command text. Failing closed means they see an empty page and
ask. Empty page is the correct default. Worth asserting explicitly that a user
with no permission rows sees nothing, rather than only asserting that explicit
denials work.

---

## C5 — `overview()` re-reads everything to compute counters

**Low. Small effort, only if it matters.**

`overview()` calls `findAllJobs()` once and derives eight counters in one pass —
that part is fine. But the counters are recomputed on every request, and several
other service methods (`allJobs()`, `byApplicationGroup()`, `timeline()`) each
call `findAllJobs()` again. A single page render can issue several full master-file
reads. `countJobs()` issues a separate `SELECT COUNT(*)` on top.

At the stated scale this is acceptable; noting it so the next person does not
"optimise" one method in isolation and miss that the pattern is systemic. The
real fix, if needed, is per-request caching of the master file (C6).

---

## C6 — no caching, and no actual poller

**Low now, Medium later.**

`req.txt` §6 asks for adaptive polling and caching (R6: excessive polling can
consume IBM i CPU/jobs). The current design has no poller and no cache: every
page view is a fresh set of queries. That is *simpler and safer* than a poller,
and for interactive use it is arguably the better choice — but it means load is
proportional to human page views, and there is no rate limit. A user holding F5
hammers the partition.

If real-time views are added (Phase 4), this becomes the central design problem:
one scheduled poller with a cache and an adaptive interval, not per-request
queries. Do not add polling per page.

---

## C7 — `LocalDate.now()` in domain code

**Low. Small effort.**

`AjsObservabilityService.overview()` and the controller use `LocalDate.now()`
directly. `AjsDateDecoders.decodeYymmdd` already does the right thing by having a
package-visible variant with an injected pivot, with a comment explaining that it
exists to keep tests deterministic. The service layer does not follow that
pattern, so anything date-relative in the service is testable only relative to
"today". Worth a `Clock` injection if date-relative behaviour grows.

---

## What is good, and should not be "cleaned up"

Listing these so a future refactor does not undo them:

- **Domain semantics in Java, not in templates.** The `*NOSBM` false-alarm rule,
  never-run/held classification, and sentinel decoding are all in tested Java.
  This is why the timeline's outcome logic was moved into `TimelineViewHelper`
  rather than living in a `th:classappend` — and that move is what fixed the
  truncated-page bug.
- **`AjsSchemaValidator` reports drift rather than refusing to start, by
  default.** A monitoring tool that will not boot is worse than one that boots
  and shouts. Correct call.
- **The read-only invariant is stated in three places** — the interface contract,
  the repository javadoc, and a test that asserts no write reaches the table. That
  redundancy is deliberate and should stay.
- **Comments explain *why*, including rejected alternatives.** The `CMSEQ` vs
  `JMSEQ` comment, the YYMMDD pivot reasoning, and the genuinely-discovered
  infrastructure traps are the most valuable documentation in the repo. The one
  exception is C1, where the comment is confidently wrong — the lesson is that an
  unverified "why" is more dangerous than none.
- **The timeline renders without JavaScript.** Bar geometry is computed
  server-side. On a monitoring page, "goes blank when a script fails" is a real
  failure mode.

---

## Handoff

### Orientation — read in this order

1. **`NOTES.md`** — the running engineering log. Bugs caught, traps, the
   write-feasibility finding. Start here.
2. **`req.txt`** — the original requirements this was built against, including
   the open questions. Several are still open (`§9`).
3. **`docs/DESIGN-CRITIQUE.md`** (this file).
4. **`README.md`** — how to run it.

### Mental model

Two *entirely separate* datasources, and conflating them is the easiest mistake:

```
AJS data (read-only)          Auth data (read-write)
jdbc:as400: -> QUSRIJS        SQLite file
H2 MODE=DB2 in tests          H2 is NOT used here
AjsRepository (interface)     UserRepository / AppRepository
```

The jt400 driver only accepts `jdbc:as400:` URLs, which is *why* `AjsRepository`
is an interface with a second implementation — that is the whole offline test
strategy, not incidental abstraction. Do not "simplify" it away.

### Invariants to preserve

- **Nothing in this application writes to the IBM i.** Every repository statement
  is a `SELECT`. See the write-feasibility section in `NOTES.md` before
  considering otherwise: it needs a second privileged profile and AJS's own
  function authority, and it is a deliberate Phase 4/5 decision, not a feature.
- **Legacy date arithmetic lives only in `AjsDateDecoders`.** Nothing else
  touches CYYMMDD/YYMMDD. This is risk R2's containment boundary.
- **Domain rules belong in Java, not in Thymeleaf expressions.** Template
  expressions are untestable and, as the timeline bug showed, can fail in ways
  that return HTTP 200 with a partial page.

### Known landmines

- **A Thymeleaf parse error returns HTTP 200 with a truncated page.** The
  response streams before it fails. If you add template logic, assert the
  template's *tail* renders (`</html>`), not just that some expected string is
  present. `DashboardControllerTest` shows the pattern.
- **Multi-line expressions in `th:*` attributes fail to parse.** Keep them on one
  line.
- **`application-test.yml` in `src/test/resources` is invisible to `bootRun`.**
  That is why `application-local.yml` lives in `src/main/resources`.
- **`testImplementation` deps are not on the `bootRun` classpath.** Hence the
  `localRuntimeOnly` configuration that keeps H2 out of the production jar.

### Open questions not resolved by code

Carried forward from `req.txt` §9, still needing a human decision:

- **R1 / layouts:** run `DSPFFD QUSRIJS/QAIJSMST`, `QAIJSHST`, `QAIJS1CM` on a
  real partition and diff against `src/main/resources/fixture/schema-ajs.sql`.
  This is the single highest-value next step — it retires the largest assumption
  in the project, including the `QAIJSHST` key I had to guess.
- **R5:** confirm DRDA/JDBC connectivity is permitted by network policy.
- **R4:** confirm `QSYS2.ACTIVE_JOB_INFO` at the installed PTF level.
- Multi-partition support: not designed for. `req.txt` §9 q3.
- SSO/Kerberos vs local accounts: `req.txt` §9 q8.

### First three things I would do

1. **Fix C1** — re-enable CSRF, or at minimum correct the comment. Highest
   risk-to-effort ratio in the repo.
2. **Fix C2** — bound the history query. It is the only thing here that gets
   worse as the system succeeds.
3. **Run `DSPFFD`** and retire R1. Everything about the data model rests on an
   authored schema.
