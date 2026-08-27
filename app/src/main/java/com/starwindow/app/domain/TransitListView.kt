package com.starwindow.app.domain

/** How the list of objects passing through a window is ordered. */
enum class TransitSort(val label: String) {
    /**
     * The most rewarding targets first. The default, because a window's night is usually a
     * hundred-odd entries of which a handful are worth setting up for.
     */
    INTEREST("Interessant"),

    /** In the order they arrive — the order in which the night actually happens. */
    ENTRY("Eintritt"),

    /** Longest stay first: how much exposure the gap allows is often what decides. */
    DURATION("Dauer"),

    /** Brightest first. */
    BRIGHTNESS("Hell"),

    /** Largest first, which for a camera is nearly the same question as brightness. */
    SIZE("Größe");

    /** What the list header says the order is, in words. */
    val listNote: String
        get() = when (this) {
            INTEREST -> "lohnendste zuerst"
            ENTRY -> "in zeitlicher Reihenfolge"
            DURATION -> "längster Durchgang zuerst"
            BRIGHTNESS -> "hellste zuerst"
            SIZE -> "größte zuerst"
        }
}

/**
 * Turning a window's transit results into the list on screen.
 *
 * Kept out of the view model and out of the UI state for the usual reason in this project: it is a
 * judgement about data, it is easy to get subtly wrong, and here it can be tested without an
 * emulator. The view model owns *when* this runs; this owns *what comes out*.
 *
 * The default order changed with the catalogue. When a window returned thirty objects, sorting by
 * entry time was right — the list read as the night in sequence. The same window now returns
 * several hundred, most of them anonymous galaxies that merely happen to cross the gap, and
 * chronological order buries M31 somewhere in the middle of them. So the default became "most
 * worth photographing first", and the chronological view stayed as one option among several.
 */
object TransitListView {

    /**
     * Filters and orders the transits for display.
     *
     * @param query free text over names and designations, folded the same way the catalogue search
     *   folds them, so `m31`, `M 31` and `ngc224` all find the Andromeda Galaxy.
     */
    fun build(
        transits: List<ObjectTransit>,
        filter: ResultFilter,
        query: String = "",
        sort: TransitSort = TransitSort.INTEREST,
    ): List<ObjectTransit> {
        if (!filter.showsObjects) return emptyList()

        val needle = ObjectSearch.fold(query)
        val matching = transits.filter { transit ->
            filter.matches(transit.obj) &&
                (needle.isEmpty() || ObjectSearch.score(needle, transit.obj) != ObjectSearch.NO_MATCH)
        }
        return sorted(matching, sort, needle)
    }

    private fun sorted(
        transits: List<ObjectTransit>,
        sort: TransitSort,
        needle: String,
    ): List<ObjectTransit> = when {
        // While something is typed, match quality outranks any chosen order: the user is looking
        // for one thing, not browsing.
        needle.isNotEmpty() -> transits.sortedWith(
            compareByDescending<ObjectTransit> { ObjectSearch.score(needle, it.obj) }
                .thenByDescending { PhotographicInterest.score(it.obj) }
        )

        sort == TransitSort.INTEREST -> transits.sortedWith(
            compareByDescending<ObjectTransit> { PhotographicInterest.score(it.obj) }
                // Among equally interesting entries, the one that stays longer is the better
                // proposition — that is exposure time.
                .thenByDescending { it.totalDurationMillis }
        )

        sort == TransitSort.ENTRY -> transits.sortedBy { it.firstEntryMillis }

        sort == TransitSort.DURATION -> transits.sortedByDescending { it.totalDurationMillis }

        sort == TransitSort.BRIGHTNESS -> transits.sortedBy { it.obj.magnitude ?: 99.0 }

        else -> transits.sortedByDescending { it.obj.sizeArcmin ?: -1.0 }
    }

    /**
     * The best few entries of each kind, for a glance at what a window is good for.
     *
     * Not a replacement for the list but a way into it: "this gap is a galaxy window" is a useful
     * thing to know before scrolling through two hundred rows, and it is invisible in any single
     * ordering of them.
     */
    fun highlightsByKind(
        transits: List<ObjectTransit>,
        perKind: Int = 3,
    ): Map<ResultFilter, List<ObjectTransit>> = listOf(
        ResultFilter.NEBULAE, ResultFilter.GALAXIES, ResultFilter.CLUSTERS, ResultFilter.STARS,
    ).associateWith { kind ->
        transits.asSequence()
            .filter { kind.matches(it.obj) }
            .sortedByDescending { PhotographicInterest.score(it.obj) }
            .take(perKind)
            .toList()
    }.filterValues { it.isNotEmpty() }
}
