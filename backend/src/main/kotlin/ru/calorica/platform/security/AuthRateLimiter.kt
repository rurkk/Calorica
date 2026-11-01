package ru.calorica.platform.security

import org.springframework.stereotype.Component
import java.time.Clock

/** Fixed one-minute windows; bounded memory, fail closed when full, no attacker-controlled eviction. */
@Component
class AuthRateLimiter(private val clock: Clock) {
    private data class Bucket(val start: Long, var count: Int)
    private val buckets = HashMap<Pair<String, String>, Bucket>()

    @Synchronized
    fun retryAfter(ip: String, refresh: Boolean): Long? {
        val now = clock.instant().epochSecond
        buckets.entries.removeIf { now >= it.value.start + 60 }
        val key = (if (refresh) "refresh" else "credentials") to ip
        val bucket = buckets[key] ?: run {
            if (buckets.size >= 10000) return 60
            Bucket(now, 0).also { buckets[key] = it }
        }
        if (bucket.count >= if (refresh) 30 else 10) return (bucket.start + 60 - now).coerceAtLeast(1)
        bucket.count++
        return null
    }
}
