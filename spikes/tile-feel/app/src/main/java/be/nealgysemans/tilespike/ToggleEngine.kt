package be.nealgysemans.tilespike

import android.os.SystemClock
import android.service.notification.Condition
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * Everything that happens *after* the optimistic tile flip: persist, toggle the real
 * zen rule, wait for the system to confirm, and record the timings.
 *
 * Nothing in here runs on the main thread and nothing in here is inside the user's
 * perceived latency — that is the whole design point. The tile has already changed
 * by the time [applyAndMeasure] is entered.
 */
class ToggleEngine(
    private val store: SpikeStore,
    private val zen: ZenController,
    private val watcher: ZenBroadcastWatcher?,
) {

    suspend fun applyAndMeasure(
        behaviorLabel: String,
        target: FakeMode?,
        t0Nanos: Long,
        flipNanos: Long,
    ) {
        val seq = store.nextSeq()
        store.setActiveMode(target)

        val targetLabel = target?.label ?: "OFF"
        val flipMicros = if (flipNanos > 0) (flipNanos - t0Nanos) / 1000 else -1L

        val ruleId = StateCache.value.zenRuleId ?: zen.findExistingRuleId()

        var confirmMicros = -1L
        var broadcastMicros = -1L
        var confirmSource: String

        if (!zen.hasDndAccess) {
            confirmSource = "no-dnd-access"
        } else if (ruleId == null) {
            confirmSource = "no-rule"
        } else {
            val desired = if (target != null) Condition.STATE_TRUE else Condition.STATE_FALSE
            val preState = zen.ruleState(ruleId)
            val preMatched = preState == desired

            watcher?.drain()
            val setResult = zen.setRuleState(ruleId, target != null)

            if (setResult.isFailure) {
                confirmSource = "set-failed: ${setResult.exceptionOrNull()?.javaClass?.simpleName}"
            } else {
                coroutineScope {
                    val pollAsync = async { pollForState(ruleId, desired, t0Nanos) }
                    val bcAsync = async { watcher?.awaitNext(BROADCAST_WAIT_MS) }
                    confirmMicros = pollAsync.await()
                    broadcastMicros = bcAsync.await()?.let { (it - t0Nanos) / 1000 } ?: -1L
                }
                confirmSource = buildString {
                    append(if (confirmMicros >= 0) "poll" else "poll-timeout")
                    // Honest flag: if the rule was ALREADY in the desired state, the
                    // "confirmation" is meaningless and must not enter the median.
                    if (preMatched) append("·pre-match")
                    if (broadcastMicros >= 0) append("·bcast")
                }
            }
        }

        store.addMeasurement(
            TapMeasurement(
                seq = seq,
                behavior = behaviorLabel,
                target = targetLabel,
                flipMicros = flipMicros,
                confirmMicros = confirmMicros,
                broadcastMicros = broadcastMicros,
                confirmSource = confirmSource,
            ),
        )
        store.log(
            "tap #$seq $behaviorLabel -> $targetLabel | " +
                "flip=${fmt(flipMicros)} confirm=${fmt(confirmMicros)} " +
                "bcast=${fmt(broadcastMicros)} [$confirmSource]",
        )
    }

    /** Busy-polls `getAutomaticZenRuleState` until it reports [desired]. Micros, or -1. */
    private suspend fun pollForState(ruleId: String, desired: Int, t0Nanos: Long): Long {
        val deadline = SystemClock.elapsedRealtime() + CONFIRM_BUDGET_MS
        while (true) {
            if (zen.ruleState(ruleId) == desired) {
                return (SystemClock.elapsedRealtimeNanos() - t0Nanos) / 1000
            }
            if (SystemClock.elapsedRealtime() >= deadline) return -1L
            delay(POLL_INTERVAL_MS)
        }
    }

    companion object {
        const val CONFIRM_BUDGET_MS = 3_000L
        const val BROADCAST_WAIT_MS = 1_500L
        private const val POLL_INTERVAL_MS = 2L

        fun fmt(micros: Long): String =
            if (micros < 0) "n/a" else "%.1fms".format(micros / 1000.0)
    }
}
