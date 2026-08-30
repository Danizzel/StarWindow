package com.starwindow.app.domain

import com.starwindow.app.data.catalog.SkyObject

/**
 * Everything the result list is currently narrowed by.
 *
 * One object rather than a handful of separate view-model fields, for a reason that only shows up
 * once there are more than two of them: a filter that is set but not visible is worse than no
 * filter, because the list then lies quietly. Keeping them together means [activeLabels] can always
 * enumerate exactly what is switched on, and the chip row above the list can never fall out of step
 * with what is actually being applied.
 *
 * Every field defaults to "no restriction", so [NONE] really does mean the whole catalogue.
 */
data class CatalogFilter(
    val kind: ResultFilter = ResultFilter.ALL,
    /** Only objects at least this bright. */
    val magnitudeLimit: Double? = null,
    /** Only objects at least this large, in arcminutes. */
    val minSizeArcmin: Double? = null,
    /** IAU three-letter abbreviation, e.g. `Ori`. */
    val constellation: String? = null,
    /** Only what currently stands at least this high. */
    val minAltitudeDeg: Double? = null,
    /** Drop what the feasibility verdict rules out for tonight. */
    val hideImpossible: Boolean = false,
    /** Restrict the list to objects that pass through the selected window; see [windowId]. */
    val onlyThroughWindow: Boolean = false,
    /** Which saved window [onlyThroughWindow] refers to; null means the most recent one. */
    val windowId: String? = null,
    /**
     * Nur die Objekte mit einem Herz.
     *
     * Der einzige Filter, der nichts über den Himmel weiß: Alle anderen fragen den Katalog oder die
     * Nacht, dieser fragt den Nutzer. Genau deshalb steht er in der Liste ganz oben — er ist der
     * kürzeste Weg von dreizehntausend Einträgen zu den fünf, um die es tatsächlich geht.
     */
    val onlyFavorites: Boolean = false,
) {
    /** True when nothing is restricted and the list is the whole catalogue. */
    val isEmpty: Boolean
        get() = kind == ResultFilter.ALL && magnitudeLimit == null && minSizeArcmin == null &&
            constellation == null && minAltitudeDeg == null && !hideImpossible &&
            !onlyThroughWindow && !onlyFavorites

    val activeCount: Int
        get() = listOf(
            kind != ResultFilter.ALL,
            magnitudeLimit != null,
            minSizeArcmin != null,
            constellation != null,
            minAltitudeDeg != null,
            hideImpossible,
            onlyThroughWindow,
            onlyFavorites,
        ).count { it }

    /**
     * What is switched on, as short chip labels.
     *
     * These are the strings shown above the list, so they are written the way a chip has to read:
     * `< 9,0 mag`, not `Helligkeitsgrenze 9,0 Magnituden`.
     */
    val activeLabels: List<FilterChip>
        get() = buildList {
            if (onlyFavorites) add(FilterChip(FilterField.FAVORITES, "Favoriten"))
            if (kind != ResultFilter.ALL) add(FilterChip(FilterField.KIND, kind.label))
            magnitudeLimit?.let { add(FilterChip(FilterField.MAGNITUDE, "< %.1f mag".format(it))) }
            minSizeArcmin?.let {
                add(FilterChip(FilterField.SIZE, "> " + formatSize(it)))
            }
            constellation?.let { add(FilterChip(FilterField.CONSTELLATION, it)) }
            minAltitudeDeg?.let { add(FilterChip(FilterField.ALTITUDE, "über %.0f°".format(it))) }
            if (hideImpossible) add(FilterChip(FilterField.FEASIBILITY, "nur machbar"))
            if (onlyThroughWindow) add(FilterChip(FilterField.WINDOW, "durchs Fenster"))
        }

    /**
     * Whether the object survives everything except the window test.
     *
     * The window is deliberately left out: answering it needs a transit search over hours, not a
     * predicate over one row, so the view model does that separately and passes the survivors in.
     *
     * @param favorites die Kennungen der Favoriten. Als Parameter und nicht als Feld des Filters,
     *   weil sie sich unabhängig von ihm ändern: Ein Herz, das während einer offenen Ergebnisliste
     *   gesetzt wird, soll dort ankommen, ohne dass der Filter neu gebaut werden muss.
     */
    fun matches(
        obj: SkyObject,
        altitudeDeg: Double?,
        conditions: SkyConditions,
        favorites: Set<String> = emptySet(),
    ): Boolean {
        if (onlyFavorites && obj.id !in favorites) return false
        if (!kind.matches(obj)) return false
        magnitudeLimit?.let { if ((obj.magnitude ?: 99.0) > it) return false }
        minSizeArcmin?.let { if ((obj.sizeArcmin ?: 0.0) < it) return false }
        constellation?.let { if (!obj.constellation.equals(it, ignoreCase = true)) return false }
        minAltitudeDeg?.let { if ((altitudeDeg ?: -90.0) < it) return false }
        if (hideImpossible &&
            !Feasibility.assess(obj, altitudeDeg, conditions).isPossible
        ) {
            return false
        }
        return true
    }

    /** Clears one field, for tapping the × on a chip. */
    fun without(field: FilterField): CatalogFilter = when (field) {
        FilterField.KIND -> copy(kind = ResultFilter.ALL)
        FilterField.MAGNITUDE -> copy(magnitudeLimit = null)
        FilterField.SIZE -> copy(minSizeArcmin = null)
        FilterField.CONSTELLATION -> copy(constellation = null)
        FilterField.ALTITUDE -> copy(minAltitudeDeg = null)
        FilterField.FEASIBILITY -> copy(hideImpossible = false)
        FilterField.WINDOW -> copy(onlyThroughWindow = false)
        FilterField.FAVORITES -> copy(onlyFavorites = false)
    }

    companion object {
        val NONE = CatalogFilter()

        /** Sizes read as arcminutes until they get large enough to be degrees. */
        fun formatSize(arcmin: Double): String =
            if (arcmin >= 60.0) "%.0f°".format(arcmin / 60.0) else "%.0f'".format(arcmin)
    }
}

/** Which part of a [CatalogFilter] a chip stands for, so it can be cleared individually. */
enum class FilterField { KIND, MAGNITUDE, SIZE, CONSTELLATION, ALTITUDE, FEASIBILITY, WINDOW, FAVORITES }

data class FilterChip(val field: FilterField, val label: String)
