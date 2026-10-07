package eu.kanade.tachiyomi.extension.fr.aniverse

import eu.kanade.tachiyomi.source.model.Filter

class ValueCheckBox(name: String, val value: String) : Filter.CheckBox(name)

abstract class MultiFilter(name: String, options: List<Pair<String, String>>) : Filter.Group<ValueCheckBox>(name, options.map { ValueCheckBox(it.first, it.second) }) {
    val values get() = state.filter { it.state }.map { it.value }
}

open class SelectFilter(name: String, private val options: List<Pair<String, String?>>) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val value get() = options[state].second
}

class SortFilter :
    SelectFilter(
        "Trier par",
        listOf(
            "Popularité" to "popularity",
            "Nouveautés" to "newest",
            "Note" to "rating",
            "Titre" to "title",
        ),
    )

class KindFilter :
    SelectFilter(
        "Catégorie",
        listOf(
            "Toutes" to null,
            "Manga" to "manga",
            "Manhwa" to "manhwa",
            "Manhua" to "manhua",
            "Comic" to "comic",
        ),
    )

class FormatFilter :
    MultiFilter(
        "Format",
        listOf("Manga" to "Manga", "One-shot" to "One-shot"),
    )

class StatusFilter :
    MultiFilter(
        "Statut (au moins un)",
        listOf(
            "En cours" to "Ongoing",
            "Terminé" to "Finished",
            "En pause" to "On hiatus",
            "Abandonné" to "Cancelled",
        ),
    )

// French label shown on the site to the value expected by the `genres` API parameter.
private val GENRES = listOf(
    "Action" to "Action",
    "Aventure" to "Adventure",
    "Comédie" to "Comedy",
    "Drame" to "Drama",
    "Ecchi" to "Ecchi",
    "Fantasy" to "Fantasy",
    "Horreur" to "Horror",
    "Mahou shoujo" to "Mahou Shoujo",
    "Mecha" to "Mecha",
    "Musique" to "Music",
    "Mystère" to "Mystery",
    "Psychologique" to "Psychological",
    "Romance" to "Romance",
    "Science-fiction" to "Sci-Fi",
    "Tranche de vie" to "Slice of Life",
    "Sport" to "Sports",
    "Surnaturel" to "Supernatural",
    "Thriller" to "Thriller",
)

// The API returns only manga that have all the selected genres.
class GenreFilter : MultiFilter("Genres (tous requis)", GENRES)

class YearFilter : Filter.Text("Année de publication") {
    val value get() = state.trim().toIntOrNull()
}

class MinRatingFilter : Filter.Text("Note minimale (0-10)") {
    val value get() = state.trim().replace(',', '.').toDoubleOrNull()
}

class MaxRatingFilter : Filter.Text("Note maximale (0-10)") {
    val value get() = state.trim().replace(',', '.').toDoubleOrNull()
}
