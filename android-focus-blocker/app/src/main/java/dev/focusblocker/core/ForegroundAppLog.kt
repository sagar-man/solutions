package dev.focusblocker.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Spike 1's output: a live ring buffer of foreground-app detections with the
 * latency of each one.
 *
 * In-memory on purpose — this is a measurement surface, not state the product
 * depends on.
 */
object ForegroundAppLog {

    data class Detection(
        val packageName: String,
        /** How long after the window actually changed we heard about it. */
        val latencyMs: Long,
        val atWallMs: Long,
        val wasBlocked: Boolean,
    )

    private const val CAPACITY = 80

    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    val detections: StateFlow<List<Detection>> = _detections.asStateFlow()

    fun record(detection: Detection) {
        _detections.update { current -> (listOf(detection) + current).take(CAPACITY) }
    }

    fun clear() {
        _detections.value = emptyList()
    }

    /** Percentile over the recorded latencies, or null when there is no data. */
    fun latencyPercentile(percentile: Int): Long? {
        val sorted = _detections.value.map { it.latencyMs }.sorted()
        if (sorted.isEmpty()) return null
        val index = ((sorted.size - 1) * percentile / 100).coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}
