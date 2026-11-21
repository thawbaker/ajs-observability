-- ============================================================================
-- Offline stand-in for the AJS physical files in library QUSRIJS.
--
-- This DDL is AUTHORED, not extracted from a real IBM i. It encodes the column
-- set and semantics documented in req.txt §2, which are themselves derived from
-- IBM support notes and are NOT a published contract (risk R1).
--
-- Consequence to be honest about: passing tests against this schema proves the
-- application logic and SQL shape are sound. It does NOT prove the real
-- QAIJSMST has these columns. Before Phase 1 exits, run DSPFFD against a real
-- partition and diff it against this file -- that diff is the deliverable that
-- retires risk R1.
--
-- Types chosen to mirror DB2 for i reported types:
--   * packed numeric (INT) for the date/time fields -> exposes the null and
--     sentinel handling that causes SQL0181/CPD4019 (risk R2).
--   * fixed-width CHAR, blank-padded, for identifiers -> exercises trimming.
-- ============================================================================

-- H2 requires the schema to exist before schema-qualified DDL; verified.
CREATE SCHEMA IF NOT EXISTS QUSRIJS;

CREATE TABLE IF NOT EXISTS QUSRIJS.QAIJSMST (
    JMJOB    CHAR(10)     NOT NULL,   -- job name
    JMSEQ    INTEGER      NOT NULL,   -- sequence
    JMGRP    CHAR(10),                -- job group
    JMAPPL   CHAR(10),                -- application group
    JMTEXT   CHAR(50),                -- description
    JMSCDNAM CHAR(10),                -- schedule name
    JMSCH    CHAR(10),                -- schedule code
    JMCAL    CHAR(10),                -- schedule calendar
    JMHCAL   CHAR(10),                -- holiday calendar
    JMFCAL   CHAR(10),                -- fiscal calendar
    JMDTE    INTEGER,                 -- next run date, CYYMMDD, 9999999 = none
    JMTME    INTEGER,                 -- next run time, HHMMSS
    JMJOBQ   CHAR(10),                -- job queue
    JMJBQL   CHAR(10),                -- job queue library
    JMJOBD   CHAR(10),                -- job description
    JMJBDL   CHAR(10),                -- job description library
    JMUSER   CHAR(10),                -- run-as user profile
    JMMSGQ   CHAR(10),                -- message queue
    JMMSGL   CHAR(10),                -- message queue library
    JMRCYA   CHAR(10),                -- recovery action
    JMSTS    CHAR(10),                -- status: *READY / *HELD
    JMLRD    INTEGER,                 -- last run date, YYMMDD, 0 = never
    JMLST    CHAR(50),                -- last run status text
    JMLCC    CHAR(1),                 -- last completion code: N / A
    JMELAP   INTEGER,                 -- last elapsed seconds
    PRIMARY KEY (JMJOB, JMSEQ)
);

CREATE TABLE IF NOT EXISTS QUSRIJS.QAIJS1CM (
    CMJOB    CHAR(10)     NOT NULL,   -- joins to QAIJSMST.JMJOB
    CMSEQ    INTEGER      NOT NULL,   -- sequence within the job
    CMCMD    CHAR(200),               -- command text
    PRIMARY KEY (CMJOB, CMSEQ)
);

-- History / run records — one row per job run. Column set trimmed to what the
-- timeline view needs; the real QAIJSHST carries the date encoding that triggers
-- CPD4019, which is why HSDTE is modelled as packed numeric here rather than a
-- native DATE.
--
-- The key is (HSJOB, HSSEQ, HSDTE, HSTME) and NOT (HSJOB, HSSEQ). The narrower
-- key was wrong and is worth recording: it permits exactly one history row per
-- job for all time, which makes a run timeline impossible to populate -- the
-- second run of any job collides on the primary key. Including the run date and
-- time is the minimum that allows history.
--
-- This is an AUTHORED schema (see the file header), so it encodes our best
-- understanding of a file we cannot yet inspect. The real key must be confirmed
-- with DSPFFD against a partition (risk R1) before the timeline SQL hardens.
--
-- HSSEQ matches the meaning of JMSEQ/CMSEQ: the position of a submission in a
-- job's sequence, not a unique run identifier.
CREATE TABLE IF NOT EXISTS QUSRIJS.QAIJSHST (
    HSJOB    CHAR(10)     NOT NULL,
    HSSEQ    INTEGER      NOT NULL,
    HSDTE    INTEGER      NOT NULL,   -- run date, CYYMMDD
    HSTME    INTEGER      NOT NULL,   -- run time, HHMMSS
    HSCC     CHAR(1),                 -- completion code
    HSELAP   INTEGER,                 -- elapsed seconds, 0 / NULL = still running
    PRIMARY KEY (HSJOB, HSSEQ, HSDTE, HSTME)
);
