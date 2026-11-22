-- 04 — a handful of real rows to seed an extracted fixture from.
--
-- Keep this small and pick deliberately: one normal job, one held, one abnormal,
-- one never-run, one with a *NOSBM recovery action, and enough history for one
-- job to draw a timeline. Twenty rows is plenty — the fixture's size is not the
-- point, its authenticity is.
--
-- Save as out/04-sample-rows.txt. These become data-ajs-real.sql, labelled as an
-- extraction. Do not edit them to look nicer.

SELECT * FROM QUSRIJS.QAIJSMST
WHERE  JMJOB IN (
         SELECT JMJOB FROM QUSRIJS.QAIJSMST ORDER BY JMJOB FETCH FIRST 8 ROWS ONLY
       )
ORDER  BY JMJOB;

-- Command sequences for those jobs. Note CMSEQ vs JMSEQ — the join is CMJOB = JMJOB.
SELECT * FROM QUSRIJS.QAIJS1CM
WHERE  CMJOB IN (
         SELECT JMJOB FROM QUSRIJS.QAIJSMST ORDER BY JMJOB FETCH FIRST 8 ROWS ONLY
       )
ORDER  BY CMJOB, CMSEQ;

-- Recent history for the same jobs.
SELECT * FROM QUSRIJS.QAIJSHST
WHERE  HSJOB IN (
         SELECT JMJOB FROM QUSRIJS.QAIJSMST ORDER BY JMJOB FETCH FIRST 8 ROWS ONLY
       )
ORDER  BY HSDTE DESC, HSTME DESC
FETCH  FIRST 40 ROWS ONLY;
