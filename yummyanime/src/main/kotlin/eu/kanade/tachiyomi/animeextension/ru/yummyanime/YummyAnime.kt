package eu.kanade.tachiyomi.animeextension.ru.yummyanime

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

class YummyAnime : AnimeHttpSource(), ConfigurableAnimeSource {
    override val name = "YummyAnime"
    override val baseUrl = API_BASE_URL
    override val lang = "ru"
    override val supportsLatest = true
    override val versionId = 1

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    init {
        DebugLogBridge.sink = { appendDebugLogLine(it) }
    }

    private val publicToken: String
        get() = preferences.getString(PREF_PUBLIC_TOKEN, DEFAULT_PUBLIC_TOKEN)!!.trim()

    private val privateToken: String
        get() = preferences.getString(PREF_PRIVATE_TOKEN, "")!!.trim()

    override val client: OkHttpClient = network.client.newBuilder().build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "application/json,image/avif,image/webp,*/*")
        .add("Lang", "ru")
        .apply {
            if (publicToken.isNotBlank()) {
                add("X-Application", publicToken)
            }
            if (privateToken.isNotBlank()) {
                add("Authorization", "Yummy $privateToken")
            }
        }

    override fun popularAnimeRequest(page: Int): Request {
        ensureConfigured()
        return GET(buildAnimeListUrl(page, query = "", filters = FilterValues.default()))
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val items = response.asJsonObject()
            .optJSONArray("response")
            .jsonObjects()
            .map(::animeListItemToSAnime)

        return AnimesPage(items, items.size >= PAGE_SIZE)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        ensureConfigured()
        return GET("$API_BASE_URL/anime/schedule#page=$page")
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val page = response.request.url.fragment
            ?.substringAfter("page=")
            ?.toIntOrNull()
            ?: 1

        val allItems = response.asJsonObject()
            .optJSONArray("response")
            .jsonObjects()

        val offset = (page - 1) * PAGE_SIZE
        val pageItems = allItems.drop(offset).take(PAGE_SIZE)

        return AnimesPage(
            pageItems.map(::scheduleItemToSAnime),
            offset + PAGE_SIZE < allItems.size,
        )
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        ensureConfigured()
        val parsedFilters = FilterValues.from(filters)
        if (query.isBlank() && parsedFilters.isEmpty()) {
            return popularAnimeRequest(page)
        }

        return GET(buildAnimeListUrl(page, query.trim(), parsedFilters))
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun animeDetailsRequest(anime: SAnime): Request {
        ensureConfigured()
        return GET("$baseUrl${anime.url}?need_videos=true")
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val obj = response.asJsonObject().optJSONObject("response")
            ?: error("Missing anime response object")

        return animeDetailsToSAnime(obj)
    }

    override fun episodeListRequest(anime: SAnime): Request {
        ensureConfigured()
        return GET("$baseUrl${anime.url}?need_videos=true")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val root = response.asJsonObject().optJSONObject("response")
            ?: return emptyList()
        val animeUrl = root.optString("anime_url").ifBlank {
            response.request.url.encodedPath.substringAfter("/anime/")
        }

        val grouped = parseVideoEntries(root.optJSONArray("videos"))
            .groupBy { it.number }
            .toList()
            .sortedByDescending { sortEpisodeNumber(it.first) }

        return grouped.map { (number, videos) ->
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain("$baseUrl/anime/$animeUrl?need_videos=true&episode=${number.encodeUrlQueryParam()}")
            episode.name = "Серия $number"
            episode.episode_number = videos.firstNotNullOfOrNull { it.number.toFloatOrNull() }
                ?: extractNumericPrefix(number)
                ?: 0f
            episode.date_upload = videos.maxOfOrNull { it.date }?.times(1000L) ?: 0L
            episode.scanlator = videos.mapNotNull { it.dubbing }
                .distinct()
                .joinToString(", ")
                .ifBlank { null }
            episode
        }
    }

    override fun videoListRequest(episode: SEpisode): Request {
        ensureConfigured()
        return GET("$baseUrl${episode.url}")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        Preference(screen.context).apply {
            title = "YummyAnime API tokens"
            summary = "PUBLIC TOKEN обязателен. PRIVATE TOKEN опционален."
            isSelectable = false
        }.also(screen::addPreference)

        val publicTokenPref = EditTextPreference(screen.context).apply {
            key = PREF_PUBLIC_TOKEN
            title = "PUBLIC TOKEN"
            summary = publicToken
            dialogTitle = title
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val newVal = (newValue as String).trim().ifBlank { DEFAULT_PUBLIC_TOKEN }
                preference.summary = newVal
                true
            }
        }
        screen.addPreference(publicTokenPref)

        Preference(screen.context).apply {
            title = "Сбросить PUBLIC TOKEN"
            summary = "Вернуть значение по умолчанию: $DEFAULT_PUBLIC_TOKEN"
            setOnPreferenceClickListener {
                preferences.edit().putString(PREF_PUBLIC_TOKEN, DEFAULT_PUBLIC_TOKEN).apply()
                publicTokenPref.text = DEFAULT_PUBLIC_TOKEN
                publicTokenPref.summary = DEFAULT_PUBLIC_TOKEN
                Toast.makeText(screen.context, "Public Token сброшен", Toast.LENGTH_SHORT).show()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_PRIVATE_TOKEN
            title = "PRIVATE TOKEN"
            summary = privateToken.maskedTokenSummary("Опционально: токен для Authorization: Yummy <token>")
            dialogTitle = title
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setOnPreferenceChangeListener { preference, newValue ->
                preference.summary = (newValue as String).trim()
                    .maskedTokenSummary("Опционально: токен для Authorization: Yummy <token>")
                true
            }
        }.also(screen::addPreference)

        val debugLogViewer = EditTextPreference(screen.context).apply {
            key = PREF_DEBUG_LOG_VIEWER
            title = "Debug лог (нажми, чтобы открыть)"
            summary = debugLogSummary()
            dialogTitle = "YummyAnime debug лог"
            setOnBindEditTextListener { editText ->
                val currentLog = getDebugLogText().ifBlank { "Лог пуст." }
                editText.setText(currentLog)
                editText.setSelection(currentLog.length)
                editText.inputType =
                    InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                editText.minLines = 10
                editText.maxLines = 20
                editText.isSingleLine = false
                editText.setHorizontallyScrolling(false)
            }
            setOnPreferenceClickListener {
                summary = debugLogSummary()
                text = getDebugLogText().ifBlank { "Лог пуст." }
                true
            }
            setOnPreferenceChangeListener { _, _ -> false }
        }.also(screen::addPreference)

        val uploadLinkViewer = EditTextPreference(screen.context).apply {
            key = PREF_DEBUG_UPLOAD_URL_VIEWER
            title = "Ссылка на выгруженный лог"
            summary = getUploadedDebugLogUrl().ifBlank { "Пока нет" }
            dialogTitle = "Ссылка на temp.sh"
            setOnBindEditTextListener { editText ->
                val url = getUploadedDebugLogUrl().ifBlank { "Пока нет" }
                editText.setText(url)
                editText.setSelection(url.length)
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            setOnPreferenceClickListener {
                val url = getUploadedDebugLogUrl().ifBlank { "Пока нет" }
                summary = url
                text = url
                true
            }
            setOnPreferenceChangeListener { _, _ -> false }
        }.also(screen::addPreference)

        Preference(screen.context).apply {
            title = "Выгрузить debug лог на temp.sh"
            summary = "Загрузить текущий лог и получить ссылку"
            setOnPreferenceClickListener {
                val logText = getDebugLogText()
                if (logText.isBlank()) {
                    Toast.makeText(screen.context, "Лог пуст, выгружать нечего", Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceClickListener true
                }

                isEnabled = false
                summary = "Загрузка..."

                Thread {
                    val uploadedUrl = uploadDebugLogToTempSh(logText)
                    Handler(Looper.getMainLooper()).post {
                        isEnabled = true
                        summary = "Загрузить текущий лог и получить ссылку"

                        if (uploadedUrl != null) {
                            saveUploadedDebugLogUrl(uploadedUrl)
                            uploadLinkViewer.summary = uploadedUrl
                            uploadLinkViewer.text = uploadedUrl
                            Toast.makeText(screen.context, "Лог выгружен", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(screen.context, "Не удалось выгрузить лог", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
                true
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DEBUG_ENABLED
            title = "Включить debug лог"
            setDefaultValue(false)
            summary = if (isDebugEnabled()) {
                "Включено. Ошибки и шаги извлечения видео сохраняются в настройках."
            } else {
                "Выключено."
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val enabled = newValue as Boolean
                preference.summary = if (enabled) {
                    "Включено. Ошибки и шаги извлечения видео сохраняются в настройках."
                } else {
                    "Выключено."
                }
                true
            }
        }.also(screen::addPreference)

        Preference(screen.context).apply {
            title = "Очистить debug лог"
            summary = "Удалить сохраненные строки диагностики"
            setOnPreferenceClickListener {
                clearDebugLog()
                debugLogViewer.summary = debugLogSummary()
                debugLogViewer.text = "Лог пуст."
                Toast.makeText(screen.context, "Debug лог очищен", Toast.LENGTH_SHORT).show()
                true
            }
        }.also(screen::addPreference)
    }

    override fun videoListParse(response: Response): List<Video> {
        val requestedEpisode = response.request.url.queryParameter("episode")
            ?.decodeUrlQueryParam()
            ?: response.request.url.fragment
                ?.substringAfter("episode=")
                ?.decodeUrlFragment()
            ?: return emptyList<Video>().also {
                logE("Missing requested episode in URL: ${response.request.url}")
            }

        val anime = response.asJsonObject().optJSONObject("response") ?: return emptyList()
        val allVideos = parseVideoEntries(anime.optJSONArray("videos"))
        val matchingVideos = allVideos
            .filter { it.number == requestedEpisode }
            .sortedWith(
                compareBy<YummyVideoEntry> { providerPriority(it.player) }
                    .thenBy { it.index }
                    .thenBy { it.player.lowercase(Locale.ROOT) },
            )
        if (matchingVideos.isEmpty()) {
            logE(
                "No matching videos for episode=\"$requestedEpisode\". " +
                    "Available episode numbers: ${allVideos.map { it.number }.distinct().joinToString()}",
            )
        }

        val extracted = mutableListOf<Video>()
        matchingVideos.forEach { entry ->
            val labelPrefix = buildString {
                append(entry.player)
                entry.dubbing?.takeIf(String::isNotBlank)?.let {
                    append(" - ")
                    append(it)
                }
            }

            when {
                entry.player.contains("Kodik", ignoreCase = true) -> {
                    runCatching {
                        KodikExtractor(client).videosFromUrl(
                            iframeUrl = entry.iframeUrl,
                            referer = SITE_URL,
                            labelPrefix = labelPrefix,
                        )
                    }.onSuccess { videos ->
                        extracted += videos
                        if (videos.isEmpty()) {
                            logE("Kodik extractor returned empty list. iframe=${entry.iframeUrl}")
                        }
                    }.onFailure { error ->
                        logE("Kodik extractor failed. iframe=${entry.iframeUrl}", error)
                    }
                }
                else -> {
                    logE("Unsupported player=${entry.player} for iframe=${entry.iframeUrl}")
                }
            }
        }

        val result = extracted
            .distinctBy { Triple(it.url, it.quality, it.videoUrl) }
            .ifEmpty { emptyList() }
        if (result.isEmpty()) {
            logE("No available videos after extraction for episode=\"$requestedEpisode\"")
        }
        return result
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("YummyAnime filters"),
        AnimeFilter.Header("Жанры указываются alias-ами через запятую: drama, romantika, isekai"),
        GenresFilter(),
        ExcludedGenresFilter(),
        StatusFilter(),
        TypeFilter(),
        TranslateFilter(),
        AgeFilter(),
        SortFilter(),
        OrderFilter(),
        YearFromFilter(),
        YearToFilter(),
        RatingFromFilter(),
        RatingToFilter(),
        EpisodeFromFilter(),
        EpisodeToFilter(),
    )

    private fun buildAnimeListUrl(page: Int, query: String, filters: FilterValues): String {
        val urlBuilder = "$API_BASE_URL/anime".toHttpUrl().newBuilder()
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("q", query)
        }

        filters.genres.forEach { urlBuilder.addQueryParameter("genres", it) }
        filters.excludeGenres.forEach { urlBuilder.addQueryParameter("exclude_genres", it) }
        filters.status?.let { urlBuilder.addQueryParameter("status", it) }
        filters.type?.let { urlBuilder.addQueryParameter("types", it) }
        filters.translate?.let { urlBuilder.addQueryParameter("translates", it) }
        filters.minAge?.let { urlBuilder.addQueryParameter("min_age", it) }
        filters.sort?.let { urlBuilder.addQueryParameter("sort", it) }
        filters.sortForward?.let { urlBuilder.addQueryParameter("sort_forward", it.toString()) }
        filters.yearFrom?.let { urlBuilder.addQueryParameter("from_year", it.toString()) }
        filters.yearTo?.let { urlBuilder.addQueryParameter("to_year", it.toString()) }
        filters.ratingFrom?.let { urlBuilder.addQueryParameter("min_rating", it.toString()) }
        filters.ratingTo?.let { urlBuilder.addQueryParameter("max_rating", it.toString()) }
        filters.episodeFrom?.let { urlBuilder.addQueryParameter("ep_from", it.toString()) }
        filters.episodeTo?.let { urlBuilder.addQueryParameter("ep_to", it.toString()) }

        return urlBuilder.build().toString()
    }

    private fun animeListItemToSAnime(obj: JSONObject): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain("$baseUrl/anime/${obj.optString("anime_url")}")
        anime.title = obj.optString("title")
        anime.thumbnail_url = obj.optJSONObject("poster").bestPosterUrl()
        anime.description = obj.optStringOrNull("description")
        anime.genre = obj.optJSONArray("genres")
            .jsonObjects()
            .mapNotNull { it.optStringOrNull("title") }
            .joinToString(", ")
            .ifBlank { null }
        anime.status = mapStatus(obj.optJSONObject("anime_status")?.optString("alias"))
        anime.author = null
        anime.artist = null
        anime.initialized = false
        return anime
    }

    private fun animeDetailsToSAnime(obj: JSONObject): SAnime {
        val anime = animeListItemToSAnime(obj)
        anime.author = obj.optJSONArray("creators")
            .jsonObjects()
            .mapNotNull { it.optStringOrNull("title")?.trim() }
            .distinct()
            .joinToString(", ")
            .ifBlank { null }
        anime.artist = obj.optJSONArray("studios")
            .jsonObjects()
            .mapNotNull { it.optStringOrNull("title")?.trim() }
            .distinct()
            .joinToString(", ")
            .ifBlank { null }

        val metadata = buildList {
            obj.optJSONObject("type")?.optStringOrNull("name")?.let { add(it) }
            obj.optInt("year").takeIf { it > 0 }?.let { add(it.toString()) }
            obj.optJSONObject("min_age")?.optStringOrNull("title_long")?.let { add(it) }
            obj.optStringOrNull("original")?.let { add("Источник: $it") }
            obj.optJSONArray("other_titles")
                .jsonStrings()
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
                ?.let { add("Другие названия: $it") }
        }

        val baseDescription = obj.optStringOrNull("description").orEmpty()
        anime.description = listOf(baseDescription, metadata.joinToString("\n"))
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .ifBlank { null }
        anime.initialized = true
        return anime
    }

    private fun scheduleItemToSAnime(obj: JSONObject): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain("$baseUrl/anime/${obj.optString("anime_url")}")
        anime.title = obj.optString("title")
        anime.thumbnail_url = obj.optJSONObject("poster").bestPosterUrl()
        anime.description = obj.optStringOrNull("description")
        anime.genre = null
        anime.status = SAnime.UNKNOWN
        anime.author = null
        anime.artist = null
        anime.initialized = false
        return anime
    }

    private fun parseVideoEntries(videosArray: JSONArray?): List<YummyVideoEntry> {
        return videosArray.jsonObjects()
            .mapNotNull { obj ->
                val player = obj.optJSONObject("data")?.optStringOrNull("player") ?: return@mapNotNull null
                val iframeUrl = normalizeUrl(obj.optString("iframe_url"))
                if (iframeUrl.isBlank()) return@mapNotNull null

                YummyVideoEntry(
                    number = obj.optString("number").trim(),
                    player = player,
                    dubbing = obj.optJSONObject("data")?.optStringOrNull("dubbing"),
                    iframeUrl = iframeUrl,
                    index = obj.optInt("index", Int.MAX_VALUE),
                    date = obj.optLong("date"),
                )
            }
    }

    private fun mapStatus(status: String?): Int = when (status) {
        "released" -> SAnime.COMPLETED
        "ongoing" -> SAnime.ONGOING
        "announcement", "announce" -> SAnime.ON_HIATUS
        else -> SAnime.UNKNOWN
    }

    private fun providerPriority(name: String): Int = when {
        name.contains("Kodik", ignoreCase = true) -> 0
        else -> 100
    }

    private fun ensureConfigured() {
        require(publicToken.isNotBlank()) {
            "Set PUBLIC TOKEN in the YummyAnime extension settings."
        }
    }

    private fun isDebugEnabled(): Boolean {
        return preferences.getBoolean(PREF_DEBUG_ENABLED, false)
    }

    private fun getDebugLogText(): String {
        return preferences.getString(PREF_DEBUG_LOG, "")!!.trim()
    }

    private fun clearDebugLog() {
        preferences.edit().remove(PREF_DEBUG_LOG).apply()
    }

    private fun getUploadedDebugLogUrl(): String {
        return preferences.getString(PREF_DEBUG_UPLOAD_URL, "")!!.trim()
    }

    private fun saveUploadedDebugLogUrl(url: String) {
        preferences.edit().putString(PREF_DEBUG_UPLOAD_URL, url.trim()).apply()
    }

    private fun appendDebugLogLine(message: String) {
        if (!isDebugEnabled()) return

        val line = "${System.currentTimeMillis()} | $message"
        val current = preferences.getString(PREF_DEBUG_LOG, "").orEmpty()
        val combined = buildString {
            if (current.isNotBlank()) {
                append(current)
                append('\n')
            }
            append(line)
        }
        val trimmed = combined.lineSequence()
            .toList()
            .takeLast(DEBUG_LOG_MAX_LINES)
            .joinToString("\n")
            .takeLast(DEBUG_LOG_MAX_CHARS)

        preferences.edit().putString(PREF_DEBUG_LOG, trimmed).apply()
    }

    private fun debugLogSummary(): String {
        val text = getDebugLogText()
        if (text.isBlank()) return "Лог пуст"
        val lines = text.lineSequence().count()
        return "Строк: $lines, символов: ${text.length}"
    }

    private fun uploadDebugLogToTempSh(logText: String): String? {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "yummyanime-debug-${System.currentTimeMillis()}.log",
                logText.toRequestBody("text/plain; charset=utf-8".toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url("https://temp.sh/upload")
            .post(requestBody)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val responseText = response.body.string().trim()
                responseText.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            }
        }.getOrElse { error ->
            logE("Debug log upload failed", error)
            null
        }
    }

    private fun sortEpisodeNumber(number: String): Float {
        return number.toFloatOrNull()
            ?: extractNumericPrefix(number)
            ?: Float.MAX_VALUE
    }

    private fun extractNumericPrefix(value: String): Float? {
        return Regex("""(\d+(?:\.\d+)?)""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
    }

    private data class YummyVideoEntry(
        val number: String,
        val player: String,
        val dubbing: String?,
        val iframeUrl: String,
        val index: Int,
        val date: Long,
    )

    private data class FilterValues(
        val genres: List<String> = emptyList(),
        val excludeGenres: List<String> = emptyList(),
        val status: String? = null,
        val type: String? = null,
        val translate: String? = null,
        val minAge: String? = null,
        val sort: String? = null,
        val sortForward: Boolean? = null,
        val yearFrom: Int? = null,
        val yearTo: Int? = null,
        val ratingFrom: Double? = null,
        val ratingTo: Double? = null,
        val episodeFrom: Int? = null,
        val episodeTo: Int? = null,
    ) {
        fun isEmpty(): Boolean = this == default()

        companion object {
            fun default() = FilterValues(sort = "top", sortForward = false)

            fun from(filters: AnimeFilterList): FilterValues {
                if (filters.isEmpty()) return default()

                return FilterValues(
                    genres = filters.find<GenresFilter>()?.state?.parseAliases().orEmpty(),
                    excludeGenres = filters.find<ExcludedGenresFilter>()?.state?.parseAliases().orEmpty(),
                    status = filters.find<StatusFilter>()?.selectedValue?.takeIf(String::isNotBlank),
                    type = filters.find<TypeFilter>()?.selectedValue?.takeIf(String::isNotBlank),
                    translate = filters.find<TranslateFilter>()?.selectedValue?.takeIf(String::isNotBlank),
                    minAge = filters.find<AgeFilter>()?.selectedValue?.takeIf(String::isNotBlank),
                    sort = filters.find<SortFilter>()?.selectedValue ?: "top",
                    sortForward = filters.find<OrderFilter>()?.selectedValue != "desc",
                    yearFrom = filters.find<YearFromFilter>()?.state?.toIntOrNull(),
                    yearTo = filters.find<YearToFilter>()?.state?.toIntOrNull(),
                    ratingFrom = filters.find<RatingFromFilter>()?.state?.toDoubleOrNull(),
                    ratingTo = filters.find<RatingToFilter>()?.state?.toDoubleOrNull(),
                    episodeFrom = filters.find<EpisodeFromFilter>()?.state?.toIntOrNull(),
                    episodeTo = filters.find<EpisodeToFilter>()?.state?.toIntOrNull(),
                )
            }
        }
    }

    private open class TextFilter(name: String) : AnimeFilter.Text(name)

    private open class SelectFilter(
        name: String,
        private val options: Array<Pair<String, String>>,
        defaultIndex: Int = 0,
    ) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray(), defaultIndex) {
        val selectedValue: String
            get() = options.getOrElse(state) { options.first() }.second
    }

    private class GenresFilter : TextFilter("Genres aliases")
    private class ExcludedGenresFilter : TextFilter("Exclude genres aliases")
    private class YearFromFilter : TextFilter("Year from")
    private class YearToFilter : TextFilter("Year to")
    private class RatingFromFilter : TextFilter("Rating from")
    private class RatingToFilter : TextFilter("Rating to")
    private class EpisodeFromFilter : TextFilter("Episodes from")
    private class EpisodeToFilter : TextFilter("Episodes to")

    private class StatusFilter : SelectFilter("Status", STATUS_OPTIONS)
    private class TypeFilter : SelectFilter("Type", TYPE_OPTIONS)
    private class TranslateFilter : SelectFilter("Translation", TRANSLATE_OPTIONS)
    private class AgeFilter : SelectFilter("Age rating", AGE_OPTIONS)
    private class SortFilter : SelectFilter("Sort by", SORT_OPTIONS, defaultIndex = 5)
    private class OrderFilter : SelectFilter("Order", ORDER_OPTIONS, defaultIndex = 1)

    private companion object {
        private const val API_BASE_URL = "https://api.yani.tv"
        private const val SITE_URL = "https://yummyani.me/"
        private const val PAGE_SIZE = 20
        private const val DEFAULT_PUBLIC_TOKEN = "yp_ls7tmtv9n74hp"
        private const val PREF_PUBLIC_TOKEN = "public_token"
        private const val PREF_PRIVATE_TOKEN = "private_token"
        private const val PREF_DEBUG_ENABLED = "debug_enabled"
        private const val PREF_DEBUG_LOG = "debug_log"
        private const val PREF_DEBUG_LOG_VIEWER = "debug_log_viewer"
        private const val PREF_DEBUG_UPLOAD_URL = "debug_upload_url"
        private const val PREF_DEBUG_UPLOAD_URL_VIEWER = "debug_upload_url_viewer"
        private const val DEBUG_LOG_MAX_LINES = 300
        private const val DEBUG_LOG_MAX_CHARS = 40_000

        private val STATUS_OPTIONS = arrayOf(
            "All" to "",
            "Released" to "released",
            "Ongoing" to "ongoing",
            "Announcement" to "announcement",
        )

        private val TYPE_OPTIONS = arrayOf(
            "All" to "",
            "TV" to "tv",
            "Movie" to "movie",
            "Short film" to "shortfilm",
            "OVA" to "ova",
            "Special" to "special",
            "Short TV" to "shorttv",
            "ONA" to "ona",
        )

        private val TRANSLATE_OPTIONS = arrayOf(
            "All" to "",
            "Full dubbing" to "full",
            "Dubbing" to "dubbing",
            "Multivoice" to "multivoice",
            "One voice" to "onevoice",
            "Two voice" to "twovoice",
            "Subtitles" to "subtitles",
        )

        private val AGE_OPTIONS = arrayOf(
            "All" to "",
            "PG" to "1",
            "PG-13" to "2",
            "R-17+" to "3",
            "R+" to "4",
            "NC-21" to "5",
        )

        private val SORT_OPTIONS = arrayOf(
            "Title" to "title",
            "Year" to "year",
            "Rating" to "rating",
            "Rating counters" to "rating_counters",
            "Views" to "views",
            "Top" to "top",
            "Random" to "random",
            "ID" to "id",
        )

        private val ORDER_OPTIONS = arrayOf(
            "Ascending" to "asc",
            "Descending" to "desc",
        )
    }
}

private class KodikExtractor(
    private val client: OkHttpClient,
) {
    fun videosFromUrl(iframeUrl: String, referer: String, labelPrefix: String): List<Video> {
        val normalized = normalizeUrl(iframeUrl)
        val iframeReferer = if (referer.isNotBlank()) normalized else referer
        val page = fetchPage(normalized, iframeReferer)
        val context = parsePlayerContext(page, normalized) ?: return emptyList()
        val endpoint = detectActualEndpoint(page, normalized) ?: DEFAULT_ENDPOINT

        val json = requestLinks(endpoint, normalized, context) ?: return emptyList()
        val links = json.optJSONObject("links") ?: return emptyList<Video>().also {
            logE("Kodik response has no links: endpoint=$endpoint body=${json.toString().take(400)}")
        }

        val qualities = links.keys().asSequence().toList()
            .sortedByDescending { it.substringBefore('p').toIntOrNull() ?: 0 }

        val videos = mutableListOf<Video>()
        qualities.forEach { quality ->
            val sources = links.optJSONArray(quality).jsonObjects()
            sources.forEachIndexed { index, source ->
                val decodedUrl = source.optString("src").decodeKodikSource()
                    ?.let(::normalizeUrl)
                    ?: return@forEachIndexed

                val sourceType = source.optStringOrNull("type")
                val suffix = buildString {
                    append(quality)
                    sourceType?.let {
                        append(" ")
                        append(it.substringAfterLast('/'))
                    }
                    if (index > 0) {
                        append(" #")
                        append(index + 1)
                    }
                }

                videos += Video(
                    decodedUrl,
                    "$labelPrefix - $suffix",
                    decodedUrl,
                    mediaHeaders(normalized),
                )
            }
        }

        if (videos.isEmpty()) {
            logE("Kodik links parsed but produced 0 videos. endpoint=$endpoint")
        }
        return videos
    }

    private fun requestLinks(
        endpoint: String,
        referer: String,
        context: KodikPlayerContext,
    ): JSONObject? {
        val missingParam = REQUIRED_URL_PARAMS.firstOrNull { context.urlParams.optString(it).isBlank() }
        if (missingParam != null) {
            logE("Kodik missing urlParams.$missingParam for endpoint=$endpoint")
            return null
        }

        val ref = context.urlParams.optString("ref").decodeKodikRef()

        val body = FormBody.Builder()
            .add("hash", context.hash)
            .add("id", context.id)
            .add("type", context.type)
            .add("d", context.urlParams.optString("d"))
            .add("d_sign", context.urlParams.optString("d_sign"))
            .add("pd", context.urlParams.optString("pd"))
            .add("pd_sign", context.urlParams.optString("pd_sign"))
            .add("ref", ref)
            .add("ref_sign", context.urlParams.optString("ref_sign"))
            .add("bad_user", "true")
            .add("cdn_is_working", "true")
            .build()

        val requestHeaders = xhrHeaders(referer).newBuilder()
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .build()

        val candidateHosts = linkedSetOf<String>()
        normalizeHostUrl(context.urlParams.optString("d"))?.let(candidateHosts::add)
        candidateHosts += context.host
        normalizeHostUrl(context.urlParams.optString("pd"))?.let(candidateHosts::add)
        candidateHosts += "https://kodik.cc"
        candidateHosts += "https://kodik.info"

        candidateHosts.forEach { host ->
            val endpointUrl = resolveEndpointUrl(host, endpoint)
            val request = Request.Builder()
                .url(endpointUrl)
                .post(body)
                .headers(requestHeaders)
                .build()

            val parsed = runCatching {
                client.newCall(request).execute().use { response ->
                    val bodyText = response.body.string()
                    if (bodyText.trimStart().startsWith("{")) {
                        JSONObject(bodyText)
                    } else {
                        logE(
                            "Kodik endpoint non-JSON. url=$endpointUrl code=${response.code} " +
                                "ct=${response.header("Content-Type").orEmpty()} body=${bodyText.take(160)}",
                        )
                        null
                    }
                }
            }.getOrElse { error ->
                logE("Kodik endpoint request failed: $endpointUrl", error)
                null
            }

            if (parsed != null) {
                return parsed
            }
        }

        logE("Kodik request exhausted hosts: ${candidateHosts.joinToString()} endpoint=$endpoint")
        return null
    }

    private fun fetchPage(url: String, referer: String): String? {
        return runCatching {
            client.newCall(GET(url, browserHeaders(referer))).execute().use { it.body.string() }
        }.getOrElse { error ->
            logE("Kodik page fetch failed: $url", error)
            null
        }
    }

    private fun parsePlayerContext(page: String?, iframeUrl: String): KodikPlayerContext? {
        if (page == null) return null

        val type = extractQuotedLiteralAfter(page, "vInfo.type =")
            ?: extractQuotedLiteralAfter(page, "var type =")
        val hash = extractQuotedLiteralAfter(page, "vInfo.hash =")
        val id = extractQuotedLiteralAfter(page, "vInfo.id =")
            ?: extractQuotedLiteralAfter(page, "var videoId =")

        val rawUrlParams = extractQuotedLiteralAfter(page, "var urlParams =")
            ?: extractQuotedLiteralAfter(page, "urlParams =")
        val urlParams = rawUrlParams
            ?.let(::parseUrlParams)
            ?.let { mergeUrlParamsWithRawFallback(it, rawUrlParams) }

        if (type.isNullOrBlank() || hash.isNullOrBlank() || id.isNullOrBlank() || urlParams == null) {
            logE(
                "Kodik player context parse failed. iframe=$iframeUrl " +
                    "type=$type hash=$hash id=$id hasUrlParams=${urlParams != null}",
            )
            return null
        }

        val parsedUrl = iframeUrl.toHttpUrl()
        val host = buildString {
            append(parsedUrl.scheme)
            append("://")
            append(parsedUrl.host)
            if (parsedUrl.port != 80 && parsedUrl.port != 443) {
                append(":")
                append(parsedUrl.port)
            }
        }

        return KodikPlayerContext(
            host = host,
            type = type,
            id = id,
            hash = hash,
            urlParams = urlParams,
        )
    }

    private fun parseUrlParams(raw: String): JSONObject? {
        val candidates = linkedSetOf(
            raw.trim(),
            raw.replace("\\/", "/").trim(),
            raw.replace("\\\"", "\"").replace("\\/", "/").trim(),
            raw.replace("\\\\", "\\").replace("\\\"", "\"").replace("\\/", "/").trim(),
            raw.replace("&quot;", "\"").replace("\\/", "/").trim(),
        )
        return candidates.firstNotNullOfOrNull { candidate ->
            runCatching { JSONObject(candidate) }.getOrNull()
        }
    }

    private fun mergeUrlParamsWithRawFallback(parsed: JSONObject, raw: String): JSONObject {
        val normalizedRaw = raw
            .replace("\\\"", "\"")
            .replace("\\/", "/")

        REQUIRED_URL_PARAMS.forEach { key ->
            if (parsed.optString(key).isBlank()) {
                extractJsonStringValue(normalizedRaw, key)?.let { parsed.put(key, it) }
            }
        }
        return parsed
    }

    private fun extractJsonStringValue(raw: String, key: String): String? {
        val marker = "\"$key\":\""
        val start = raw.indexOf(marker)
        if (start < 0) return null
        val from = start + marker.length
        val end = raw.indexOf('"', from)
        if (end < 0) return null
        return raw.substring(from, end)
    }

    private fun extractQuotedLiteralAfter(source: String, anchor: String): String? {
        val anchorIndex = source.indexOf(anchor, ignoreCase = true)
        if (anchorIndex < 0) return null

        var position = anchorIndex + anchor.length
        while (position < source.length && source[position].isWhitespace()) {
            position++
        }
        if (position >= source.length) return null

        val quote = source[position]
        if (quote != '"' && quote != '\'') return null
        position++

        val value = StringBuilder()
        var escaped = false
        while (position < source.length) {
            val char = source[position++]
            if (escaped) {
                value.append(char)
                escaped = false
                continue
            }
            if (char == '\\') {
                value.append(char)
                escaped = true
                continue
            }
            if (char == quote) {
                return value.toString()
            }
            value.append(char)
        }

        return null
    }

    private fun detectActualEndpoint(page: String?, normalizedUrl: String): String? {
        val playerScriptPath = page
            ?.let(::extractPlayerScriptPath)
            ?: return null

        val scriptUrl = if (playerScriptPath.startsWith("http")) {
            playerScriptPath
        } else {
            "${normalizedUrl.toHttpUrl().scheme}://${normalizedUrl.toHttpUrl().host}$playerScriptPath"
        }

        val scriptBody = runCatching {
            client.newCall(GET(scriptUrl, browserHeaders(normalizedUrl))).execute().use { it.body.string() }
        }.getOrNull() ?: return null

        val encodedEndpoint = extractBetween(scriptBody, "url:atob(\"", "\")")
            ?: extractBetween(scriptBody, "url:atob('", "')")
            ?: return null

        return encodedEndpoint.decodeBase64()
    }

    private fun extractPlayerScriptPath(page: String): String? {
        val marker = "/assets/js/app.player_single."
        val start = page.indexOf(marker, ignoreCase = true)
        if (start < 0) return null

        val end = page.indexOf(".js", startIndex = start, ignoreCase = true)
        if (end < 0) return null

        return page.substring(start, end + 3)
    }

    private fun extractBetween(source: String, prefix: String, suffix: String): String? {
        val start = source.indexOf(prefix)
        if (start < 0) return null
        val valueStart = start + prefix.length
        val end = source.indexOf(suffix, startIndex = valueStart)
        if (end < 0) return null
        return source.substring(valueStart, end)
    }

    private fun normalizeHostUrl(value: String): String? {
        val trimmed = value.trim().removeSuffix("/")
        if (trimmed.isBlank()) return null

        val candidate = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }

        return runCatching {
            val parsed = candidate.toHttpUrl()
            "${parsed.scheme}://${parsed.host}"
        }.getOrNull()
    }

    private fun resolveEndpointUrl(host: String, endpoint: String): String {
        val normalizedEndpoint = endpoint.trim()
        return when {
            normalizedEndpoint.startsWith("http://") || normalizedEndpoint.startsWith("https://") -> normalizedEndpoint
            normalizedEndpoint.startsWith("/") -> "$host$normalizedEndpoint"
            else -> "$host/$normalizedEndpoint"
        }
    }

    private data class KodikPlayerContext(
        val host: String,
        val type: String,
        val id: String,
        val hash: String,
        val urlParams: JSONObject,
    )

    private companion object {
        private const val DEFAULT_ENDPOINT = "/ftor"
        private val REQUIRED_URL_PARAMS = listOf("d", "d_sign", "pd", "pd_sign", "ref", "ref_sign")
    }
}

private fun Response.asJsonObject(): JSONObject = use { JSONObject(it.body.string()) }

private fun JSONObject?.bestPosterUrl(): String? {
    if (this == null) return null
    return normalizeUrl(
        optStringOrNull("mega")
            ?: optStringOrNull("huge")
            ?: optStringOrNull("big")
            ?: optStringOrNull("medium")
            ?: optStringOrNull("fullsize")
            ?: "",
    ).ifBlank { null }
}

private fun JSONObject.optStringOrNull(key: String): String? {
    val value = optString(key).trim()
    return value.takeIf { it.isNotBlank() && it != "null" }
}

private fun JSONArray?.jsonObjects(): List<JSONObject> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(::add)
        }
    }
}

private fun JSONArray?.jsonStrings(): List<String> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private inline fun <reified T : AnimeFilter<*>> AnimeFilterList.find(): T? = firstOrNull { it is T } as? T

private fun String.parseAliases(): List<String> {
    return split(',', ';', '\n')
        .map { it.trim() }
        .filter(String::isNotBlank)
        .distinct()
}

private fun normalizeUrl(value: String): String {
    val trimmed = value.trim()
    return when {
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        else -> trimmed
    }
}

private fun browserHeaders(referer: String): Headers = Headers.Builder()
    .add("Referer", referer)
    .add("Origin", "${referer.toHttpUrl().scheme}://${referer.toHttpUrl().host}")
    .add("User-Agent", DEFAULT_USER_AGENT)
    .build()

private fun xhrHeaders(referer: String): Headers = browserHeaders(referer).newBuilder()
    .add("X-Requested-With", "XMLHttpRequest")
    .build()

private fun mediaHeaders(referer: String): Headers = Headers.Builder()
    .add("Referer", referer)
    .add("Origin", "${referer.toHttpUrl().scheme}://${referer.toHttpUrl().host}")
    .add("User-Agent", DEFAULT_USER_AGENT)
    .build()

private fun String.encodeUrlFragment(): String = Uri.encode(this)

private fun String.decodeUrlFragment(): String = Uri.decode(this)

private fun String.encodeUrlQueryParam(): String = Uri.encode(this)

private fun String.decodeUrlQueryParam(): String = Uri.decode(this)

private fun String.decodeKodikSource(): String? {
    if (startsWith("http://") || startsWith("https://")) return this

    for (shift in 0..25) {
        val shifted = map { char ->
            when (char) {
                in 'a'..'z' -> 'a' + ((char - 'a' + shift) % 26)
                in 'A'..'Z' -> 'A' + ((char - 'A' + shift) % 26)
                else -> char
            }
        }.joinToString("")

        val decoded = shifted.decodeBase64()
        if (
            decoded != null &&
            (
                decoded.startsWith("http://") ||
                    decoded.startsWith("https://") ||
                    decoded.contains("mp4:hls:manifest")
                )
        ) {
            return decoded
        }
    }

    return null
}

private fun String.decodeKodikRef(): String {
    if (isBlank()) return this
    return runCatching { Uri.decode(this) }.getOrDefault(this)
}

private fun String.decodeBase64(): String? {
    return runCatching {
        val padded = this + "=".repeat((4 - this.length % 4) % 4)
        String(Base64.decode(padded, Base64.DEFAULT))
    }.getOrNull()
}

private fun String.maskedTokenSummary(emptySummary: String): String {
    if (isBlank()) return emptySummary
    if (length <= 8) return "Configured"
    return buildString {
        append(take(4))
        append("...")
        append(takeLast(4))
    }
}

private const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
private const val LOG_TAG = "YummyAnimeExt"

private object DebugLogBridge {
    @Volatile
    var sink: ((String) -> Unit)? = null
}

private fun logE(message: String, error: Throwable? = null) {
    val sinkMessage = if (error != null) {
        val causes = generateSequence(error) { it.cause }
            .joinToString(" -> ") { throwable ->
                "${throwable::class.java.simpleName}:${throwable.message.orEmpty()}"
            }
        "$message | $causes"
    } else {
        message
    }
    DebugLogBridge.sink?.invoke(sinkMessage)

    if (error != null) {
        Log.e(LOG_TAG, message, error)
    } else {
        Log.e(LOG_TAG, message)
    }
}
