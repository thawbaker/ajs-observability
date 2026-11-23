# Host capture — one session, scripted

The IBM i is on an internal network and is not reachable from where this was
built. Everything in `README.md` about the AJS layer rests on an **authored**
schema. This directory is the script for the one session that can retire that
assumption, written in advance so the session is execute-and-capture rather than
explore-and-hope.

Nothing here runs offline. It is SQL to be run against a real partition, and the
output is committed back into this directory so the next person can diff the
fixture against reality without needing the host.

## What to bring

- Read-only credentials for a profile with `*USE` on `QUSRIJS` and no more.
- An SQL client: **STRSQL** or **ACS Run SQL Scripts** on the host, or `jt400`
  from this application's server. Any of the three works; ACS is easiest to
  export from.
- 30–60 minutes. This is deliberately short.

## Run order

| # | File | Produces | Answers |
| --- | --- | --- | --- |
| 1 | `01-columns.sql` | `out/01-columns.txt` | The real column set of the three files (R1) |
| 2 | `02-keys.sql` | `out/02-keys.txt` | The real `QAIJSHST` key — the invented assumption |
| 3 | `03-date-encodings.sql` | `out/03-date-encodings.txt` | Real `CYYMMDD`/`YYMMDD` values and sentinels (R2) |
| 4 | `04-sample-rows.sql` | `out/04-sample-rows.txt` | Real rows to seed a *real* fixture from |
| 5 | `05-drda.sql` | `out/05-drda.txt` | That the app's own driver path works (R5) |

Run them in order. If step 1 fails on authority, stop — the rest will too, and
the failure is itself the answer to risk R3.

## Then, back on this machine

```bash
# Diff the real columns against the authored fixture.
docs/host-capture/diff-against-fixture.sh out/01-columns.txt
```

That script is the deliverable that retires R1. It reports, per file, columns
the fixture invents and columns the real file has that the fixture lacks. Anything
in the first category is a column the application reads but the host does not
have — a guaranteed runtime failure. Anything in the second category is a column
the application ignores.

## After the session

1. Commit `out/` verbatim. Do not tidy it; raw output is the evidence.
2. If the column sets differ, update `src/main/resources/fixture/schema-ajs.sql`
   to match the host and keep the old version as `schema-ajs-authored.sql` so the
   change is visible in history.
3. If `04-sample-rows.sql` produced usable rows, seed a **real** fixture beside
   the authored one — `data-ajs-real.sql` — and name it as an extraction in its
   header. An extracted fixture and an invented one must never be confused.
4. Re-run `./gradlew test` and update `README.md` section 15's "cannot prove offline"
   table with what is now proven.

## What this session cannot do

It cannot validate AJS's *behaviour* — whether a job that shows `*HELD` is really
held, whether `*NOSBM` means what the docs say. Those are readings of the data,
not the schema. One afternoon settles the schema; it does not settle the product.
