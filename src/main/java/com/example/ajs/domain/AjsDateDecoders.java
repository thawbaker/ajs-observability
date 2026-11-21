package com.example.ajs.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

/**
 * Decoders for the legacy IBM i date/time encodings used by the AJS physical
 * files in QUSRIJS.
 *
 * <p>This class exists because of risk R2 in {@code req.txt} §7: AJS stores
 * dates in packed/legacy formats with sentinel values, and a naive cast in SQL
 * raises SQL0181 / CPD4019 (see IBM's note on the AJS history file QAIJSHST).
 * Every one of these conversions is centralised here and unit-tested; nothing
 * else in the codebase is allowed to do raw arithmetic on these columns.
 *
 * <p>Encodings handled:
 * <ul>
 *   <li>{@code JMDTE} — 7-digit {@code CYYMMDD}; century flag is the first
 *       digit (0 = 19xx, 1 = 20xx). Sentinel {@code 9999999} = no next run.</li>
 *   <li>{@code JMLRD} — 6-digit {@code YYMMDD}; {@code 0} = never run.</li>
 *   <li>{@code JMTME} — time as {@code HHMMSS}.</li>
 * </ul>
 *
 * <p>Deliberately strict: malformed input throws {@link IllegalArgumentException}
 * rather than silently returning a wrong date. A wrong date in a job monitor is
 * worse than a loud failure, because it produces a false "job ran fine" signal.
 */
public final class AjsDateDecoders {

    /** {@code JMDTE} sentinel meaning "no next run date scheduled". */
    public static final int JMDTE_NO_NEXT_RUN = 9_999_999;

    /** {@code JMLRD} sentinel meaning "this job has never run". */
    public static final int JMLRD_NEVER_RUN = 0;

    /** All-ones packed value some AJS fields use for "not set". */
    public static final int ALL_ONES_7 = 9_999_999;

    private AjsDateDecoders() {
    }

    /**
     * Decodes a {@code CYYMMDD} value, e.g. {@code 1251103} -> 2025-11-03.
     *
     * <p>Returns {@link Optional#empty()} for the {@code 9999999} sentinel.
     *
     * @param cyymmdd 7-digit CYYMMDD, or {@code null}
     * @return decoded date, or empty when the sentinel / null is supplied
     * @throws IllegalArgumentException if the value is not a valid CYYMMDD
     */
    public static Optional<LocalDate> decodeCyymmdd(Integer cyymmdd) {
        if (cyymmdd == null || cyymmdd == JMDTE_NO_NEXT_RUN) {
            return Optional.empty();
        }
        if (cyymmdd < 0 || cyymmdd > 9_999_999) {
            throw new IllegalArgumentException(
                    "CYYMMDD out of range (expected 0..9999999 exclusive of sentinel): " + cyymmdd);
        }

        int centuryFlag = cyymmdd / 1_000_000;
        int yy = (cyymmdd / 10_000) % 100;
        int mm = (cyymmdd / 100) % 100;
        int dd = cyymmdd % 100;

        // Century flag is documented as 0 = 19xx, 1 = 20xx. Anything else is
        // data we do not understand — fail loudly rather than guess a century.
        int year;
        switch (centuryFlag) {
            case 0 -> year = 1900 + yy;
            case 1 -> year = 2000 + yy;
            default -> throw new IllegalArgumentException(
                    "Unsupported CYYMMDD century flag " + centuryFlag + " in value " + cyymmdd);
        }

        return Optional.of(buildDate(year, mm, dd, "CYYMMDD", cyymmdd));
    }

    /**
     * Decodes a {@code YYMMDD} value, e.g. {@code 251103} -> 2025-11-03.
     *
     * <p>Two-digit years are inherently ambiguous. The rule used here is the one
     * that matches how schedulers are actually operated: a job's last-run date is
     * almost always in the recent past, while a next-run date may be slightly in
     * the future. So the pivot is a window around today — a value within the next
     * {@value #FUTURE_WINDOW_YEARS} years is read as 20xx, otherwise 19xx.
     *
     * <p>This is why the naive "yy <= currentYear % 100" rule is wrong: on
     * 2025-11-03 that rule turns a next-run date of 2026-01-01 into 1926-01-01,
     * which is exactly the kind of silent corruption this class exists to stop.
     *
     * <p>Returns {@link Optional#empty()} for the {@code 0} "never run" sentinel.
     *
     * @param yymmdd 6-digit YYMMDD, or {@code null}
     * @return decoded date, or empty when never-run / null
     * @throws IllegalArgumentException if the value is not a valid YYMMDD
     */
    public static Optional<LocalDate> decodeYymmdd(Integer yymmdd) {
        return decodeYymmdd(yymmdd, LocalDate.now());
    }

    /** How many years into the future a two-digit year may point before it is
     *  assumed to be a 1900s date instead. One is enough for a scheduler. */
    static final int FUTURE_WINDOW_YEARS = 1;

    /** Package-visible variant with an injected pivot so tests are deterministic. */
    static Optional<LocalDate> decodeYymmdd(Integer yymmdd, LocalDate today) {
        if (yymmdd == null || yymmdd == JMLRD_NEVER_RUN) {
            return Optional.empty();
        }
        if (yymmdd < 0 || yymmdd > 999_999) {
            throw new IllegalArgumentException(
                    "YYMMDD out of range (expected 0..999999): " + yymmdd);
        }

        int yy = yymmdd / 10_000;
        int mm = (yymmdd / 100) % 100;
        int dd = yymmdd % 100;

        // Simple and provable: prefer the 2000s interpretation, and only fall
        // back to the 1900s when the 2000s reading would be implausibly far in
        // the future. With today = 2025 and a window of 1:
        //   97 -> 2097 is 72 years ahead -> implausible -> 1997
        //   26 -> 2026 is 1 year ahead   -> plausible   -> 2026
        //   25 -> 2025 is now            -> plausible   -> 2025
        int candidate = 2000 + yy;
        int latestPlausible = today.getYear() + FUTURE_WINDOW_YEARS;
        int year = (candidate > latestPlausible) ? 1900 + yy : candidate;

        return Optional.of(buildDate(year, mm, dd, "YYMMDD", yymmdd));
    }

    /**
     * Decodes a {@code HHMMSS} time-of-day value.
     *
     * @param hhmmss packed time, or {@code null}
     * @return decoded time, or empty when null
     * @throws IllegalArgumentException if the value is not a valid HHMMSS
     */
    public static Optional<LocalTime> decodeHhmmss(Integer hhmmss) {
        if (hhmmss == null) {
            return Optional.empty();
        }
        if (hhmmss < 0 || hhmmss > 235_959) {
            throw new IllegalArgumentException("HHMMSS out of range (expected 0..235959): " + hhmmss);
        }
        int hh = hhmmss / 10_000;
        int mi = (hhmmss / 100) % 100;
        int ss = hhmmss % 100;

        if (hh > 23 || mi > 59 || ss > 59) {
            throw new IllegalArgumentException("Invalid HHMMSS components in value " + hhmmss);
        }
        return Optional.of(LocalTime.of(hh, mi, ss));
    }

    /**
     * Renders a decoded date as {@code yyyy-MM-dd} for display, or the supplied
     * placeholder when the field was a sentinel.
     */
    public static String display(Optional<LocalDate> date, String placeholder) {
        return date.map(LocalDate::toString).orElse(placeholder);
    }

    private static LocalDate buildDate(int year, int month, int day, String format, int raw) {
        try {
            return LocalDate.of(year, month, day);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "Invalid date components in " + format + " value " + raw
                            + " (" + year + "-" + month + "-" + day + ")", ex);
        }
    }
}
