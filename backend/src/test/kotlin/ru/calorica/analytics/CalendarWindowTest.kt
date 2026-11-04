package ru.calorica.analytics

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.calorica.analytics.domain.CalendarWindow
import ru.calorica.platform.api.ApiFailure
import java.time.Duration
import java.time.LocalDate

class CalendarWindowTest {
    @Test fun `week always spans Monday through Sunday including a year boundary`() {
        val window = CalendarWindow.select("2027-01-01", "Europe/Moscow", "WEEK")
        assertThat(window.startDate).isEqualTo(LocalDate.parse("2026-12-28"))
        assertThat(window.endDateExclusive).isEqualTo(LocalDate.parse("2027-01-04"))
        assertThat(window.dates).hasSize(7)
        assertThat(window.dayCount).isEqualTo(7)
        assertThat(CalendarWindow.select("2027-01-03", "Europe/Moscow", "WEEK")).isEqualTo(window)
    }
    @Test fun `months include every date and leap day`() {
        val leap = CalendarWindow.select("2028-02-12", "UTC", "MONTH")
        assertThat(leap.startDate).isEqualTo(LocalDate.parse("2028-02-01"))
        assertThat(leap.endDateExclusive).isEqualTo(LocalDate.parse("2028-03-01"))
        assertThat(leap.dayCount).isEqualTo(29)
        assertThat(CalendarWindow.select("2026-02-28", "UTC", "MONTH").dayCount).isEqualTo(28)
        assertThat(CalendarWindow.select("2026-04-30", "UTC", "MONTH").dayCount).isEqualTo(30)
        assertThat(CalendarWindow.select("2026-12-31", "UTC", "MONTH").dayCount).isEqualTo(31)
    }
    @Test fun `DST changes elapsed hours but never the calendar denominator`() {
        val spring = CalendarWindow.select("2026-03-29", "Europe/Berlin", "DAY")
        assertThat(spring.start.toString()).isEqualTo("2026-03-28T23:00:00Z")
        assertThat(spring.end.toString()).isEqualTo("2026-03-29T22:00:00Z")
        assertThat(Duration.between(spring.start, spring.end).toHours()).isEqualTo(23)
        val autumn = CalendarWindow.select("2026-10-25", "Europe/Berlin", "DAY")
        assertThat(Duration.between(autumn.start, autumn.end).toHours()).isEqualTo(25)
        val week = CalendarWindow.select("2026-03-29", "Europe/Berlin", "WEEK")
        assertThat(Duration.between(week.start, week.end).toHours()).isEqualTo(167)
        assertThat(week.dayCount).isEqualTo(7)
        val skipped = CalendarWindow.select("2011-12-30", "Pacific/Apia", "DAY")
        assertThat(skipped.start).isEqualTo(skipped.end)
        assertThat(skipped.dayCount).isEqualTo(1)
    }
    @Test fun `invalid calendar input fails with field specific validation`() {
        for ((date, zone, period, field) in listOf(
            listOf("2026-02-30", "UTC", "DAY", "date"),
            listOf("0000-01-01", "UTC", "DAY", "date"),
            listOf("9999-12-31", "UTC", "MONTH", "date"),
            listOf("2026-1-01", "UTC", "DAY", "date"),
            listOf("2026-01-01", "+03:00", "DAY", "zone"),
            listOf("2026-01-01", "Unknown/Zone", "DAY", "zone"),
            listOf("2026-01-01", "UTC", "week", "period"))) {
            assertThatThrownBy { CalendarWindow.select(date, zone, period) }
                .isInstanceOfSatisfying(ApiFailure::class.java) {
                    assertThat(it.status).isEqualTo(422)
                    assertThat(it.errors.single().field).isEqualTo(field)
                }
        }
    }
}
