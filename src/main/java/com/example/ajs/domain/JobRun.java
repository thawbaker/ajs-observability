package com.example.ajs.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * One historical execution of an AJS job, as read from {@code QUSRIJS.QAIJSHST}.
 *
 * <p>Field names mirror the IBM i column names, as with {@link AjsJob}, so the
 * mapping stays reviewable against {@code DSPFFD} output when a release changes
 * a layout (risk R1).
 *
 * <p>This record exists to serve the run-timeline view: every run is a bar whose
 * left edge is {@link #startedAt()} and whose width is {@link #duration()}.
 */
public record JobRun(
        // --- identity -------------------------------------------------------
        String jobName,   // HSJOB
        int sequence,     // HSSEQ

        // --- when it ran (decoded from CYYMMDD / HHMMSS) ---------------------
        LocalDateTime startedAt, // HSDTE + HSTME

        // --- how it ended ---------------------------------------------------
        String completionCode,   // HSCC, 'N' / 'A' / blank
        Integer elapsedSeconds   // HSELAP
) {

    /**
     * A run with no elapsed time is one that had not finished when the row was
     * written — AJS has no separate "in progress" flag. Treating this as
     * "running" rather than as zero duration is what stops an in-flight job
     * from rendering as an instantaneous success on the timeline, which would
     * be a false all-clear.
     */
    public boolean stillRunning() {
        return elapsedSeconds == null || elapsedSeconds == 0;
    }

    /**
     * Duration of the run. Empty while the run is still in progress, because a
     * half-finished job's elapsed time is not a duration we should plot as fact.
     */
    public Optional<Duration> duration() {
        return stillRunning()
                ? Optional.empty()
                : Optional.of(Duration.ofSeconds(elapsedSeconds));
    }

    /** Outcome of this individual run, reusing {@link AjsJob.LastOutcome}. */
    public AjsJob.LastOutcome outcome() {
        if (completionCode == null) {
            return AjsJob.LastOutcome.UNKNOWN;
        }
        return switch (completionCode.trim().toUpperCase()) {
            case "N" -> AjsJob.LastOutcome.NORMAL;
            case "A" -> AjsJob.LastOutcome.ABNORMAL;
            default -> AjsJob.LastOutcome.UNKNOWN;
        };
    }

    /** Elapsed seconds, or empty, formatted as {@code 2m 22s} for display. */
    public String durationDisplay() {
        return duration().map(d -> {
            long total = d.getSeconds();
            long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
            StringBuilder sb = new StringBuilder();
            if (h > 0) sb.append(h).append("h ");
            if (h > 0 || m > 0) sb.append(m).append("m ");
            return sb.append(s).append('s').toString();
        }).orElse("running");
    }
}
