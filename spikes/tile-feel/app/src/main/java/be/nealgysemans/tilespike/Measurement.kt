package be.nealgysemans.tilespike

/**
 * One tap's worth of timing, all deltas from `onClick()` entry, measured with
 * [android.os.SystemClock.elapsedRealtimeNanos].
 *
 *  - [flipMicros]     onClick entry -> Tile.updateTile() returned. This is what the
 *                     user perceives as "the tile reacted".
 *  - [confirmMicros]  onClick entry -> getAutomaticZenRuleState() reported the state
 *                     we asked for. -1 when not applicable.
 *  - [broadcastMicros] onClick entry -> ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED
 *                     arrived. -1 when it never did within the wait budget.
 */
data class TapMeasurement(
    val seq: Int,
    val behavior: String,
    val target: String,
    val flipMicros: Long,
    val confirmMicros: Long,
    val broadcastMicros: Long,
    val confirmSource: String,
) {
    fun encode(): String = listOf(
        seq, behavior, target, flipMicros, confirmMicros, broadcastMicros, confirmSource,
    ).joinToString(FIELD_SEP)

    val flipMs: Double get() = flipMicros / 1000.0
    val confirmMs: Double get() = confirmMicros / 1000.0
    val broadcastMs: Double get() = broadcastMicros / 1000.0

    companion object {
        private const val FIELD_SEP = "|"
        private const val RECORD_SEP = "\n"
        const val MAX_RECORDS = 200

        fun decodeOrNull(line: String): TapMeasurement? {
            val p = line.split(FIELD_SEP)
            if (p.size != 7) return null
            return TapMeasurement(
                seq = p[0].toIntOrNull() ?: return null,
                behavior = p[1],
                target = p[2],
                flipMicros = p[3].toLongOrNull() ?: return null,
                confirmMicros = p[4].toLongOrNull() ?: return null,
                broadcastMicros = p[5].toLongOrNull() ?: return null,
                confirmSource = p[6],
            )
        }

        fun decodeAll(blob: String): List<TapMeasurement> =
            blob.lineSequence().mapNotNull { if (it.isBlank()) null else decodeOrNull(it) }.toList()

        fun encodeAll(items: List<TapMeasurement>): String =
            items.takeLast(MAX_RECORDS).joinToString(RECORD_SEP) { it.encode() }
    }
}

/** Median of a non-empty list; null for an empty one. */
fun List<Long>.medianOrNull(): Double? {
    if (isEmpty()) return null
    val s = sorted()
    val mid = s.size / 2
    return if (s.size % 2 == 1) s[mid].toDouble() else (s[mid - 1] + s[mid]) / 2.0
}

data class LatencyStats(
    val count: Int,
    val medianMs: Double?,
    val maxMs: Double?,
    val minMs: Double?,
) {
    companion object {
        /** Ignores the -1 sentinel so "no rule confirmation" never pollutes the stats. */
        fun of(microsValues: List<Long>): LatencyStats {
            val valid = microsValues.filter { it >= 0 }
            return LatencyStats(
                count = valid.size,
                medianMs = valid.medianOrNull()?.let { it / 1000.0 },
                maxMs = valid.maxOrNull()?.let { it / 1000.0 },
                minMs = valid.minOrNull()?.let { it / 1000.0 },
            )
        }
    }
}
