-- 01 — the real column set of the three AJS files.
--
-- Uses QSYS2.SYSCOLUMNS, which is present on every supported release and needs
-- only *USE on the files. DSPFFD output would also work but is harder to diff
-- mechanically, so this is the query to run.
--
-- Expected result: three files' worth of rows. Save as out/01-columns.txt.
-- Do not edit the output. An invented column and a real one look identical once
-- someone has tidied them.

SELECT TABLE_NAME,
       ORDINAL_POSITION,
       COLUMN_NAME,
       DATA_TYPE,
       LENGTH,
       NUMERIC_PRECISION,
       NUMERIC_SCALE,
       IS_NULLABLE,
       CCSID
FROM   QSYS2.SYSCOLUMNS
WHERE  TABLE_SCHEMA = 'QUSRIJS'
  AND  TABLE_NAME IN ('QAIJSMST', 'QAIJSHST', 'QAIJS1CM')
ORDER  BY TABLE_NAME, ORDINAL_POSITION;
