# AJS Observability

A read-only web dashboard for **IBM Advanced Job Scheduler (AJS)** on IBM i.
It answers what a 5250 session makes tedious: what is due today, what failed,
what is held, and — on the timeline — when jobs actually ran and how long each took.

**It never writes to the IBM i.** Every statement is a `SELECT`, and the service
profile it is designed for has no `*JOBCTL` and no AJS function authority.

> This README is the *how to build and run it* document. `NOTES.md` is the design
> record — why the code looks the way it does, the risks, the measurements and the
> seventeen screenshots. Read this file to get it running; read `NOTES.md` to
> change it.

---

## Contents

- [Quick start — no IBM i needed](#quick-start-no-ibm-i-needed)
- [Prerequisites](#prerequisites)
- [Build](#build)
- [Test](#test)
- [Run against a real IBM i / iSeries](#run-against-a-real-ibm-i-iseries)
- [First-contact checklist for a new partition](#first-contact-checklist-for-a-new-partition)
- [Configuration reference](#configuration-reference)
- [Troubleshooting](#troubleshooting)
- [What is still unproven](#what-is-still-unproven)

---

## Quick start — no IBM i needed

A fabricated fixture is built in, so the whole application runs with no host.

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

Then open <http://localhost:8080> and sign in as:

| Email | Password |
| --- | --- |
| `admin@localhost` | `localdev1234` |

That is the entire setup. The `local` profile seeds H2 in memory with eight
invented AJS jobs and twenty run-history rows, so every page has content.

**Two things that surprise people here:**

- **Run it through Gradle.** `bootRun` puts H2 on the classpath and sets
  `AJS_ALLOW_FIXTURE_PROFILES=true`. Run the packaged jar with the `local`
  profile instead and the app **refuses to start** — deliberately, because a
  deployed environment must never display invented scheduler state.
- **The admin database persists.** `./data/auth-local.db` survives restarts. To
  re-run the first-boot bootstrap from scratch, delete that file.

---

## Prerequisites

| Requirement | Notes |
| --- | --- |
| **Java 21** | Pinned as a Gradle toolchain. A newer JDK on your machine is fine — Gradle auto-provisions 21 via the Foojay resolver, so a fresh clone builds with no manual JDK step. |
| **Network access on first build** | Dependencies come from Maven Central. After one successful build, `--offline` works. |
| **Linux / macOS / Windows** | The Gradle wrapper is committed, so no Gradle install is needed. |

Verify the toolchain resolves before anything else:

```bash
./gradlew -q javaToolchains | grep -A2 'JDK 21'
```

That should show a provisioned Temurin/Adoptium 21. Note that
`./gradlew --version` reports your **launcher** JVM (27 on the machine this was
written on), not the build toolchain — the build itself is compiled and tested
against 21 regardless, which is the point of pinning it.

For running against a real partition you additionally need **network reachability
to the DB2 for i host server ports** — see [Run against a real IBM
i](#run-against-a-real-ibm-i-iseries).

---

## Build

```bash
./gradlew build            # compiles, runs all tests, assembles the jar
./gradlew build -x test    # jar only, no tests
```

Output lands in `build/libs/`:

```
ajs-observability-0.1.0-SNAPSHOT.jar         <- runnable (Spring Boot fat jar, ~51 MB)
ajs-observability-0.1.0-SNAPSHOT-plain.jar   <- NOT runnable (no main manifest)
```

**Use the versioned jar by name, not a glob.** `java -jar build/libs/*.jar` can
resolve to the `-plain` jar and fail with `no main manifest attribute`:

```bash
java -jar build/libs/ajs-observability-0.1.0-SNAPSHOT.jar
```

The production jar deliberately contains **no H2 driver** (verified: 0 H2 entries),
so the shipped artifact can only talk to a real IBM i. That is why the `local`
profile cannot be run from it.

---

## Test

```bash
./gradlew test                          # 187 tests, all offline
./gradlew test --tests "*JdbcAjsRepository*"   # one suite
./gradlew test --rerun-tasks            # force, ignoring the build cache
```

**All 187 tests run with no IBM i, no Docker and no network.** They are grouped
into four tiers:

| Tier | What it proves | Example suite |
| --- | --- | --- |
| 1. Unit | Legacy date decoding, view logic, timeline geometry | `AjsDateDecodersTest` |
| 2. Repository | Real SQL against H2 in `MODE=DB2`, standing in for `QUSRIJS` | `JdbcAjsRepositoryTest` |
| 3. HTTP / template | Real Thymeleaf rendering via MockMvc | `DashboardControllerTest` |
| 4. Security | Sign-in, role gating, CSRF, per-app permissions | `AuthFlowTest` |

A fifth tier — a real DB2 for i in Docker — is deliberately **not** wired up:
Db2 LUW is not DB2 for i, and it does not exercise the jt400 DRDA path at all,
so it would add setup cost while validating almost nothing.

### What the tests cannot prove

The fixture is **authored, not extracted**. Passing tests prove the application,
not your partition. Specifically unproven without a host: the real column layouts
of `QAIJSMST`/`QAIJSHST`, real `CYYMMDD` values and sentinels, and the jt400 DRDA
path end to end. `NOTES.md` §4 ("What local testing cannot cover") lists these as
risks R1–R5.

---

## Run against a real IBM i / iSeries

### 1. Create the service profile on the host

The account, not the JDBC URL, is what enforces read-only. On the IBM i:

```text
CRTUSRPRF USRPRF(AJSREAD) PWDEXPITV(*NOMAX) STATUS(*ENABLED)
          USRCLS(*USER) SPCAUT(*NONE) INLPGM(*NONE) INLMNU(*SIGNOFF)
GRTOBJAUT OBJ(QUSRIJS) OBJTYPE(*LIB) USER(AJSREAD) AUT(*USE)
GRTOBJAUT OBJ(QUSRIJS/QAIJSMST) OBJTYPE(*FILE) USER(AJSREAD) AUT(*USE)
GRTOBJAUT OBJ(QUSRIJS/QAIJSHST) OBJTYPE(*FILE) USER(AJSREAD) AUT(*USE)
GRTOBJAUT OBJ(QUSRIJS/QAIJS1CM) OBJTYPE(*FILE) USER(AJSREAD) AUT(*USE)
```

`SPCAUT(*NONE)` is intentional: no `*ALLOBJ`, no `*JOBCTL`, no `*SAVSYS`. If
authority is wrong, the app fails at the schema check with an explicit
`table unreadable ... lacks *USE authority` message rather than rendering empty.
*(Verify with your security team — the exact `USRCLS`/special-authority profile
should follow your site's standards.)*

### 2. Confirm the host server port is reachable

jt400 talks DRDA to the **database host server**. The default port is **8471**;
older configurations use **446**. This is the step most likely to be blocked by
network policy (risk R5).

```bash
# From the machine that will run the app:
nc -vz <ibmi-host> 8471
nc -vz <ibmi-host> 446      # legacy DRDA, worth trying if 8471 is closed
```

If that fails, the application cannot reach the partition regardless of
credentials, and the fallback is a SQL-services gateway rather than direct DRDA.

> **There is a public IBM i you can use to test the DRDA path itself**:
> `pub400.com:8471` accepts connections from the internet. The driver completes a
> full signon exchange and is rejected on bad credentials with SQLState `08004`.
> That is enough to prove the network path and driver work. It will **not** have
> AJS installed (5770-JS1), so it cannot serve dashboard data.

### 3. Set the environment and start

```bash
export IBMI_JDBC_URL='jdbc:as400://ibmi-host/QUSRIJS;naming=system;libraries=QUSRIJS;translate binary=true;prompt=false;errors=full;read only=true'
export IBMI_USER='AJSREAD'
export IBMI_PASSWORD='...'

# Required; startup fails with no usable default.
export JWT_SECRET=$(openssl rand -base64 48)

# Optional: set the first admin's password. Leave unset and it is generated
# and printed ONCE at WARN level on first start.
export AUTH_BOOTSTRAP_ADMIN_PASSWORD='choose-something-strong'

# Optional: defaults to ./data/auth.db — keep it out of the deploy directory if
# that is read-only or replaced on upgrade.
export AUTH_DATABASE_PATH=/var/lib/ajs/auth.db

./gradlew bootRun                                        # development
java -jar build/libs/ajs-observability-0.1.0-SNAPSHOT.jar  # deployment
```

The `IBMI_JDBC_URL` above is the verified-working shape. Each parameter matters:

| Parameter | Why |
| --- | --- |
| `naming=system` | IBM i `*SYS` naming, so `QUSRIJS.FILE` resolves |
| `libraries=QUSRIJS` | default library for unqualified names |
| `translate binary=true` | required to read some AJS packed fields safely |
| `prompt=false` | never block on an interactive sign-on prompt server-side |
| `errors=full` | full SQLCA detail, needed to diagnose `SQL0181`/`CPD4019` |
| `read only=true` | a request to the driver. **Not** a security control — the profile's authority is. |

### 4. Use the `ibmi` profile for the first connection

This is the step people skip, and it is the one that saves an afternoon:

```bash
./gradlew bootRun --args='--spring.profiles.active=ibmi'
```

The **default** profile tolerates schema drift: missing columns are logged as a
warning and the app starts anyway. Good for a host you have already inspected;
bad for a first look, because a half-empty dashboard looks like a working one.

The **`ibmi`** profile sets `fail-on-missing-columns=true`, so a mismatch is a
**failed start with a precise list of missing columns**. Against an unreachable
host it exits with, for example:

```
AJS schema drift detected ... QAIJSMST unreadable
(The application requester cannot establish the connection. (ibmi-host))
```

Once `DSPFFD` has confirmed the layouts, either keep this profile or move to the
default one — the only difference is whether drift is fatal.

---

## First-contact checklist for a new partition

Work through this in order the first time you point the app at a real system.
It turns "the dashboard looks odd" into a specific diagnosis.

```text
[ ] 1. Network:   nc -vz <host> 8471                     -> open?
[ ] 2. Authority: profile can SELECT from the three files -> app starts, no drift warn
[ ] 3. Layouts:   DSPFFD FILE(QUSRIJS/QAIJSMST)           -> matches the mapper's columns
                   DSPFFD FILE(QUSRIJS/QAIJSHST)
                   DSPFFD FILE(QUSRIJS/QAIJS1CM)
[ ] 4. Scale:     SELECT COUNT(*) FROM QUSRIJS/QAIJSMST   -> is it "a few thousand"?
[ ] 5. Index:     DSPDBR FILE(QUSRIJS/QAIJSHST)           -> is there an access path
                                                             over HSDTE?
[ ] 6. Load:      browse each view once, then check DB2 for i timings
```

Steps 4, 5 and 6 exist because the application's performance reasoning rests on
three assumptions that only the target partition can confirm. `NOTES.md` §7a
explains the reasoning; this is the operational form of it:

- **Step 4** — the master-file read has no `WHERE` clause. That is deliberate
  (it is what lets next-run dates be decoded in Java instead of compared in SQL,
  where CYYMMDD raises `SQL0181`). It is justified by "a few thousand jobs". The
  app logs a **one-time warning** if the count exceeds 5,000
  (`JdbcAjsRepository.ASSUMED_SCALE_JOBS`). If you see it, the fix is to project
  only the needed columns and/or push the date filter into SQL — not to raise the
  constant.
- **Step 5** — the timeline's `BETWEEN` on `HSDTE` is index-usable *if an access
  path exists*. Without one it scans. Cheap for a bounded range, but worth knowing.
- **Step 6** — everything else in the docs is structural: statement counts and
  row bounds. It says nothing about milliseconds on your hardware. Collect a short
  `STRDBMON` trace, or read `QSYS2.SQL_STATEMENT_STATISTICS` afterwards, while
  browsing each view once.

There is a scripted version of the layout/data checks:

```bash
docs/host-capture/README.md     # 01-columns.sql .. 05-drda.sql, run in order
docs/host-capture/diff-against-fixture.sh out/01-columns.txt
```

That script reports, per file, columns the local fixture *invents* (guaranteed
runtime failures against the real host) and columns the host has that the fixture
lacks (harmless, just unread).

---

## Configuration reference

Everything has a default in `application.yml`. Only `JWT_SECRET` has no usable
one — the application refuses to start without it in a real deployment.

| Variable | Default | Change when |
| --- | --- | --- |
| `spring.profiles.active` | none (production) | `local` for offline work; `ibmi` for first contact |
| `IBMI_JDBC_URL` | `jdbc:as400://ibmi-host/...` | always, in a real deployment |
| `IBMI_USER` / `IBMI_PASSWORD` | `CHANGEME` | always |
| `JWT_SECRET` | empty | **always** — startup fails without it |
| `AUTH_BOOTSTRAP_ADMIN_EMAIL` | `admin@localhost` | yes, for a real deployment |
| `AUTH_BOOTSTRAP_ADMIN_PASSWORD` | empty → random, logged once | when automating a first deploy |
| `AUTH_DATABASE_PATH` | `./data/auth.db` | when the working directory is not writable or is replaced on upgrade |
| `ajs.polling.inventory-seconds` | `900` | cache lifetime for job definitions (**not** a poll interval — see below) |
| `ajs.polling.live-seconds` | `60` | cache lifetime for failure/held/never-run lists |
| `ajs.polling.cache-enabled` | `true` | `false` to debug a stale reading |
| `ajs.schema-validation.fail-on-missing-columns` | `false` (`true` in `ibmi`) | `true` once layouts are confirmed |

### Two cache settings that look like polling but are not

`ajs.polling.*` are **maximum staleness caps**, not a fetch cadence. *There is no
background poller* — every query is issued because someone opened a page, so an
idle deployment issues no queries at all. The names match the wording in
`req.txt` §6; `NOTES.md` §7a records why no poller was built.

Consequence to be aware of: an operator can see data up to that old. Lower
`live-seconds` if someone complains a fresh failure is not showing yet.

**`spring.cache.type=none` does not disable this cache.** The app declares its own
`@Primary` cache manager, which wins over the auto-configuration that property
drives. Use `ajs.polling.cache-enabled=false`.

---

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `AJS schema drift detected` | Host column layouts differ from the mapper, **or** the profile lacks `*USE` | Read the listed columns; `DSPFFD` the file; fix authority or the mapper |
| `The application requester cannot establish the connection` | Port 8471 unreachable, or wrong host | `nc -vz <host> 8471`; check firewall/VPN |
| `SQLState 08004` on connect | Credentials rejected at signon | The DRDA path works; the user/password is wrong |
| `SQL0181` / `CPD4019` | A date/timestamp comparison reached SQL | A regression in date handling — the app encodes CYYMMDD in Java on purpose |
| Startup fails with a JWT message | `JWT_SECRET` unset or shorter than 32 bytes | `export JWT_SECRET=$(openssl rand -base64 48)` |
| `no main manifest attribute` | Ran the `-plain.jar` | Use `ajs-observability-0.1.0-SNAPSHOT.jar` by name |
| App starts but dashboards are empty | Profile lacks authority; or you are on the `local` profile against no host | Check the log for drift/authority warnings |
| Sign-in pages work but dashboards fail | The IBM i is unreachable | Deliberate: auth is served from local SQLite so you can still administer |

---

## What is still unproven

This has never been run against a real AJS partition. Stated plainly so nobody
over-trusts it:

- The `QAIJSMST` / `QAIJSHST` / `QAIJS1CM` column layouts are **authored from
  documentation**, not extracted (risk R1).
- Real `CYYMMDD`/`YYMMDD` encodings and `9999999`/`0` sentinel behaviour per
  release (R2).
- The jt400 DRDA path end to end against AJS specifically (R5).
- Whether `QAIJSHST.HSDTE` has an access path on your partition.
- Actual DB2 for i timings.

**The highest-value next step is one read-only afternoon against one real
partition.** `docs/host-capture/` is the script for exactly that session. See
`NOTES.md` §12 for the full risk register and §12b for what a host session can
retire.

---

## Documentation map

| File | Contents |
| --- | --- |
| `README.md` (this file) | Setup, build, test, run against iSeries |
| `NOTES.md` | Design record: decisions, risks, measurements, screenshots |
| `req.txt` | Original requirements and open questions |
| `docs/host-capture/` | Scripted session to validate against a real partition |
| `docs/tools/README.md` | Regenerating the documentation screenshots |
| `docs/screenshots/` | The seventeen figures referenced by `NOTES.md` |

---

## License / status

Version `0.1.0-SNAPSHOT`. Read-only by design; see `NOTES.md` §11 for why hold,
release and submit are out of scope rather than missing.