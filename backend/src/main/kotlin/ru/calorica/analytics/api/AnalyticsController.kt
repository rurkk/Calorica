package ru.calorica.analytics.api

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.calorica.analytics.application.AnalyticsService
import ru.calorica.platform.api.ApiFailure
import ru.calorica.platform.security.Actor

@RestController
@RequestMapping("/api/v1/analytics")
class AnalyticsController(private val analytics: AnalyticsService) {
    @GetMapping
    fun summary(@AuthenticationPrincipal actor: Actor?, @RequestParam date: String,
        @RequestParam zone: String, @RequestParam period: String) =
        analytics.summary(actor ?: throw ApiFailure.unauthorized(), date, zone, period)
}
