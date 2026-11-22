-- 02 — the real key of QAIJSHST, and whether it is per-run or per-step.
--
-- This is the single most load-bearing unknown in the app. The fixture assumes
-- PRIMARY KEY (HSJOB, HSSEQ, HSDTE, HSTME) and one row per run. The original
-- authored key was (HSJOB, HSSEQ), which could not hold history at all. If the
-- real file is keyed differently, or holds one row per STEP rather than per run,
-- the timeline's bars are wrong in a way H2 can never reveal.
--
-- Save as out/02-keys.txt.

-- Constraints and their columns.
SELECT c.CONSTRAINT_NAME,
       c.CONSTRAINT_TYPE,
       c.TABLE_NAME,
       k.COLUMN_NAME,
       k.ORDINAL_POSITION
FROM   QSYS2.SYSCST c
JOIN   QSYS2.SYSKEYCST k
       ON  k.CONSTRAINT_SCHEMA = c.CONSTRAINT_SCHEMA
       AND k.CONSTRAINT_NAME   = c.CONSTRAINT_NAME
WHERE  c.TABLE_SCHEMA = 'QUSRIJS'
  AND  c.TABLE_NAME IN ('QAIJSMST', 'QAIJSHST', 'QAIJS1CM')
ORDER  BY c.TABLE_NAME, c.CONSTRAINT_NAME, k.ORDINAL_POSITION;

-- Rows per job in history: 1 per job means "one row per run" is wrong.
SELECT HSJOB, COUNT(*) AS ROWS_PER_JOB
FROM   QUSRIJS.QAIJSHST
GROUP  BY HSJOB
ORDER  BY ROWS_PER_JOB DESC
FETCH  FIRST 20 ROWS ONLY;
