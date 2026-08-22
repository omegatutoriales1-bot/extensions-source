package eu.kanade.tachiyomi.extension.es.lectorxd

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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.Locale

@Source
abstract class LectorXD : KeiSource() {

    private val statusQueryCache = mutableMapOf<String, Pair<String, String>>()

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("User-Agent", DESKTOP_UA)
        set("Accept-Language", "es-ES,es;q=0.9,en;q=0.7")
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host

            if (host !in CDN_HOSTS) {
                return@addInterceptor chain.proceed(request)
            }

            try {
                val response = chain.proceed(request)
                if (response.isSuccessful) return@addInterceptor response

                response.close()
                chain.proceed(request.withAlternateCdn())
            } catch (error: IOException) {
                chain.proceed(request.withAlternateCdn())
            }
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Filtros de LectorXD"),
        StatusFilter(),
        TypeFilter(),
        CategoryFilter(),
    )

    override suspend fun getPopularManga(page: Int): MangasPage {
        // LectorXD exposes its catalogue as the most stable paginated entry point.
        // "sort" is harmless on deployments that ignore it and works on deployments
        // where the catalogue exposes the ordering as a query parameter.
        val url = catalogueUrl(page, sort = "views")
        val document = client.get(url).asJsoup()
        return document.toMangasPage(page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = catalogueUrl(page, sort = "recent")
        val document = client.get(url).asJsoup()
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

        for (route in SEARCH_ROUTES) {
            val result = runCatching {
                val url = "$baseUrl$route".toHttpUrl().newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("page", page.toString())
                    .build()
                val document = client.get(url).asJsoup()
                val mangas = parseCatalogue(document)
                    .filter { it.title.contains(query, ignoreCase = true) }
                if (mangas.isNotEmpty()) MangasPage(mangas, hasNextPage(document, page)) else null
            }.getOrNull()

            if (result != null) return result
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

        val updatedManga = parseMangaDetails(document, seriesPath).apply {
            // Preserve a previously discovered thumbnail if the current page omitted it.
            if (thumbnail_url.isNullOrEmpty()) thumbnail_url = manga.thumbnail_url
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

        val fromImages = document.select("img").mapNotNull { image ->
            val alt = image.attr("alt")
            val candidate = image.bestImageUrl()
            val isReaderPage = PAGE_ALT_REGEX.containsMatchIn(alt) ||
                candidate?.toHttpUrlOrNull()?.host?.let { it in CDN_HOSTS } == true

            candidate?.takeIf { isReaderPage }
        }.distinct()

        val urls = if (fromImages.isNotEmpty()) {
            fromImages
        } else {
            // Reader revisions may inject the CDN URLs through inline JSON/JS.
            // Normalizing escaped slashes lets us recover those URLs without
            // depending on a generated JavaScript variable name.
            val html = document.html().replace("\\/", "/").replace("&amp;", "&")
            CDN_IMAGE_REGEX.findAll(html).map { it.value }.distinct().toList()
        }

        return urls.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    private fun catalogueUrl(page: Int, sort: String? = null): HttpUrl =
        "$baseUrl/catalogo".toHttpUrl().newBuilder().apply {
            addQueryParameter("filters", "true")
            addQueryParameter("page", page.toString())
            sort?.let { addQueryParameter("sort", it) }
        }.build()

    private fun filteredCatalogueUrl(
        page: Int,
        filters: FilterList,
        statusQuery: Pair<String, String>?,
    ): HttpUrl = "$baseUrl/catalogo".toHttpUrl().newBuilder().apply {
        addQueryParameter("filters", "true")
        addQueryParameter("page", page.toString())

        filters.filterIsInstance<TypeFilter>()
            .firstOrNull()
            ?.selectedValue
            ?.takeIf { it.isNotEmpty() }
            ?.let { addQueryParameter("types", it) }

        filters.filterIsInstance<CategoryFilter>()
            .firstOrNull()
            ?.selectedValue
            ?.takeIf { it.isNotEmpty() }
            ?.let { addQueryParameter("tags", it) }

        statusQuery?.let { (name, value) ->
            addQueryParameter(name, value)
        }
    }.build()

    private suspend fun resolveStatusQuery(status: String): Pair<String, String> {
        statusQueryCache[status]?.let { return it }

        val candidates = STATUS_QUERY_CANDIDATES[status].orEmpty()
        if (candidates.isEmpty()) return "status" to status

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

    private fun catalogueTotal(document: Document): Int? {
        val raw = CATALOGUE_TOTAL_REGEX.find(document.text())
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        return raw.filter(Char::isDigit).toIntOrNull()
    }

    private fun Document.toMangasPage(page: Int): MangasPage =
        MangasPage(parseCatalogue(this), hasNextPage(this, page))

    private fun parseCatalogue(document: Document): List<SManga> {
        val seen = mutableSetOf<String>()

        return document.select("a[href]").mapNotNull { anchor ->
            val absolute = anchor.attr("abs:href").toHttpUrlOrNull() ?: return@mapNotNull null
            val path = absolute.encodedPath.removeSuffix("/")
            if (!SERIES_PATH_REGEX.matches(path)) return@mapNotNull null
            if (!seen.add(path)) return@mapNotNull null

            val image = anchor.selectFirst("img")
            val title = anchor.selectFirst("h1,h2,h3,h4,h5")?.text()?.takeIf { it.isNotEmpty() }
                ?: image?.attr("alt")?.takeIf { it.isNotEmpty() }
                ?: anchor.attr("title").takeIf { it.isNotEmpty() }
                ?: anchor.ownText().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null

            SManga.create().apply {
                url = path
                this.title = title
                thumbnail_url = image?.bestImageUrl()
            }
        }
    }

    private fun parseMangaDetails(document: Document, seriesPath: String): SManga {
        val title = document.selectFirst("h1")?.text()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("LectorXD: no se pudo encontrar el título")

        val cover = document.select("img").firstOrNull { image ->
            val alt = image.attr("alt")
            alt.equals(title, ignoreCase = true) ||
                image.classNames().any { it.contains("cover", ignoreCase = true) }
        }?.bestImageUrl()
            ?: document.select("img").mapNotNull { it.bestImageUrl() }
                .firstOrNull { !it.contains("avatar", ignoreCase = true) && !it.contains("logo", ignoreCase = true) }

        val synopsisHeading = document.select("h2,h3,h4")
            .firstOrNull { it.text().contains("Sinopsis", ignoreCase = true) }
        val description = synopsisHeading?.nextElementSibling()?.text()?.takeIf { it.isNotEmpty() }

        val genres = document.select("a[href*='tags=']")
            .map { it.text() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString()
            .takeIf { it.isNotEmpty() }

        val pageText = document.text().lowercase(Locale.ROOT)
        val status = when {
            "completado" in pageText -> SManga.COMPLETED
            "en emisión" in pageText || "en emision" in pageText -> SManga.ONGOING
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
                .addQueryParameter("page", page.toString())
                .build()
            val document = client.get(url).asJsoup()
            reachedKnown = addFrom(document)
            page++
        }

        // During normal updates, once we hit a known chapter there is no need to
        // redownload all older chapter pages. Reattach the locally known tail.
        if (knownUrls.isNotEmpty()) {
            existing.forEach { chapter ->
                if (seenUrls.add(chapter.url)) collected += chapter
            }
        }

        return collected
    }

    private fun parseChapters(document: Document): List<SChapter> {
        val seen = mutableSetOf<String>()

        return document.select("a[href*='/leer/']").mapNotNull { anchor ->
            val absolute = anchor.attr("abs:href").toHttpUrlOrNull() ?: return@mapNotNull null
            val path = absolute.encodedPath.removeSuffix("/")
            if (!CHAPTER_PATH_REGEX.matches(path)) return@mapNotNull null
            if (!seen.add(path)) return@mapNotNull null

            val rawNumber = path.substringAfterLast('/')
            val text = anchor.text()
            val chapterNumber = CHAPTER_NUMBER_REGEX.find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                ?: rawNumber.toFloatOrNull()

            SChapter.create().apply {
                url = path
                name = text.takeIf { it.isNotEmpty() } ?: "Cap. $rawNumber"
                chapter_number = chapterNumber ?: -1f
            }
        }
    }

    private fun chapterPaginationMax(document: Document): Int {
        val fromLinks = document.select("a[href*='page=']").mapNotNull { anchor ->
            anchor.attr("abs:href").toHttpUrlOrNull()?.queryParameter("page")?.toIntOrNull()
                ?: anchor.text().toIntOrNull()
        }.maxOrNull()

        if (fromLinks != null) return fromLinks.coerceAtLeast(1)

        // Fallback for button-driven pagination rendered without hrefs.
        val match = PAGINATION_SUMMARY_REGEX.find(document.text()) ?: return 1
        val total = match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
            ?: match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
            ?: return 1
        return (total + CHAPTERS_PER_PAGE - 1) / CHAPTERS_PER_PAGE
    }

    private fun hasNextPage(document: Document, currentPage: Int): Boolean {
        return document.select("a[href*='page=']").any { anchor ->
            val page = anchor.attr("abs:href").toHttpUrlOrNull()?.queryParameter("page")?.toIntOrNull()
                ?: anchor.text().toIntOrNull()
            page == currentPage + 1
        } || document.text().contains("${currentPage * CATALOGUE_PAGE_SIZE + 1}–")
    }

    private fun normalizeSeriesPath(path: String): String? {
        val normalized = path.substringBefore('?').substringBefore('#').removeSuffix("/")
        if (SERIES_PATH_REGEX.matches(normalized)) return normalized

        val chapterMatch = CHAPTER_PATH_REGEX.find(normalized) ?: return null
        return chapterMatch.groupValues[1]
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
            value.isNotEmpty() && !value.startsWith("data:image") && !value.startsWith("blob:")
        }?.let { value ->
            return if (value.startsWith("//")) "https:$value" else absUrlFor(value)
        }

        val srcset = attr("data-srcset").ifEmpty { attr("srcset") }
        if (srcset.isNotEmpty()) {
            val value = srcset.substringBefore(',').trim().substringBefore(' ')
            if (value.isNotEmpty()) return if (value.startsWith("//")) "https:$value" else absUrlFor(value)
        }

        return null
    }

    private fun Element.absUrlFor(value: String): String {
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        val base = baseUri().toHttpUrlOrNull() ?: return value
        return base.resolve(value)?.toString() ?: value
    }

    private fun Request.withAlternateCdn(): Request {
        val alternate = if (url.host == CDN_ALPHA) CDN_BETA else CDN_ALPHA
        return newBuilder()
            .url(url.newBuilder().host(alternate).build())
            .build()
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

        private const val CDN_ALPHA = "s1.cdnlxd.xyz"
        private const val CDN_BETA = "s2.cdnlxd.xyz"
        private val CDN_HOSTS = setOf(CDN_ALPHA, CDN_BETA)

        private const val CATALOGUE_PAGE_SIZE = 24
        private const val CHAPTERS_PER_PAGE = 20
        private const val MAX_CHAPTER_PAGES = 80

        private val SEARCH_PARAMETERS = listOf("search", "q", "query", "title")
        private val SEARCH_ROUTES = listOf("/buscar", "/search")

        private val STATUS_QUERY_CANDIDATES = mapOf(
            "ongoing" to listOf(
                "status" to "ongoing",
                "statuses" to "ongoing",
                "status" to "en-emision",
                "status" to "emision",
            ),
            "completed" to listOf(
                "status" to "completed",
                "statuses" to "completed",
                "status" to "completado",
            ),
        )

        private val CATALOGUE_TOTAL_REGEX = Regex("([0-9.,]+)\\s+series disponibles", RegexOption.IGNORE_CASE)

        private val SERIES_PATH_REGEX = Regex("^/(?:manga|manhwa|manhua|novela|one-shot)/[^/?#]+$", RegexOption.IGNORE_CASE)
        private val CHAPTER_PATH_REGEX = Regex("^((?:/manga|/manhwa|/manhua|/novela|/one-shot)/[^/?#]+)/leer/[^/?#]+$", RegexOption.IGNORE_CASE)
        private val CHAPTER_NUMBER_REGEX = Regex("(?:Cap(?:ítulo)?\\.?\\s*)?([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
        private val PAGE_ALT_REGEX = Regex("P[aá]gina\\s*\\d+", RegexOption.IGNORE_CASE)
        private val PAGINATION_SUMMARY_REGEX = Regex("Mostrando\\s+\\d+\\s*[-–]\\s*\\d+\\s+de\\s+(\\d+)|([0-9]+)\\s+cap[ií]tulos", RegexOption.IGNORE_CASE)
        private val CDN_IMAGE_REGEX = Regex("https://s[12]\\.cdnlxd\\.xyz/[^\\\"'<>\\s]+", RegexOption.IGNORE_CASE)
    }
}


private open class LectorXDSelectFilter(
    name: String,
    private val values: Array<Pair<String, String>>,
) : Filter.Select<String>(name, values.map { it.first }.toTypedArray()) {
    val selectedValue: String
        get() = values[state].second
}

private class StatusFilter : LectorXDSelectFilter(
    "Estado",
    arrayOf(
        "Todos" to "",
        "En emisión" to "ongoing",
        "Completado" to "completed",
    ),
)

private class TypeFilter : LectorXDSelectFilter(
    "Tipo",
    arrayOf(
        "Todos" to "",
        "Manga" to "manga",
        "Manhwa" to "manhwa",
        "Manhua" to "manhua",
        "Novela" to "novela",
        "One Shot" to "one-shot",
    ),
)

private class CategoryFilter : LectorXDSelectFilter(
    "Categoría",
    arrayOf(
        "Todas" to "",
        "Romance" to "27",
        "Drama" to "30",
        "Acción" to "23",
        "Fantasía" to "24",
        "Comedia" to "28",
        "Aventura" to "25",
        "Ecchi" to "33",
        "Harem" to "21",
        "Recuentos de la vida" to "35",
        "Artes Marciales" to "54",
        "Reencarnación" to "29",
        "Sobrenatural" to "36",
        "Magia" to "48",
        "Tragedia" to "46",
        "Academia" to "53",
    ),
)
