package dev.deskseed.foundation

enum class SearchResultCountRelation {
    EXACT,
    LOWER_BOUND,
    UNAVAILABLE,
}

data class SearchResultCount(
    val value: Long?,
    val relation: SearchResultCountRelation,
) {
    init {
        require(value == null || value >= 0) { "Search result count cannot be negative" }
        require((relation == SearchResultCountRelation.UNAVAILABLE) == (value == null)) {
            "Unavailable search result count must be null and available counts must have a value"
        }
    }
}
