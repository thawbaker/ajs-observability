-- 03 — real CYYMMDD / YYMMDD values and the sentinels.
--
-- Run these with the raw numeric columns shown, NOT formatted. The whole point is
-- to see what AJS actually stores, so do not let the client "helpfully" render a
-- date. In ACS, select the columns as plain integers.
--
-- Save as out/03-date-encodings.txt.

-- The next-run date column as stored, plus the same value as a number.
SELECT JMJOB, JMDTE, JMLRD, JMTME, JMSTS, JMLCC, JMRCYA
FROM   QUSRIJS.QAIJSMST
ORDER  BY JMJOB
FETCH  FIRST 40 ROWS ONLY;

-- Sentinels: how many jobs carry the "no next run" value, and in what form.
SELECT JMDTE, COUNT(*) AS JOBS
FROM   QUSRIJS.QAIJSMST
GROUP  BY JMDTE
ORDER  BY JOBS DESC
FETCH  FIRST 20 ROWS ONLY;

-- Never-run: is "never" 0, null, or something else?
SELECT JMLRD, COUNT(*) AS JOBS
FROM   QUSRIJS.QAIJSMST
GROUP  BY JMLRD
ORDER  BY JOBS DESC
FETCH  FIRST 20 ROWS ONLY;

-- History dates in raw form, to check HSDTE's encoding matches JMDTE's.
SELECT HSJOB, HSDTE, HSTME, HSCC, HSELAP
FROM   QUSRIJS.QAIJSHST
ORDER  BY HSDTE DESC, HSTME DESC
FETCH  FIRST 40 ROWS ONLY;
