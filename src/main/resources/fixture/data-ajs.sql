-- ============================================================================
-- Seed data for the offline test tier.
--
-- Every row is crafted to exercise a specific documented AJS semantic from
-- req.txt §2/§3 rather than to look realistic:
--
--   PAYROLL1   normal last run, next run 2025-11-03      -> happy path
--   PAYROLL2   last run ABNORMAL, no recovery            -> genuine failure
--   PAYROLL3   last run ABNORMAL, recovery *NOSBM        -> NOT a failure
--              (system down at run time; flagging it is a false alarm)
--   ARCHIVE1   held, never run (JMLRD=0)                 -> held + never
--   REPORT1    never run, no next date (JMDTE=9999999)   -> unscheduled
--   REPORT2    last run in 1997 (YYMMDD 971231)          -> YYMMDD pivot
--   LEGACY1    next run 1999 (CYYMMDD 0991231)           -> century flag 0
--   BATCH1     no JMLCC at all (NULL)                    -> UNKNOWN outcome
--
-- Dates are relative to nothing: they are fixed so tests are deterministic.
-- 1251103 = 2025-11-03 in CYYMMDD.
--
-- The DELETE statements make seeding idempotent. The in-memory database uses
-- DB_CLOSE_DELAY=-1 so it survives between Spring context loads; without these
-- deletes a second context (e.g. the MockMvc test class) re-runs this script
-- and hits the primary key.
-- ============================================================================

DELETE FROM QUSRIJS.QAIJS1CM;
DELETE FROM QUSRIJS.QAIJSHST;
DELETE FROM QUSRIJS.QAIJSMST;

INSERT INTO QUSRIJS.QAIJSMST
  (JMJOB, JMSEQ, JMGRP, JMAPPL, JMTEXT, JMSCDNAM, JMSCH, JMCAL, JMHCAL, JMFCAL,
   JMDTE, JMTME, JMJOBQ, JMJBQL, JMJOBD, JMJBDL, JMUSER, JMMSGQ, JMMSGL, JMRCYA,
   JMSTS, JMLRD, JMLST, JMLCC, JMELAP)
VALUES
  ('PAYROLL1', 1, 'NIGHTLY', 'FINANCE', 'Nightly payroll extract',
   'NIGHTLY', '*DAILY', 'STDCAL', 'HOLCAL', 'FISCAL',
   1251103, 230000, 'QBATCH', 'QGPL', 'PAYJOBD', 'QGPL', 'PAYUSER', 'QSYSOPR', 'QGPL', '*SBMJOB',
   '*READY', 251102, 'Completed normally', 'N', 142),

  ('PAYROLL2', 1, 'NIGHTLY', 'FINANCE', 'Payroll GL posting',
   'NIGHTLY', '*DAILY', 'STDCAL', 'HOLCAL', 'FISCAL',
   1251103, 231000, 'QBATCH', 'QGPL', 'PAYJOBD', 'QGPL', 'PAYUSER', 'QSYSOPR', 'QGPL', '*SBMJOB',
   '*READY', 251102, 'Ended abnormally', 'A', 88),

  ('PAYROLL3', 1, 'NIGHTLY', 'FINANCE', 'Payroll interface send',
   'NIGHTLY', '*DAILY', 'STDCAL', 'HOLCAL', 'FISCAL',
   1251103, 232000, 'QBATCH', 'QGPL', 'PAYJOBD', 'QGPL', 'PAYUSER', 'QSYSOPR', 'QGPL', '*NOSBM',
   '*READY', 251102, 'Not submitted - system unavailable', 'A', 0),

  ('ARCHIVE1', 1, 'WEEKLY', 'OPS', 'Archive transaction log',
   'WEEKLY', '*WEEKLY', 'STDCAL', 'HOLCAL', 'FISCAL',
   1251109, 010000, 'QBATCH', 'QGPL', 'ARCJOBD', 'QGPL', 'OPSUSER', 'QSYSOPR', 'QGPL', '*SBMJOB',
   '*HELD', 0, '', NULL, NULL),

  ('REPORT1', 1, 'MONTHLY', 'REPORTING', 'Monthly management pack',
   'MONTHLY', '*MONTHLY', 'STDCAL', 'HOLCAL', 'FISCAL',
   9999999, 0, 'QREPORT', 'QGPL', 'RPTJOBD', 'QGPL', 'RPTUSER', 'QSYSOPR', 'QGPL', '*SBMJOB',
   '*READY', 0, '', NULL, NULL),

  ('REPORT2', 1, 'MONTHLY', 'REPORTING', 'Legacy annual report',
   'ANNUAL', '*ANNUAL', 'STDCAL', 'HOLCAL', 'FISCAL',
   1251231, 060000, 'QREPORT', 'QGPL', 'RPTJOBD', 'QGPL', 'RPTUSER', 'QSYSOPR', 'QGPL', '*SBMJOB',
   '*READY', 971231, 'Completed normally', 'N', 3610),

  ('LEGACY1', 1, 'ARCHIVE', 'OPS', 'Millennium-era cleanup',
   'ANNUAL', '*ANNUAL', 'STDCAL', 'HOLCAL', 'FISCAL',
   0991231, 235900, 'QBATCH', 'QGPL', 'ARCJOBD', 'QGPL', 'OPSUSER', 'QSYSOPR', 'QGPL', '*SBMJOB',
   '*READY', 991231, 'Completed normally', 'N', 12),

  ('BATCH1', 1, 'NIGHTLY', 'OPS', 'Batch close',
   'NIGHTLY', '*DAILY', 'STDCAL', 'HOLCAL', 'FISCAL',
   1251103, 220000, 'QBATCH', 'QGPL', 'BATJOBD', 'QGPL', 'OPSUSER', 'QSYSOPR', 'QGPL', '*SBMJOB',
   '*READY', 251101, 'Still running at cutoff', NULL, NULL);

INSERT INTO QUSRIJS.QAIJS1CM (CMJOB, CMSEQ, CMCMD) VALUES
  ('PAYROLL1', 1, 'CALL PGM(PAYLIB/PAYEXT) PARM(''DAILY'')'),
  ('PAYROLL1', 2, 'CPYF FROMFILE(PAYLIB/PAYSTG) TOFILE(PAYLIB/PAYARC) MBROPT(*ADD)'),
  ('PAYROLL2', 1, 'CALL PGM(PAYLIB/GLLOAD)'),
  ('ARCHIVE1', 1, 'SAVLIB LIB(APPLIB) DEV(TAP01) ENDOPT(*LEAVE)'),
  ('REPORT2', 1, 'CALL PGM(RPTLIB/ANNREP)');

-- ----------------------------------------------------------------------------
-- Run history (QAIJSHST) — drives the run-timeline view.
--
-- Shape: one row per job *run*. HSDTE is 7-digit CYYMMDD, HSTME is HHMMSS,
-- HSELAP is elapsed seconds. Durations and clock times are chosen so the
-- timeline has something worth looking at: a nightly batch window that
-- overlaps, one long-running job, one failure that is retried successfully,
-- and enough spread across the day to exercise every time scale.
--
-- Window covered: 2025-11-20 00:00 through 2025-11-21 00:00.
--
-- NOTE: whether the real QAIJSHST carries one row per job run or one row per
-- *step* within a run is NOT confirmed (risk R1 — these layouts are not a
-- published contract). This fixture assumes per-run. Verify with DSPFFD
-- against a real partition before the timeline SQL shape hardens.
--
-- HSJOB values are constrained to job names that exist in QAIJSMST above,
-- because a history row for an unknown job would be silently undisplayable
-- on the timeline (the gutter is built from the master file).
-- ----------------------------------------------------------------------------
INSERT INTO QUSRIJS.QAIJSHST (HSJOB, HSSEQ, HSDTE, HSTME, HSCC, HSELAP) VALUES
  -- --- 2025-11-19: prior-day context (visible when the window is widened) ---
  ('PAYROLL1', 1, 1251119, 230000, 'N', 138),
  ('PAYROLL2', 1, 1251119, 231000, 'N',  86),
  ('ARCHIVE1', 1, 1251119,  13000, 'N', 2830),
  ('REPORT2',  1, 1251119,  60000, 'N',  402),

  -- --- 2025-11-20: the main window -----------------------------------------
  -- Nightly payroll cluster: starts 23:00 and runs past midnight.
  ('PAYROLL1', 1, 1251120, 230000, 'N', 142),
  ('PAYROLL2', 1, 1251120, 231000, 'A',  88),   -- genuine failure
  ('PAYROLL2', 1, 1251120, 231500, 'N',  91),   -- retry after the failure
  ('BATCH1',   1, 1251120, 233000, 'N', 192),

  -- Long-running archive: 47 minutes, the widest bar in the window.
  ('ARCHIVE1', 1, 1251120,  13000, 'N', 2830),

  -- Daytime reporting, spread so the 6-hour and day scales both look sane.
  ('REPORT2',  1, 1251120,  60000, 'N',  390),
  ('REPORT2',  1, 1251120, 180000, 'N',  371),
  ('LEGACY1',  1, 1251120,  83000, 'N',  215),
  ('PAYROLL1', 1, 1251120, 120000, 'N',  137),

  -- A short job that repeats often: exercises the 15-minute scale.
  ('BATCH1',   1, 1251120,  60000, 'N',   44),
  ('BATCH1',   1, 1251120,  90000, 'N',   39),
  ('BATCH1',   1, 1251120, 150000, 'N',   51),
  ('BATCH1',   1, 1251120, 210000, 'N',   42),

  -- --- 2025-11-21: next day, for a multi-day window -------------------------
  ('PAYROLL1', 1, 1251121, 230000, 'N', 145),
  ('ARCHIVE1', 1, 1251121,  13000, 'N', 2791),
  ('REPORT2',  1, 1251121,  60000, 'N',  388);
