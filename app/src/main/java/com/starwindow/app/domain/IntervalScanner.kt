package com.starwindow.app.domain

/** A stretch of time during which some condition held, with the best moment inside it. */
data class ScoredInterval(
    val enterMillis: Long,
    val exitMillis: Long,
    /** True when the condition already held when the search started. */
    val clippedAtStart: Boolean,
    /** True when it still held when the search ended. */
    val clippedAtEnd: Boolean,
    /** Highest score seen while inside, and when — altitude for an object, star count for a figure. */
    val bestScore: Double,
    val bestMillis: Long,
) {
    val durationMillis: Long get() = exitMillis - enterMillis
}

/**
 * Finds the stretches of time in which a predicate holds.
 *
 * Sampling on a coarse grid and then bisecting each crossing, rather than solving analytically:
 * the predicate here is "is this direction inside an arbitrary sky polygon", which has no closed
 * form. Shared between single objects and whole constellation figures so the bisection — the part
 * that is easy to get subtly wrong — exists exactly once.
 */
internal object IntervalScanner {

    fun scan(
        fromMillis: Long,
        toMillis: Long,
        stepMillis: Long,
        refineIterations: Int,
        isInside: (Long) -> Boolean,
        score: (Long) -> Double,
    ): List<ScoredInterval> {
        require(stepMillis > 0) { "Die Schrittweite muss positiv sein" }
        val intervals = mutableListOf<ScoredInterval>()

        var previousTime = fromMillis
        var previousInside = isInside(fromMillis)

        var entryTime = fromMillis
        var clippedAtStart = previousInside
        var bestScore = if (previousInside) score(fromMillis) else Double.NEGATIVE_INFINITY
        var bestMillis = fromMillis

        var time = fromMillis + stepMillis
        while (time <= toMillis) {
            val nowInside = isInside(time)

            if (nowInside && !previousInside) {
                entryTime = refine(previousTime, time, refineIterations, isInside)
                clippedAtStart = false
                bestScore = score(time)
                bestMillis = time
            } else if (!nowInside && previousInside) {
                intervals += ScoredInterval(
                    enterMillis = entryTime,
                    exitMillis = refine(previousTime, time, refineIterations, isInside),
                    clippedAtStart = clippedAtStart,
                    clippedAtEnd = false,
                    bestScore = bestScore,
                    bestMillis = bestMillis,
                )
                bestScore = Double.NEGATIVE_INFINITY
            } else if (nowInside) {
                val current = score(time)
                if (current > bestScore) {
                    bestScore = current
                    bestMillis = time
                }
            }

            previousInside = nowInside
            previousTime = time
            time += stepMillis
        }

        if (previousInside) {
            intervals += ScoredInterval(
                enterMillis = entryTime,
                exitMillis = toMillis,
                clippedAtStart = clippedAtStart,
                clippedAtEnd = true,
                bestScore = bestScore,
                bestMillis = bestMillis,
            )
        }

        return intervals
    }

    /** Bisects a bracket that straddles a crossing down to the moment it happens. */
    private fun refine(
        lowMillis: Long,
        highMillis: Long,
        iterations: Int,
        isInside: (Long) -> Boolean,
    ): Long {
        var low = lowMillis
        var high = highMillis
        val insideAtLow = isInside(low)
        repeat(iterations) {
            val mid = low + (high - low) / 2
            if (mid == low || mid == high) return mid
            if (isInside(mid) == insideAtLow) low = mid else high = mid
        }
        return low + (high - low) / 2
    }
}
