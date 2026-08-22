package eu.kanade.tachiyomi.extension.es.lectorotaku

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

@Source
abstract class LectorOtaku : KeiSource() {

    private val statusQueryCache = mutableMapOf<String, Pair<String, String>>()

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("User-Agent", DESKTOP_UA)
        set("Accept-Language", "es-ES,es;q=0.9,en;q=0.7")
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Filtros de LectorOtaku"),
        StatusFilter(),
        GenreFilter(),
    )

    override suspend fun getPopularManga(page: Int): MangasPage {
        // El catálogo es el endpoint paginado más estable. En la primera página
        // LectorOtaku prioriza obras activas y destacadas.
        val document = client.get(catalogueUrl(page)).asJsoup()
        return document.toMangasPage(page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        // El catálogo por defecto coloca las incorporaciones/actualizaciones recientes
        // al principio y mantiene paginación estable.
        val document = client.get(catalogueUrl(page)).asJsoup()
        return document.toMangasPage(page)
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val selectedStatus = filters.filterIsInstance<StatusFilter>()
            .firstOrNull()
            ?.selectedValue
            .orEmpty()

        val statusQuery = selectedStatus
            .takeIf { it.isNotEmpty() }
            ?.let { resolveStatusQuery(it) }

        if (query.isBlank()) {
            val document = client.get(filteredCatalogueUrl(page, filters, statusQuery)).asJsoup()
            return document.toMangasPage(page)
        }

        for (parameter in SEARCH_PARAMETERS) {
            val result = runCatching {
                val url = filteredCatalogueUrl(page, filters, statusQuery)
                    .newBuilder()
                    .addQueryParameter(parameter, query)
                    .build()

                val document = client.get(url).asJsoup()
                val mangas = parseCatalogue(document)
                    .filter { it.title.contains(query, ignoreCase = true) }

                if (mangas.isNotEmpty()) {
                    MangasPage(mangas, hasNextPage(document, page))
                } else {
                    null
                }
            }.getOrNull()

            if (result != null) return result
        }

        // Fallback a la búsqueda global de la web.
        for (route in SEARCH_ROUTES) {
            for (parameter in SEARCH_PARAMETERS) {
                val result = runCatching {
                    val url = (baseUrl + route).toHttpUrl().newBuilder()
                        .addQueryParameter(parameter, query)
                        .addQueryParameter("pagina", page.toString())
                        .build()
                    val document = client.get(url).asJsoup()
                    val mangas = parseCatalogue(document)
                        .filter { it.title.contains(query, ignoreCase = true) }
                    if (mangas.isNotEmpty()) {
                        MangasPage(mangas, hasNextPage(document, page))
                    } else {
                        null
                    }
                }.getOrNull()

                if (result != null) return result
            }
        }

        return MangasPage(emptyList(), false)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val path = normalizeSeriesPath(url.encodedPath) ?: return null
        val document = client.get(baseUrl + path).asJsoup()
        return parseMangaDetails(document, path)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val seriesPath = normalizeSeriesPath(manga.url) ?: manga.url
        val document = client.get(baseUrl + seriesPath).asJsoup()

        val updatedManga = if (fetchDetails) {
            parseMangaDetails(document, seriesPath).apply {
                if (thumbnail_url.isNullOrEmpty()) thumbnail_url = manga.thumbnail_url
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters || chapters.isEmpty()) {
            fetchAllChapters(document, seriesPath, chapters)
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url
        val document = client.get(chapterUrl).asJsoup()

        val pages = document.select("img").mapNotNull { image ->
            val alt = image.attr("alt")
            val candidate = image.bestImageUrl()
            candidate?.takeIf {
                PAGE_ALT_REGEX.containsMatchIn(alt) ||
                    image.classNames().any { className ->
                        className.contains("reader", ignoreCase = true) ||
                            className.contains("page", ignoreCase = true)
                    }
            }
        }.distinct()

        if (pages.isNotEmpty()) {
            return pages.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
        }

        // Fallback para revisiones que inyecten URLs desde JSON/JS.
        val html = document.html().replace("\\/", "/").replace("&amp;", "&")
        return ABSOLUTE_IMAGE_REGEX.findAll(html)
            .map { it.value }
            .filter { value ->
                value.contains("api.lectorotakus.com", ignoreCase = true) ||
                    value.contains("storage", ignoreCase = true)
            }
            .distinct()
            .mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
            .toList()
    }

    private fun catalogueUrl(page: Int): HttpUrl = "$baseUrl/series/".toHttpUrl().newBuilder().apply {
        addQueryParameter("pagina", page.toString())
    }.build()

    private fun filteredCatalogueUrl(
        page: Int,
        filters: FilterList,
        statusQuery: Pair<String, String>?,
    ): HttpUrl = "$baseUrl/series/".toHttpUrl().newBuilder().apply {
        addQueryParameter("pagina", page.toString())

        filters.filterIsInstance<GenreFilter>()
            .firstOrNull()
            ?.selectedValue
            ?.takeIf { it.isNotEmpty() }
            ?.let { addQueryParameter("generos[]", it) }

        statusQuery?.let { (name, value) -> addQueryParameter(name, value) }
    }.build()

    private suspend fun resolveStatusQuery(status: String): Pair<String, String> {
        statusQueryCache[status]?.let { return it }

        val candidates = STATUS_QUERY_CANDIDATES[status].orEmpty()
        if (candidates.isEmpty()) return "estado" to status

        val baseCount = runCatching {
            catalogueTotal(client.get(catalogueUrl(1)).asJsoup())
        }.getOrNull()

        if (baseCount != null && baseCount > 0) {
            for (candidate in candidates) {
                val count = runCatching {
                    val url = catalogueUrl(1).newBuilder()
                        .addQueryParameter(candidate.first, candidate.second)
                        .build()
                    catalogueTotal(client.get(url).asJsoup())
                }.getOrNull()

                if (count != null && count in 1 until baseCount) {
                    statusQueryCache[status] = candidate
                    return candidate
                }
            }
        }

        return candidates.first().also { statusQueryCache[status] = it }
    }

    private fun catalogueTotal(document: Document): Int? = CATALOGUE_TOTAL_REGEX
        .find(document.text())
        ?.groupValues
        ?.getOrNull(1)
        ?.filter(Char::isDigit)
        ?.toIntOrNull()

    private fun Document.toMangasPage(page: Int): MangasPage =
        MangasPage(parseCatalogue(this), hasNextPage(this, page))

    private fun parseCatalogue(document: Document): List<SManga> {
        val seen = mutableSetOf<String>()

        return document.select("a[href]").mapNotNull { anchor ->
            val absolute = anchor.attr("abs:href").toHttpUrlOrNull() ?: return@mapNotNull null
            if (absolute.host != baseUrl.toHttpUrl().host) return@mapNotNull null

            val path = absolute.encodedPath.ensureTrailingSlash()
            if (!SERIES_PATH_REGEX.matches(path)) return@mapNotNull null
            if (!seen.add(path)) return@mapNotNull null

            val image = anchor.selectFirst("img")
                ?: anchor.parent()?.selectFirst("img")
            val title = anchor.selectFirst("h1,h2,h3,h4,h5")?.text()?.takeIf { it.isNotBlank() }
                ?: image?.attr("alt")?.removeSuffix(" portada")?.takeIf { it.isNotBlank() }
                ?: anchor.attr("title").takeIf { it.isNotBlank() }
                ?: anchor.text().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            SManga.create().apply {
                url = path
                this.title = title
                thumbnail_url = image?.bestImageUrl()
            }
        }
    }

    private fun parseMangaDetails(document: Document, seriesPath: String): SManga {
        val title = document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("LectorOtaku: no se encontró el título")

        val cover = document.select("img").firstOrNull { image ->
            image.attr("alt").contains("portada", ignoreCase = true) ||
                image.attr("alt").equals(title, ignoreCase = true) ||
                image.classNames().any { it.contains("cover", ignoreCase = true) }
        }?.bestImageUrl()

        val synopsisHeading = document.select("h2,h3,h4")
            .firstOrNull { it.text().contains("Sinopsis", ignoreCase = true) }
        val description = synopsisHeading?.nextElementSibling()?.text()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("[class*=synopsis], [class*=sinopsis], #sinopsis")?.text()?.takeIf { it.isNotBlank() }

        val genres = document.select("a[href*='genero'], a[href*='generos']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString()
            .takeIf { it.isNotBlank() }

        val pageText = document.text().lowercase(Locale.ROOT)
        val status = when {
            "finalizado" in pageText || "completado" in pageText -> SManga.COMPLETED
            "en emisión" in pageText || "en emision" in pageText -> SManga.ONGOING
            "hiatus" in pageText -> SManga.ON_HIATUS
            "cancelada" in pageText || "cancelado" in pageText -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        return SManga.create().apply {
            url = seriesPath
            this.title = title
            thumbnail_url = cover
            this.description = description
            genre = genres
            this.status = status
        }
    }

    private suspend fun fetchAllChapters(
        firstDocument: Document,
        seriesPath: String,
        existing: List<SChapter>,
    ): List<SChapter> {
        val knownUrls = existing.map { it.url }.toHashSet()
        val collected = mutableListOf<SChapter>()
        val seenUrls = mutableSetOf<String>()

        fun addFrom(document: Document): Boolean {
            var reachedKnown = false
            parseChapters(document).forEach { chapter ->
                if (chapter.url in knownUrls) reachedKnown = true
                if (seenUrls.add(chapter.url)) collected += chapter
            }
            return reachedKnown
        }

        var reachedKnown = addFrom(firstDocument)
        val maxPage = chapterPaginationMax(firstDocument).coerceAtMost(MAX_CHAPTER_PAGES)

        var page = 2
        while (!reachedKnown && page <= maxPage) {
            val url = (baseUrl + seriesPath).toHttpUrl().newBuilder()
                .addQueryParameter("pagina", page.toString())
                .build()
            reachedKnown = addFrom(client.get(url).asJsoup())
            page++
        }

        if (knownUrls.isNotEmpty()) {
            existing.forEach { chapter ->
                if (seenUrls.add(chapter.url)) collected += chapter
            }
        }

        return collected.sortedByDescending { it.chapter_number }
    }

    private fun parseChapters(document: Document): List<SChapter> {
        val seen = mutableSetOf<String>()

        return document.select("a[href*='/capitulo/']").mapNotNull { anchor ->
            val absolute = anchor.attr("abs:href").toHttpUrlOrNull() ?: return@mapNotNull null
            val path = absolute.encodedPath.ensureTrailingSlash()
            if (!CHAPTER_PATH_REGEX.matches(path)) return@mapNotNull null
            if (!seen.add(path)) return@mapNotNull null

            val rawNumber = path.removeSuffix("/").substringAfterLast('/')
            val text = anchor.text().trim()
            val chapterNumber = CHAPTER_NUMBER_REGEX.find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                ?: rawNumber.toFloatOrNull()

            SChapter.create().apply {
                url = path
                name = text.takeIf { it.isNotBlank() } ?: "Capítulo $rawNumber"
                chapter_number = chapterNumber ?: -1f
            }
        }
    }

    private fun chapterPaginationMax(document: Document): Int = document
        .select("a[href*='pagina=']")
        .mapNotNull { anchor ->
            anchor.attr("abs:href").toHttpUrlOrNull()?.queryParameter("pagina")?.toIntOrNull()
                ?: anchor.text().toIntOrNull()
        }
        .maxOrNull()
        ?.coerceAtLeast(1)
        ?: 1

    private fun hasNextPage(document: Document, currentPage: Int): Boolean = document
        .select("a[href]")
        .any { anchor ->
            val url = anchor.attr("abs:href").toHttpUrlOrNull() ?: return@any false
            val next = url.queryParameter("pagina")?.toIntOrNull()
                ?: url.queryParameter("page")?.toIntOrNull()
            next == currentPage + 1
        }

    private fun normalizeSeriesPath(path: String): String? {
        val normalized = path.substringBefore('?').substringBefore('#').ensureTrailingSlash()
        if (SERIES_PATH_REGEX.matches(normalized)) return normalized
        return CHAPTER_PATH_REGEX.find(normalized)?.groupValues?.getOrNull(1)?.ensureTrailingSlash()
    }

    private fun Element.bestImageUrl(): String? {
        val candidates = sequenceOf(
            attr("data-src"),
            attr("data-lazy-src"),
            attr("data-original"),
            attr("data-url"),
            attr("src"),
        )

        candidates.firstOrNull { value ->
            value.isNotBlank() && !value.startsWith("data:image") && !value.startsWith("blob:")
        }?.let { value ->
            return when {
                value.startsWith("//") -> "https:$value"
                value.startsWith("http://") || value.startsWith("https://") -> value
                else -> baseUri().toHttpUrlOrNull()?.resolve(value)?.toString() ?: value
            }
        }

        val srcset = attr("data-srcset").ifEmpty { attr("srcset") }
        if (srcset.isNotBlank()) {
            val value = srcset.substringBefore(',').trim().substringBefore(' ')
            if (value.isNotBlank()) {
                return when {
                    value.startsWith("//") -> "https:$value"
                    value.startsWith("http://") || value.startsWith("https://") -> value
                    else -> baseUri().toHttpUrlOrNull()?.resolve(value)?.toString() ?: value
                }
            }
        }

        return null
    }

    private fun String.ensureTrailingSlash(): String = if (endsWith('/')) this else "$this/"

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

        private const val MAX_CHAPTER_PAGES = 100

        private val SEARCH_PARAMETERS = listOf("buscar", "busqueda", "search", "q", "s", "titulo")
        private val SEARCH_ROUTES = listOf("/series/", "/buscar/", "/search/")

        private val STATUS_QUERY_CANDIDATES = mapOf(
            "ongoing" to listOf(
                "estado" to "En emisión",
                "estado" to "en-emision",
                "estado" to "ongoing",
                "estados[]" to "En emisión",
                "status" to "ongoing",
            ),
            "completed" to listOf(
                "estado" to "Finalizado",
                "estado" to "finalizado",
                "estado" to "completed",
                "estados[]" to "Finalizado",
                "status" to "completed",
            ),
            "hiatus" to listOf(
                "estado" to "Hiatus",
                "estado" to "hiatus",
                "estados[]" to "Hiatus",
                "status" to "hiatus",
            ),
            "cancelled" to listOf(
                "estado" to "Cancelada",
                "estado" to "cancelada",
                "estados[]" to "Cancelada",
                "status" to "cancelled",
            ),
        )

        private val CATALOGUE_TOTAL_REGEX = Regex("([0-9.,]+)\\s+c[oó]mics disponibles", RegexOption.IGNORE_CASE)
        private val SERIES_PATH_REGEX = Regex("^/series/[^/?#]+/$", RegexOption.IGNORE_CASE)
        private val CHAPTER_PATH_REGEX = Regex("^(/series/[^/?#]+/)(?:capitulo)/[^/?#]+/$", RegexOption.IGNORE_CASE)
        private val CHAPTER_NUMBER_REGEX = Regex("(?:Cap(?:ítulo)?\\.?\\s*)?([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
        private val PAGE_ALT_REGEX = Regex("P[aá]gina\\s*\\d+", RegexOption.IGNORE_CASE)
        private val ABSOLUTE_IMAGE_REGEX = Regex("https?://[^\\\"'<>\\s]+\\.(?:jpg|jpeg|png|webp|avif)(?:\\?[^\\\"'<>\\s]*)?", RegexOption.IGNORE_CASE)
    }
}

private open class LectorOtakuSelectFilter(
    name: String,
    private val options: Array<Pair<String, String>>,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val selectedValue: String
        get() = options[state].second
}

private class StatusFilter : LectorOtakuSelectFilter(
    "Estado",
    arrayOf(
        "Todos" to "",
        "En emisión" to "ongoing",
        "Finalizado" to "completed",
        "Hiatus" to "hiatus",
        "Cancelada" to "cancelled",
    ),
)

private class GenreFilter : LectorOtakuSelectFilter(
    "Género",
    arrayOf(
        "Todos" to "",
        "+18" to "+18",
        "Acción" to "Accion",
        "Adulto" to "Adulto",
        "Artes Marciales" to "Artes Marciales",
        "Aventura" to "Aventura",
        "Boys Love" to "Boys Love",
        "Ciencia ficción" to "Ciencia ficción",
        "Comedia" to "Comedia",
        "Drama" to "Drama",
        "Ecchi" to "Ecchi",
        "Fantasía" to "Fantasía",
        "Familia" to "Familia",
        "Harem" to "Harem",
        "Josei" to "Josei",
        "Magia" to "Magia",
        "Maduro" to "Maduro",
        "Misterio" to "Misterio",
        "Recuentos de la vida" to "Recuentos de la vida",
        "Romance" to "Romance",
        "Shoujo" to "Shoujo",
        "Smut" to "Smut",
        "Sobrenatural" to "Sobrenatural",
        "Tragedia" to "Tragedia",
    ),
)
