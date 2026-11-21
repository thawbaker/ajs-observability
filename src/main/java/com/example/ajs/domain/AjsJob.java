package com.example.ajs.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

/**
 * One AJS job definition as read from {@code QUSRIJS.QAIJSMST}, joined with the
 * per-job command list from {@code QUSRIJS.QAIJS1CM}.
 *
 * <p>Field names mirror the IBM i column names so that the mapping from SQL to
 * this record stays reviewable against {@code DSPFFD} output. That reviewability
 * is the point: risk R1 says these layouts are not a published contract, so the
 * mapping must be easy to eyeball when a release changes something.
 *
 * <p>Raw legacy date values are decoded here once, at the boundary, so no
 * template or controller ever touches CYYMMDD arithmetic.
 */
public record AjsJob(
        // --- identity -------------------------------------------------------
        String jobName,          // JMJOB
        int sequence,            // JMSEQ
        String jobGroup,         // JMGRP
        String applicationGroup, // JMAPPL
        String description,      // JMTEXT

        // --- schedule -------------------------------------------------------
        String scheduleName,     // JMSCDNAM
        String scheduleCode,     // JMSCH
        String calendar,         // JMCAL
        String holidayCalendar,  // JMHCAL
        String fiscalCalendar,   // JMFCAL

        // --- next run (decoded from CYYMMDD) --------------------------------
        Optional<LocalDate> nextRunDate, // JMDTE
        Optional<LocalTime> nextRunTime, // JMTME

        // --- execution environment -----------------------------------------
        String jobQueue,         // JMJOBQ
        String jobQueueLibrary,  // JMJBQL
        String jobDescription,   // JMJOBD
        String jobDescriptionLib,// JMJBDL
        String runAsUser,        // JMUSER
        String messageQueue,     // JMMSGQ
        String messageQueueLib,  // JMMSGL
        String recoveryAction,   // JMRCYA

        // --- current state --------------------------------------------------
        String status,           // JMSTS  (*READY / *HELD)

        // --- last run summary (decoded from YYMMDD) -------------------------
        Optional<LocalDate> lastRunDate, // JMLRD
        String lastRunStatus,            // JMLST
        String lastCompletionCode,       // JMLCC  ('N' / 'A')
        Integer lastElapsedSeconds       // JMELAP
) {

    /** {@code JMSTS} value for a job that is active and will be submitted. */
    public static final String STATUS_READY = "*READY";

    /** {@code JMSTS} value for a held job. */
    public static final String STATUS_HELD = "*HELD";

    /**
     * Reduces {@code JMLCC} to a tri-state so the UI never has to reason about
     * raw character codes.
     */
    public enum LastOutcome {
        /** Job last completed normally ({@code JMLCC = 'N'}). */
        NORMAL,
        /** Job last completed abnormally ({@code JMLCC = 'A'}) — a real failure. */
        ABNORMAL,
        /** Job has never completed, or the code was blank/unrecognised. */
        UNKNOWN
    }

    public boolean held() {
        return STATUS_HELD.equalsIgnoreCase(status);
    }

    public boolean ready() {
        return STATUS_READY.equalsIgnoreCase(status);
    }

    public boolean neverRun() {
        return lastRunDate.isEmpty();
    }

    public LastOutcome lastOutcome() {
        if (lastCompletionCode == null) {
            return LastOutcome.UNKNOWN;
        }
        return switch (lastCompletionCode.trim().toUpperCase()) {
            case "N" -> LastOutcome.NORMAL;
            case "A" -> LastOutcome.ABNORMAL;
            default -> LastOutcome.UNKNOWN;
        };
    }

    /**
     * Whether AJS's recovery action means this job would have been skipped
     * rather than failed.
     *
     * <p>From {@code req.txt} §3: a job with recovery {@code *NOSBM} that is
     * skipped because the system was down is <em>not</em> a failure of the job.
     * Surfacing those as failures produces false alarms, which destroys trust in
     * the dashboard faster than missing a real failure.
     */
    public boolean skippedRatherThanFailed() {
        return recoveryAction != null && recoveryAction.trim().equalsIgnoreCase("*NOSBM");
    }

    /**
     * A job is a genuine failure worth alerting on when it completed abnormally
     * and was not merely skipped by its recovery action.
     */
    public boolean genuineFailure() {
        return lastOutcome() == LastOutcome.ABNORMAL && !skippedRatherThanFailed();
    }

    /** Formatted next-run date for display, {@code —} when unscheduled. */
    public String nextRunDateDisplay() {
        return AjsDateDecoders.display(nextRunDate, "—");
    }

    /** Formatted last-run date for display, {@code Never} when never run. */
    public String lastRunDateDisplay() {
        return AjsDateDecoders.display(lastRunDate, "Never");
    }
}
