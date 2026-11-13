package com.raunakpatil.roniepocket.engine

/**
 * Detects a reply that has started repeating itself (the failure mode Qwen3.5's small models showed in
 * the bake-off: every thinking-mode answer ran to the token cap). Feed it the growing reply; when
 * [isLooping] returns true, stop generating and use [trimmed] for the text to keep.
 *
 * Two checks, both cheap enough to run on every streamed chunk:
 *  - the reply ends with the same chunk (12..120 chars) repeated [minRepeats] times in a row;
 *  - the same non-trivial line appears [minRepeats] times.
 */
class LoopGuard(private val minRepeats: Int = 3) {
    private var cutAt = -1

    fun isLooping(text: String): Boolean {
        if (text.length < 60) return false
        // 1) Repeated tail chunk.
        val maxW = minOf(120, text.length / minRepeats)
        for (w in 12..maxW) {
            val tail = text.takeLast(w)
            if (tail.isBlank()) continue
            var reps = 1
            var end = text.length - w
            while (end - w >= 0 && text.regionMatches(end - w, tail, 0, w)) { reps++; end -= w }
            if (reps >= minRepeats) { cutAt = end + w; return true }
        }
        // 2) Repeated line.
        val lines = text.lines().map { it.trim() }.filter { it.length >= 15 }
        val counts = lines.groupingBy { it }.eachCount()
        val repeated = counts.entries.firstOrNull { it.value >= minRepeats }?.key
        if (repeated != null) {
            val first = text.indexOf(repeated)
            cutAt = text.indexOf(repeated, first + repeated.length).takeIf { it > 0 } ?: text.length
            return true
        }
        return false
    }

    /** The reply up to (and including) the first copy of the repeated part. */
    fun trimmed(text: String): String = if (cutAt in 1..text.length) text.substring(0, cutAt).trimEnd() else text
}
