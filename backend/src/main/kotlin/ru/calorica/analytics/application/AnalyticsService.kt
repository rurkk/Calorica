package ru.calorica.analytics.application

import org.springframework.stereotype.Service
import ru.calorica.analytics.domain.Analytics
import ru.calorica.analytics.domain.CalendarWindow
import ru.calorica.analytics.domain.ProductGroup
import ru.calorica.analytics.persistence.AnalyticsRepository
import ru.calorica.catalog.application.CatalogTransactions
import ru.calorica.catalog.application.labels.ProductLabelRead
import ru.calorica.diary.application.DiaryAnalyticsRead
import ru.calorica.platform.security.Actor

@Service
class AnalyticsService(private val diary: DiaryAnalyticsRead, private val repository: AnalyticsRepository,
    private val productLabels: ProductLabelRead, private val transactions: CatalogTransactions) {
    fun summary(actor: Actor, date: String, zone: String, period: String): Analytics {
        val window = CalendarWindow.select(date, zone, period)
        return transactions.snapshot { snapshot ->
            val entries = diary.entries(actor, window.start, window.end, snapshot)
            val totals = repository.summarize(entries, window)
            val groups = productLabels.distribution(actor, entries.map { it.portion }, snapshot)
            Analytics(window.startDate, window.endDateExclusive, window.zone.id, window.dayCount,
                totals.days, totals.total, totals.dailyAverage, totals.mealLabels,
                groups.sortedBy { it.labelId.toString() }.map { ProductGroup(it.labelId, it.grams) })
        }
    }
}
