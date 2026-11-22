package com.example.ajs.web;

import com.example.ajs.domain.AjsJob;
import com.example.ajs.domain.JobRun;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Percentage arithmetic for the run-timeline chart, exposed to Thymeleaf as
 * {@code @timelineView}.
 *
 * <p>This exists as a bean rather than as expressions inside the template for two
 * reasons. It is arithmetic, and arithmetic buried in a Thymeleaf attribute cannot
 * be unit-tested — the first draft of the template tried and produced an
 * unreadable nested {@code BigDecimal} expression. And the window bounds are
 * needed by every bar, so doing it here keeps one definition of "where is this
 * run on the axis".
 *
 * <p>Percentages are relative to the window, so the same run renders at different
 * widths as the window changes — which is the point of the view.
 */
@Component("timelineView")
public class TimelineViewHelper {

    /**
     * Minimum bar width, as a percentage of the window.
     *
     * <p>Without a floor, a short run in a wide window rounds to zero pixels and
     * vanishes, so a job that did run looks like a job that did not. The floor is
     * a deliberate visual lie in favour of visibility: every run stays clickable
     * and countable, and the tooltip and table carry the true duration.
     */
    static final double MIN_WIDTH_PCT = 0.35;

    /**
     * A bar that is still running is drawn to the right edge of the window: it has
     * no known end, and guessing one would misrepresent it. Clamped to the window
     * so a long-running job cannot overflow the plot when the window starts after
     * it began.
     */
    private static final double RUNNING_WIDTH_PCT = 100.0;

    /** Left offset of a run's start, as a percentage of the window. */
    public double leftPercent(LocalDateTime from, LocalDateTime to, LocalDateTime at) {
        long window = windowSeconds(from, to);
        long offset = Duration.between(from, at).getSeconds();
        double pct = (offset * 100.0) / window;
        // Clamp: a run that began before the window is drawn from the left edge
        // rather than off-screen, so it is visibly "started earlier".
        return clamp(pct, 0.0, 100.0);
    }

    /**
     * Bar width for a run, as a percentage of the window.
     *
     * <p>A still-running job extends to the right edge. A finished one is its true
     * duration, subject to {@link #MIN_WIDTH_PCT}.
     */
    public double widthPercent(LocalDateTime from, LocalDateTime to, JobRun run) {
        if (run == null || run.startedAt() == null) {
            return 0.0;
        }
        if (run.stillRunning()) {
            return Math.max(MIN_WIDTH_PCT, RUNNING_WIDTH_PCT - leftPercent(from, to, run.startedAt()));
        }
        double pct = run.duration()
                .map(d -> (d.getSeconds() * 100.0) / windowSeconds(from, to))
                .orElse(MIN_WIDTH_PCT);
        return Math.max(MIN_WIDTH_PCT, Math.min(pct, 100.0));
    }

    /**
     * CSS class for a run's bar.
     *
     * <p>Decided here rather than as a nested ternary in the template: Thymeleaf's
     * expression parser rejects the parenthesised nested conditional outright, and
     * the outcome-to-colour mapping is a rule worth having in one tested place
     * anyway.
     */
    public String barClass(JobRun run) {
        if (run == null) {
            return "tl-normal";
        }
        if (run.stillRunning()) {
            return "tl-running";
        }
        if (run.outcome() == AjsJob.LastOutcome.ABNORMAL) {
            return "tl-abnormal";
        }
        return isLongRunning(run) ? "tl-long" : "tl-normal";
    }

    /**
     * Threshold above which a completed run is drawn as long-running.
     *
     * <p>An hour is the threshold because of what the view is for. It separates
     * "finished as expected" from "finished, but late enough to wonder" — the
     * nightly extract that normally takes two minutes and took fifty is worth
     * seeing at a glance, and on a timeline it is also the bar that will dominate
     * the plot and crowd its neighbours.
     *
     * <p>Deliberately a constant rather than a per-job expectation: AJS does not
     * record a job's expected duration in the master file, so any per-job
     * threshold would be invented. One blunt line that is honestly the same for
     * every job beats a per-job rule that looks authoritative and is not.
     */
    static final long LONG_RUNNING_SECONDS = 3600;

    /**
     * Whether a completed run took long enough to be worth flagging.
     *
     * <p>Not applicable to a still-running job, which has no duration to compare
     * and is already drawn distinctly.
     */
    public boolean isLongRunning(JobRun run) {
        if (run == null || run.stillRunning()) {
            return false;
        }
        return run.elapsedSeconds() != null && run.elapsedSeconds() > LONG_RUNNING_SECONDS;
    }

    /**
     * Tag class for the run's outcome in the detail table, kept consistent with
     * {@link #barClass} so the chart and the table never disagree.
     */
    public String outcomeTagClass(JobRun run) {
        if (run == null) {
            return "";
        }
        if (run.stillRunning()) {
            return "ok";
        }
        return switch (run.outcome()) {
            case ABNORMAL -> "bad";
            case NORMAL -> "ok";
            case UNKNOWN -> "";
        };
    }

    /** Human-readable outcome, matching the tag class above. */
    public String outcomeLabel(JobRun run) {
        if (run == null) {
            return "—";
        }
        if (run.stillRunning()) {
            return "Running";
        }
        return switch (run.outcome()) {
            case ABNORMAL -> "Abnormal";
            case NORMAL -> "Normal";
            case UNKNOWN -> "Unknown";
        };
    }

    /**
     * Formats a run's gap from the previous run for display, or an em dash when
     * there is no measurable prior end (first run, or a prior run still going).
     */
    public String gapDisplay(Long seconds) {
        if (seconds == null) {
            return "—";
        }
        long s = seconds;
        if (s < 60) {
            return s + "s";
        }
        long h = s / 3600, m = (s % 3600) / 60;
        if (h > 0) {
            return m > 0 ? h + "h " + m + "m" : h + "h";
        }
        return m + "m " + (s % 60) + "s";
    }

    /** Rounds to two places so the emitted CSS stays short and stable. */
    public double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Groups digits for display, e.g. {@code 1440 -> "1,440"}.
     *
     * <p>Used by the run-count figures. A five-digit run count is common on a wide
     * window and ungrouped digits are genuinely hard to read at a glance, which
     * matters here: the whole point of these numbers is that the reader notices
     * when a total is large.
     */
    public String thousands(long value) {
        return NumberFormat.getIntegerInstance(Locale.UK).format(value);
    }

    /**
     * Colour for a lane, derived from the job's application group.
     *
     * <p>Hue is a hash of the group name, so every job in one group shares a
     * colour and the colour is stable across requests without being stored
     * anywhere. Stability is the point: a lane that changed colour between page
     * loads would make the chart re-read from scratch every time, which defeats the
     * purpose of a dense view.
     *
     * <p>Returns an HSL triple at a fixed saturation and lightness rather than an
     * arbitrary hex, so every lane colour has the same weight against the dark
     * background and none of them can come out unreadable.
     */
    public String laneColor(String applicationGroup) {
        String key = applicationGroup == null || applicationGroup.isBlank()
                ? "(ungrouped)"
                : applicationGroup;
        // Math.abs on a possible Integer.MIN_VALUE would still be negative, hence
        // the mask rather than abs. Golden-ratio stride spreads adjacent names.
        int hash = Math.abs(key.hashCode() * 31) % 360;
        return "hsl(" + hash + " 62% 66%)";
    }

    /**
     * Whether a "now" marker belongs on this chart, and where.
     *
     * <p>Returns a percentage, or {@code null} when the window does not contain
     * now — a marker outside the plot would be drawn clamped to an edge and would
     * then read as a real position, which is worse than no marker.
     *
     * <p>The marker matters on this page more than on a typical chart. The window
     * defaults to "the last 24 hours", so the right-hand edge moves with the clock,
     * and without a reference line a reader cannot tell which bars are recent and
     * which are simply far right.
     */
    public Double nowPercent(LocalDateTime from, LocalDateTime to, LocalDateTime now) {
        if (from == null || to == null || now == null) {
            return null;
        }
        if (now.isBefore(from) || !now.isBefore(to)) {
            return null;
        }
        return round2(leftPercent(from, to, now));
    }

    private static long windowSeconds(LocalDateTime from, LocalDateTime to) {
        long seconds = Duration.between(from, to).getSeconds();
        // A zero-length window would divide by zero. Callers reject inverted
        // ranges, but this is a public bean method, so it defends itself.
        return seconds <= 0 ? 1 : seconds;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }
}
