package nl.ihnatov.transcriber.asr

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Best-effort memory budget estimate for foreground-service-hosted model
 * loads. Android 17 (API 37) introduced a per-process native memory
 * limiter (source.android.com/docs/core/perf/memory-limiter) but ships no
 * query API — apps can't read their assigned limit at runtime. We
 * approximate it instead: our transcription work always runs from a
 * foreground service, which counts as "not visible" under the limiter —
 * the platform docs put that tier at roughly 25-33% of total RAM (vs
 * 50-67% for a visible/foreground-UI process). We use the conservative
 * low end. Below API 37 there's no hard limiter, so we just use the
 * OS-reported available memory instead.
 */
object MemoryGuard {

    private const val NOT_VISIBLE_BUDGET_FRACTION = 0.25
    const val GEMMA_E4B_MIN_BUDGET_BYTES = 5L * 1024 * 1024 * 1024

    data class Estimate(
        val estimatedBudgetBytes: Long,
        /** True when the estimate is derived from the API 37 limiter's known tier, not live availMem. */
        val isLimiterEstimate: Boolean,
    )

    fun estimateBudget(context: Context): Estimate {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return if (Build.VERSION.SDK_INT >= 37) {
            Estimate(
                estimatedBudgetBytes = (info.totalMem * NOT_VISIBLE_BUDGET_FRACTION).toLong(),
                isLimiterEstimate = true,
            )
        } else {
            Estimate(estimatedBudgetBytes = info.availMem, isLimiterEstimate = false)
        }
    }

    fun gibString(bytes: Long): String = "%.1f".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
