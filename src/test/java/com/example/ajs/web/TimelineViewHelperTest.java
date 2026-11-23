package com.example.ajs.web;

import com.example.ajs.domain.JobRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the timeline percentage arithmetic.
 *
 * <p>These exist because the alternative — expressing this in the template — was
 * unreadable and untestable. A wrong percentage here puts a bar in the wrong
 * place on the chart, which silently misinforms rather than erroring, so the
 * boundaries (window edges, sub-pixel runs, in-progress runs) are pinned.
 */
class TimelineViewHelperTest {

    private final TimelineViewHelper helper = new TimelineViewHelper();

    private static final LocalDateTime FROM = LocalDateTime.of(2025, 11, 20, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2025, 11, 21, 0, 0);

    @Test
    @DisplayName("left offset is proportional to elapsed time in the window")
    void leftPercentIsProportional() {
        assertThat(helper.leftPercent(FROM, TO, FROM)).isZero();
        assertThat(helper.leftPercent(FROM, TO, FROM.plusHours(6))).isEqualTo(25.0);
        assertThat(helper.leftPercent(FROM, TO, FROM.plusHours(12))).isEqualTo(50.0);
        assertThat(helper.leftPercent(FROM, TO, TO)).isEqualTo(100.0);
    }

    @Test
    @DisplayName("a run starting before the window is clamped to the left edge, not drawn off-screen")
    void runStartingBeforeWindowIsClamped() {
        // Drawing it at a negative offset would push the bar outside the plot and
        // hide a long-running job entirely.
        assertThat(helper.leftPercent(FROM, TO, FROM.minusHours(5))).isZero();
    }

    @Test
    @DisplayName("width is the run's duration as a fraction of the window")
    void widthPercentIsDurationFraction() {
        // A 2-hour run in a 24-hour window is 8.33% wide.
        JobRun twoHours = run(FROM, 7200);
        assertThat(helper.round2(helper.widthPercent(FROM, TO, twoHours))).isEqualTo(8.33);
    }

    @Test
    @DisplayName("a very short run keeps a visible minimum width instead of vanishing")
    void shortRunKeepsMinimumWidth() {
        // A 1-second run in a 24-hour window is ~0.001% — sub-pixel. Without the
        // floor the job appears not to have run at all, which is the opposite of
        // what a monitoring view should say.
        JobRun tiny = run(FROM.plusHours(1), 1);
        assertThat(helper.widthPercent(FROM, TO, tiny))
                .isEqualTo(TimelineViewHelper.MIN_WIDTH_PCT);
    }

    @Test
    @DisplayName("a still-running job extends to the right edge of the window")
    void runningJobExtendsToRightEdge() {
        // It has no known end, so the bar stays open rather than claiming a
        // duration nobody has observed yet.
        JobRun running = new JobRun("J", 1, FROM.plusHours(6), null, null);
        double width = helper.widthPercent(FROM, TO, running);

        assertThat(helper.round2(width)).isEqualTo(75.0);
        assertThat(helper.round2(helper.leftPercent(FROM, TO, running.startedAt()) + width))
                .isEqualTo(100.0);
    }

    @Test
    @DisplayName("a run cannot overflow the plot even if its duration exceeds the window")
    void widthNeverExceedsWindow() {
        JobRun huge = run(FROM.plusHours(1), 48 * 3600);
        assertThat(helper.widthPercent(FROM, TO, huge)).isLessThanOrEqualTo(100.0);
    }

    @Test
    @DisplayName("a zero-length window does not divide by zero")
    void zeroLengthWindowIsSafe() {
        // The controller rejects inverted ranges, but this is a public bean method.
        assertThat(helper.leftPercent(FROM, FROM, FROM)).isFinite();
        assertThat(helper.widthPercent(FROM, FROM, run(FROM, 60))).isFinite();
    }

    @Test
    @DisplayName("gap display distinguishes an unknown gap from a zero one")
    void gapDisplayHandlesUnknownAndZero() {
        // An em dash (unknown) and "0s" (ran back to back) mean different things
        // to an operator investigating why a batch window slipped.
        assertThat(helper.gapDisplay(null)).isEqualTo("—");
        assertThat(helper.gapDisplay(0L)).isEqualTo("0s");
        assertThat(helper.gapDisplay(45L)).isEqualTo("45s");
        assertThat(helper.gapDisplay(90L)).isEqualTo("1m 30s");
        assertThat(helper.gapDisplay(3600L)).isEqualTo("1h");
        assertThat(helper.gapDisplay(3660L)).isEqualTo("1h 1m");
    }

    @Test
    @DisplayName("duration labels flip inside the bar near the right edge, where they would be clipped")
    void labelsFlipNearTheRightEdge() {
        // A label hangs to the right of its bar, so a bar starting near the end of
        // the window pushes the label outside the plot, where overflow:hidden cuts
        // it to a fragment. The boundary is pinned either side of the threshold so
        // the flip cannot silently move.
        assertThat(helper.labelFlipped(FROM, TO, run(FROM, 60))).isFalse();
        assertThat(helper.labelFlipped(FROM, TO, run(FROM.plusHours(1), 60))).isFalse();

        // 87.5% -- just inside the threshold, label still on the right.
        LocalDateTime justBefore = FROM.plusMinutes((long) (24 * 60 * 0.875));
        assertThat(helper.leftPercent(FROM, TO, justBefore)).isLessThan(TimelineViewHelper.LABEL_FLIP_PCT);
        assertThat(helper.labelFlipped(FROM, TO, run(justBefore, 60))).isFalse();

        // 88% exactly and beyond -- label moves to the left of the bar.
        // 1267.2 minutes truncates below the threshold (87.99%), so the exact
        // boundary is expressed in seconds to avoid a float-cast surprise in the
        // test itself.
        LocalDateTime atThreshold = FROM.plusSeconds((long) (86400 * 0.88));
        assertThat(helper.leftPercent(FROM, TO, atThreshold)).isGreaterThanOrEqualTo(TimelineViewHelper.LABEL_FLIP_PCT);
        assertThat(helper.labelFlipped(FROM, TO, run(atThreshold, 60))).isTrue();
        assertThat(helper.labelFlipped(FROM, TO, run(TO.minusMinutes(10), 60))).isTrue();
    }

    @Test
    @DisplayName("label flip is false for a missing run rather than throwing")
    void labelFlipToleratesMissingRun() {
        // The template evaluates this for every bar; a null run must not fail the
        // page render, and "not flipped" is the rendering that was always used.
        assertThat(helper.labelFlipped(FROM, TO, null)).isFalse();
    }

    private static JobRun run(LocalDateTime start, int seconds) {
        return new JobRun("J", 1, start, "N", seconds);
    }
}
