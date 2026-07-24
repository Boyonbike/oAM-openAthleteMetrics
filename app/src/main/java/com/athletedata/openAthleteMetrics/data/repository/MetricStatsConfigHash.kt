package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.model.BaselineFallbackDefaults
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

/** Which of the three resolution sources actually supplied a resolved window/minimum value. */
enum class ConfigResolutionTier { OVERRIDE, FALLBACK, DEFAULT }

/**
 * Computes [com.athletedata.openAthleteMetrics.data.db.MetricDailyStatsEntity.configHash]: a hash
 * over the inputs that determined a metric's resolved window/minimum, so a later config change
 * (an override added/cleared, a fallback default changed, the global setting changed) is
 * detectable by comparing hashes rather than re-deriving whether anything changed.
 */
object MetricStatsConfigHash {

    /** Bump if the canonical string's shape changes, so old and new hashes can never collide. */
    private const val SCHEMA_TAG = "v1"

    /**
     * Deliberately excludes [metric] from the hashed input: two different metrics that resolve to
     * the same (window, minimum, tier) pair legitimately get the same hash — the composite
     * (summary_date, metric_type) primary key already disambiguates by metric, so this hash's
     * only job is detecting when a given metric's OWN resolution changes over time.
     *
     * [windowDays]/[minimumDays] must be the already-resolved values from
     * [BaselineWindowConfigRepository.getEffectiveWindow]/[BaselineWindowConfigRepository.getEffectiveMinimum]
     * (this function does not re-derive them). The tier classification below makes one additional
     * read-only call to [BaselineWindowConfigRepository.observeOverride] to determine which
     * source fired; because that read and the two `getEffective*` reads the caller already made
     * are independent suspend calls, a concurrent `setOverride`/`clearOverride` could in rare
     * cases produce a tier tag from a slightly different moment than the numeric values alongside
     * it. Accepted as a narrow, practically harmless race — resolving it properly would require
     * changes to the out-of-scope [BaselineWindowConfigRepository] to make resolution atomic.
     */
    suspend fun compute(
        metric: BaselineMetric,
        windowDays: Int,
        minimumDays: Int,
        windowConfigRepo: BaselineWindowConfigRepository,
    ): String {
        val (windowTier, minimumTier) = resolveTiers(metric, windowConfigRepo)
        val canonical = "$SCHEMA_TAG:w=$windowDays;m=$minimumDays;wt=$windowTier;mt=$minimumTier"
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private suspend fun resolveTiers(
        metric: BaselineMetric,
        windowConfigRepo: BaselineWindowConfigRepository,
    ): Pair<ConfigResolutionTier, ConfigResolutionTier> {
        val override = windowConfigRepo.observeOverride(metric).first()
        val hasFallback = BaselineFallbackDefaults.values[metric] != null
        val windowTier = when {
            override?.windowDays != null -> ConfigResolutionTier.OVERRIDE
            hasFallback -> ConfigResolutionTier.FALLBACK
            else -> ConfigResolutionTier.DEFAULT
        }
        val minimumTier = when {
            override?.minimumDays != null -> ConfigResolutionTier.OVERRIDE
            hasFallback -> ConfigResolutionTier.FALLBACK
            else -> ConfigResolutionTier.DEFAULT
        }
        return windowTier to minimumTier
    }
}
