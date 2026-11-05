package ru.calorica.diary

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.calorica.catalog.domain.CatalogFailure
import ru.calorica.diary.api.DiaryInput
import ru.calorica.diary.domain.DiaryValues
import ru.calorica.platform.api.ApiFailure
import java.time.Duration
import java.util.UUID

class DiaryInputTest {
    private val mapper = ObjectMapper()
    private val id = UUID.randomUUID()
    @Test fun `mass is required positive exact and bounded with no JSON coercion`() {
        fun body(q: String) = mapper.readTree("""{"productId":"$id","quantity":$q,"unit":"g","occurredAt":"2026-11-01T00:00:00Z","mealLabelIds":[]}""")
        assertThat(DiaryInput.create(body("\"9999999.999\"")).quantity).isEqualByComparingTo("9999999.999")
        for (quantity in listOf("\"0\"", "\"-1\"", "\"1e2\"", "\"+1\"", "\"NaN\"", "\"10000000\"", "\"1.0001\"", "null"))
            assertThatThrownBy { DiaryInput.create(body(quantity)) }.isInstanceOf(ApiFailure::class.java)
        assertThatThrownBy { DiaryInput.create(body("12")) }.isInstanceOfSatisfying(ApiFailure::class.java) { assertThat(it.status).isEqualTo(400) }
    }
    @Test fun `patch preserves absent fields clears labels and rejects null unknown and mismatched quantity unit`() {
        assertThat(DiaryInput.patch(mapper.readTree("{}")).mealLabelIds).isNull()
        assertThat(DiaryInput.patch(mapper.readTree("{\"mealLabelIds\":[]}")).mealLabelIds).isEmpty()
        for (invalid in listOf("{\"quantity\":\"1\"}", "{\"unit\":\"g\"}", "{\"occurredAt\":null}",
            "{\"mealLabelIds\":null}", "{\"ownerId\":\"$id\"}", "{\"productId\":null}", "[]"))
            assertThatThrownBy { DiaryInput.patch(mapper.readTree(invalid)) }.isInstanceOf(ApiFailure::class.java)
        assertThatThrownBy { DiaryInput.patch(mapper.readTree("{\"mealLabelIds\":[\"$id\",\"$id\"]}")) }.isInstanceOf(ApiFailure::class.java)
        assertThatThrownBy { DiaryInput.patch(mapper.readTree("{\"productId\":\"1-1-1-1-1\"}")) }.isInstanceOf(CatalogFailure::class.java)
    }
    @Test fun `calendar uses IANA transitions and rejects invalid date zone and non UTC time`() {
        val zone = DiaryValues.zone("Europe/Berlin")
        val short = DiaryValues.day("2026-03-29")
        val long = DiaryValues.day("2026-10-25")
        assertThat(Duration.between(short.atStartOfDay(zone),short.plusDays(1).atStartOfDay(zone)).toHours()).isEqualTo(23)
        assertThat(Duration.between(long.atStartOfDay(zone),long.plusDays(1).atStartOfDay(zone)).toHours()).isEqualTo(25)
        for (bad in listOf("+03:00", "Not/AZone", "Europe/Mosco")) assertThatThrownBy { DiaryValues.zone(bad) }.isInstanceOf(ApiFailure::class.java)
        assertThatThrownBy { DiaryValues.day("2026-02-30") }.isInstanceOf(ApiFailure::class.java)
        for (bad in listOf("2026-11-01T00:00:00+00:00", "0000-01-01T00:00:00Z", "2026-02-30T00:00:00Z"))
            assertThatThrownBy { DiaryValues.occurredAt(bad) }.isInstanceOf(ApiFailure::class.java)
    }
}
