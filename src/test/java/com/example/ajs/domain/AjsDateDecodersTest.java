package com.example.ajs.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tier 1 — pure unit tests for the legacy date/sentinel decoders.
 *
 * <p>This is the highest-value offline test in the project. Risk R2 in
 * {@code req.txt} §7 is the one that produces real production incidents
 * (SQL0181/CPD4019), and it is entirely testable with no IBM i involved.
 */
class AjsDateDecodersTest {

    @Nested
    @DisplayName("CYYMMDD (JMDTE)")
    class Cyymmdd {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "1251103, 2025-11-03",   // century flag 1 -> 20xx
                "1000101, 2000-01-01",   // first day of 2000
                "1240229, 2024-02-29",   // leap year, valid
        })
        void decodesValidValues(int raw, LocalDate expected) {
            assertThat(AjsDateDecoders.decodeCyymmdd(raw)).contains(expected);
        }

        // --- encodeCyymmdd: the inverse, used to bound the history query ---

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "2025-11-03, 1251103",
                "2000-01-01, 1000101",
                "2024-02-29, 1240229",
                // Written without the leading zero, as in the decoder test above:
                // JUnit's CSV-to-int conversion does not accept "0991231", and the
                // numeric value 991231 is identical (century flag 0).
                "1999-12-31, 991231",
                "1990-01-01, 900101",
        })
        void encodesValidDates(LocalDate date, int expected) {
            assertThat(AjsDateDecoders.encodeCyymmdd(date)).isEqualTo(expected);
        }

        @Test
        @DisplayName("encoding is the exact inverse of decoding, on both sides of 2000")
        void encodeDecodeRoundTrips() {
            // The history query compares ENCODED integers, so a rounding error in
            // either direction would silently shift a window edge and drop or
            // include a day of runs. Round-tripping is the property that makes the
            // bounded query safe, so it is asserted directly.
            for (LocalDate date : new LocalDate[]{
                    LocalDate.of(1999, 12, 31),
                    LocalDate.of(2000, 1, 1),
                    LocalDate.of(2025, 11, 3),
                    LocalDate.of(2099, 12, 31)}) {
                Integer encoded = AjsDateDecoders.encodeCyymmdd(date);
                assertThat(AjsDateDecoders.decodeCyymmdd(encoded))
                        .as("round trip for %s via %s", date, encoded)
                        .contains(date);
            }
        }

        @Test
        @DisplayName("the encoded value is numerically ordered, which is what makes BETWEEN valid")
        void encodingPreservesOrderingWithinACentury() {
            // This is the assumption the SQL range predicate rests on. If it were
            // false, BETWEEN would filter incorrectly rather than raise an error.
            int earlier = AjsDateDecoders.encodeCyymmdd(LocalDate.of(2025, 11, 20));
            int later = AjsDateDecoders.encodeCyymmdd(LocalDate.of(2025, 11, 21));
            int newYearsEve = AjsDateDecoders.encodeCyymmdd(LocalDate.of(2025, 12, 31));

            assertThat(earlier).isLessThan(later);
            assertThat(later).isLessThan(newYearsEve);
        }

        @Test
        @DisplayName("null encodes to null, matching the null-capable column")
        void nullEncodesToNull() {
            assertThat(AjsDateDecoders.encodeCyymmdd(null)).isNull();
        }

        @Test
        @DisplayName("a year outside 1900..2099 is rejected rather than mis-encoded")
        void yearOutsideRepresentableRangeThrows() {
            // The century flag is a single digit, so 2100 and 1899 cannot be
            // represented. Failing loudly is the point: a silently wrong century
            // would put a query bound in the wrong era and return nothing.
            assertThatThrownBy(() -> AjsDateDecoders.encodeCyymmdd(LocalDate.of(2100, 1, 1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> AjsDateDecoders.encodeCyymmdd(LocalDate.of(1899, 12, 31)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a leading zero century flag decodes to the 1900s")
        void leadingZeroCenturyFlagIs1900s() {
            // Written without the leading zero here because JUnit's CSV-to-int
            // conversion rejects "0991231". Numerically 991231 has century flag
            // 0, which is precisely the case being tested.
            assertThat(AjsDateDecoders.decodeCyymmdd(991231))
                    .contains(LocalDate.of(1999, 12, 31));
        }

        @Test
        @DisplayName("the century flag is the leading digit, so 1991231 is 2099 not 1999")
        void centuryFlagIsLeadingDigit() {
            // This is deliberately asserted because it is the single most
            // misread field in CYYMMDD: the first digit is a century flag, not
            // part of the year. 1 99 12 31 -> 2099-12-31. A value intended as
            // 1999 must be written 0991231, which is numerically 991231.
            assertThat(AjsDateDecoders.decodeCyymmdd(1991231))
                    .contains(LocalDate.of(2099, 12, 31));
            assertThat(AjsDateDecoders.decodeCyymmdd(991231))
                    .contains(LocalDate.of(1999, 12, 31));
        }

        @Test
        @DisplayName("9999999 sentinel means no next run, not a date")
        void sentinelMeansNoNextRun() {
            assertThat(AjsDateDecoders.decodeCyymmdd(9_999_999)).isEmpty();
        }

        @Test
        @DisplayName("null is treated as absent, not an error")
        void nullIsAbsent() {
            assertThat(AjsDateDecoders.decodeCyymmdd(null)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(ints = {1250230, 1251332, 1251100, 1250001}) // Feb 30, month 13, day 0
        @DisplayName("impossible dates fail loudly instead of silently shifting")
        void impossibleDatesThrow(int raw) {
            assertThatThrownBy(() -> AjsDateDecoders.decodeCyymmdd(raw))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an unknown century flag is rejected rather than guessed")
        void unknownCenturyFlagThrows() {
            // 2xxxxxx is not a documented CYYMMDD century flag.
            assertThatThrownBy(() -> AjsDateDecoders.decodeCyymmdd(2991231))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("century flag");
        }

        @Test
        @DisplayName("a negative value is rejected")
        void negativeThrows() {
            assertThatThrownBy(() -> AjsDateDecoders.decodeCyymmdd(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("YYMMDD (JMLRD)")
    class Yymmdd {

        @Test
        @DisplayName("0 sentinel means never run")
        void zeroMeansNeverRun() {
            assertThat(AjsDateDecoders.decodeYymmdd(0)).isEmpty();
        }

        @Test
        void nullIsAbsent() {
            assertThat(AjsDateDecoders.decodeYymmdd(null, LocalDate.of(2025, 11, 3))).isEmpty();
        }

        @Test
        @DisplayName("recent years land in the 2000s")
        void recentYearsAre2000s() {
            assertThat(AjsDateDecoders.decodeYymmdd(251102, LocalDate.of(2025, 11, 3)))
                    .contains(LocalDate.of(2025, 11, 2));
            assertThat(AjsDateDecoders.decodeYymmdd(260101, LocalDate.of(2025, 11, 3)))
                    .contains(LocalDate.of(2026, 1, 1));
        }

        @Test
        @DisplayName("a year above the pivot decodes into the 1900s, so 1997 stays 1997")
        void yearsAbovePivotAre1900s() {
            // Pivot is 25 (from 2025); 97 > 25 therefore 1997, not 2097.
            assertThat(AjsDateDecoders.decodeYymmdd(971231, LocalDate.of(2025, 11, 3)))
                    .contains(LocalDate.of(1997, 12, 31));
        }

        @Test
        @DisplayName("the pivot boundary itself belongs to the 2000s")
        void pivotBoundaryIs2000s() {
            // Pivot year 25: 25 -> 2025, 26 -> 2026.
            assertThat(AjsDateDecoders.decodeYymmdd(250101, LocalDate.of(2025, 6, 1)))
                    .contains(LocalDate.of(2025, 1, 1));
        }

        @Test
        void impossibleDatesThrow() {
            assertThatThrownBy(() -> AjsDateDecoders.decodeYymmdd(251332, LocalDate.of(2025, 11, 3)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("HHMMSS (JMTME)")
    class Hhmmss {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "230000, 23:00",
                "0, 00:00",
                "235959, 23:59:59",
                "120000, 12:00",
        })
        void decodesValidTimes(int raw, String expected) {
            assertThat(AjsDateDecoders.decodeHhmmss(raw)).contains(LocalTime.parse(expected));
        }

        @Test
        void nullIsAbsent() {
            assertThat(AjsDateDecoders.decodeHhmmss(null)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(ints = {240000, 126000, 120060, 999999})
        void invalidTimesThrow(int raw) {
            assertThatThrownBy(() -> AjsDateDecoders.decodeHhmmss(raw))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("display helper")
    class Display {

        @Test
        void usesPlaceholderForSentinels() {
            assertThat(AjsDateDecoders.display(AjsDateDecoders.decodeCyymmdd(9_999_999), "—"))
                    .isEqualTo("—");
            assertThat(AjsDateDecoders.display(AjsDateDecoders.decodeYymmdd(0), "Never"))
                    .isEqualTo("Never");
        }

        @Test
        void rendersIsoDateWhenPresent() {
            assertThat(AjsDateDecoders.display(
                    AjsDateDecoders.decodeCyymmdd(1251103), "—"))
                    .isEqualTo("2025-11-03");
        }
    }
}
