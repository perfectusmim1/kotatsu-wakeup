package org.koitharu.kotatsu.scrobbling.anilist.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.parsers.exception.GraphQLException
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.toIntUp
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerStorage
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.ScrobblerAuthRequiredException
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private const val REDIRECT_URI = "kotatsu://anilist-auth"
private const val BASE_URL = "https://anilist.co/api/v2/"
private const val ENDPOINT = "https://graphql.anilist.co"
private const val MANGA_PAGE_SIZE = 10
private const val REQUEST_QUERY = "query"
private const val REQUEST_MUTATION = "mutation"
private const val KEY_SCORE_FORMAT = "score_format"

@Singleton
class AniListRepository @Inject constructor(
	@ApplicationContext context: Context,
	@ScrobblerType(ScrobblerService.ANILIST) private val okHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.ANILIST) private val storage: ScrobblerStorage,
	private val db: MangaDatabase,
) : ScrobblerRepository {

	private val clientId = context.getString(R.string.anilist_clientId)
	private val clientSecret = context.getString(R.string.anilist_clientSecret)

	override val oauthUrl: String
		get() = "${BASE_URL}oauth/authorize?client_id=$clientId&" +
			"redirect_uri=${REDIRECT_URI}&response_type=code"

	override val isAuthorized: Boolean
		get() = storage.accessToken != null

	private val shrinkRegex = Regex("\\t+")

	override suspend fun authorize(code: String?) {
		val body = FormBody.Builder()
		body.add("client_id", clientId)
		body.add("client_secret", clientSecret)
		if (code != null) {
			body.add("grant_type", "authorization_code")
			body.add("redirect_uri", REDIRECT_URI)
			body.add("code", code)
		} else {
			body.add("grant_type", "refresh_token")
			body.add("refresh_token", checkNotNull(storage.refreshToken))
		}
		val request = Request.Builder()
			.post(body.build())
			.url("${BASE_URL}oauth/token")
		val response = okHttp.newCall(request.build()).await().parseJson()
		storage.accessToken = response.getString("access_token")
		storage.refreshToken = response.getString("refresh_token")
	}

	override suspend fun loadUser(): ScrobblerUser {
		val response = doRequest(
			REQUEST_QUERY,
			"""
			AniChartUser {
				user {
					id
					name
					avatar {
						medium
					}
					mediaListOptions {
						scoreFormat
					}
				}
			}
		""",
		)
		val jo = response.getJSONObject("data").getJSONObject("AniChartUser").getJSONObject("user")
		storage[KEY_SCORE_FORMAT] = jo.getJSONObject("mediaListOptions").getString("scoreFormat")
		return AniListUser(jo).also { storage.user = it }
	}

	override val cachedUser: ScrobblerUser?
		get() {
			return storage.user
		}

	override suspend fun unregister(mangaId: Long) {
		return db.getScrobblingDao().delete(ScrobblerService.ANILIST.id, mangaId)
	}

	override fun logout() {
		storage.clear()
	}

	override suspend fun findManga(query: String, offset: Int): List<ScrobblerManga> {
		val page = (offset / MANGA_PAGE_SIZE.toFloat()).toIntUp() + 1
		val response = doRequest(
			REQUEST_QUERY,
			"""
			Page(page: $page, perPage: ${MANGA_PAGE_SIZE}) {
				media(type: MANGA, sort: SEARCH_MATCH, search: ${JSONObject.quote(query)}) {
					id
					title {
						userPreferred
						native
					}
					coverImage {
						medium
					}
					siteUrl
				}
			}
		""",
		)
		val data = response.getJSONObject("data").getJSONObject("Page").getJSONArray("media")
		return data.mapJSON { ScrobblerManga(it, query) }
	}

	override suspend fun createRate(mangaId: Long, scrobblerMangaId: Long) {
		val response = doRequest(
			REQUEST_MUTATION,
			"""
				SaveMediaListEntry(mediaId: $scrobblerMangaId) {
					id
					mediaId
					status
					notes
					score
					progress
				}
			""",
		)
		saveRate(response.getJSONObject("data").getJSONObject("SaveMediaListEntry"), mangaId)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
		val response = doRequest(
			REQUEST_MUTATION,
			"""
				SaveMediaListEntry(id: $rateId, progress: $chapter) {
					id
					mediaId
					status
					notes
					score
					progress
				}
			""",
		)
		saveRate(response.getJSONObject("data").getJSONObject("SaveMediaListEntry"), mangaId)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, rating: Float, status: String?, comment: String?) {
		val scoreRaw = (rating * 100f).roundToInt()
		val statusString = status?.let { ", status: $it" }.orEmpty()
		val notesString = comment?.let { ", notes: ${JSONObject.quote(it)}" }.orEmpty()
		val response = doRequest(
			REQUEST_MUTATION,
			"""
				SaveMediaListEntry(id: $rateId, scoreRaw: $scoreRaw$statusString$notesString) {
					id
					mediaId
					status
					notes
					score
					progress
				}
			""",
		)
		saveRate(response.getJSONObject("data").getJSONObject("SaveMediaListEntry"), mangaId)
	}

	override suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo {
		val response = doRequest(
			REQUEST_QUERY,
			"""
			Media(id: $id) {
				id
				title {
					userPreferred
				}
				coverImage {
					large
				}
				description
				siteUrl
			}
			""",
		)
		return ScrobblerMangaInfo(response.getJSONObject("data").getJSONObject("Media"))
	}

	suspend fun fetchUserMediaList(): List<AniListMediaListEntry> {
		if (!isAuthorized) {
			throw ScrobblerAuthRequiredException(ScrobblerService.ANILIST)
		}
		val user = cachedUser ?: loadUser()
		val response = doRequest(
			REQUEST_QUERY,
			"""
			MediaListCollection(userId: ${user.id}, type: MANGA) {
				lists {
					name
					isCustomList
					status
					entries {
						id
						status
						progress
						score(format: POINT_100)
						media {
							id
							title {
								romaji
								english
								native
								userPreferred
							}
							synonyms
							coverImage {
								large
							}
							chapters
							siteUrl
						}
					}
				}
			}
			""",
		)
		val lists = response.optJSONObject("data")
			?.optJSONObject("MediaListCollection")
			?.optJSONArray("lists")
			?: return emptyList()

		val byMediaId = LinkedHashMap<Long, AniListMediaListEntry>()
		val customSeen = HashSet<Long>()
		for (i in 0 until lists.length()) {
			val listObj = lists.optJSONObject(i) ?: continue
			val isCustom = listObj.optBoolean("isCustomList", false)
			val listStatus = listObj.getStringOrNull("status")
			val entries = listObj.optJSONArray("entries") ?: continue
			for (j in 0 until entries.length()) {
				val entryObj = entries.optJSONObject(j) ?: continue
				val parsed = parseMediaListEntry(entryObj, listStatus) ?: continue
				val existing = byMediaId[parsed.mediaId]
				if (existing == null || (parsed.mediaId in customSeen && !isCustom)) {
					byMediaId[parsed.mediaId] = parsed
					if (isCustom) {
						customSeen.add(parsed.mediaId)
					} else {
						customSeen.remove(parsed.mediaId)
					}
				}
			}
		}
		return byMediaId.values.toList()
	}

	private fun parseMediaListEntry(json: JSONObject, fallbackStatus: String?): AniListMediaListEntry? {
		val media = json.optJSONObject("media") ?: return null
		val mediaId = media.optLong("id", 0L)
		if (mediaId <= 0L) return null
		val titleObj = media.optJSONObject("title") ?: return null
		val romaji = titleObj.getStringOrNull("romaji")?.takeIf { it.isNotBlank() }
		val english = titleObj.getStringOrNull("english")?.takeIf { it.isNotBlank() }
		val native = titleObj.getStringOrNull("native")?.takeIf { it.isNotBlank() }
		val userPreferred = titleObj.getStringOrNull("userPreferred")?.takeIf { it.isNotBlank() }
			?: english
			?: romaji
			?: native
			?: return null
		val synonymsArray = media.optJSONArray("synonyms")
		val synonyms = if (synonymsArray != null) {
			buildList(synonymsArray.length()) {
				for (k in 0 until synonymsArray.length()) {
					if (!synonymsArray.isNull(k)) {
						val syn = synonymsArray.optString(k).takeIf { it.isNotBlank() }
						if (syn != null) add(syn)
					}
				}
			}
		} else {
			emptyList()
		}
		val status = json.getStringOrNull("status") ?: fallbackStatus ?: "CURRENT"
		return AniListMediaListEntry(
			listEntryId = json.optInt("id", 0),
			mediaId = mediaId,
			status = status,
			progress = json.optInt("progress", 0).coerceAtLeast(0),
			scorePoint100 = json.optInt("score", 0).coerceIn(0, 100),
			titleUserPreferred = userPreferred,
			titleRomaji = romaji,
			titleEnglish = english,
			titleNative = native,
			synonyms = synonyms,
			coverUrl = media.optJSONObject("coverImage")?.getStringOrNull("large"),
			totalChapters = if (media.isNull("chapters")) null else media.optInt("chapters", 0).takeIf { it > 0 },
			siteUrl = media.getStringOrNull("siteUrl"),
		)
	}

	private suspend fun saveRate(json: JSONObject, mangaId: Long) {
		val scoreFormat = ScoreFormat.of(storage[KEY_SCORE_FORMAT])
		val entity = ScrobblingEntity(
			scrobbler = ScrobblerService.ANILIST.id,
			id = json.getInt("id"),
			mangaId = mangaId,
			targetId = json.getLong("mediaId"),
			status = json.getString("status"),
			chapter = json.getInt("progress"),
			comment = json.getString("notes"),
			rating = scoreFormat.normalize(json.getDouble("score").toFloat()),
		)
		db.getScrobblingDao().upsert(entity)
	}

	private fun ScrobblerManga(json: JSONObject, sourceTitle: String): ScrobblerManga {
		val title = json.getJSONObject("title")
		return ScrobblerManga(
			id = json.getLong("id"),
			name = title.getString("userPreferred"),
			altName = title.getStringOrNull("native"),
			cover = json.getJSONObject("coverImage").getString("medium"),
			url = json.getString("siteUrl"),
			isBestMatch = sourceTitle.let {
				title.keys().forEach { key ->
					if (title.getStringOrNull(key)?.equals(it, ignoreCase = true) == true) {
						return@let true
					}
				}
				false
			},
		)
	}

	private fun ScrobblerMangaInfo(json: JSONObject) = ScrobblerMangaInfo(
		id = json.getLong("id"),
		name = json.getJSONObject("title").getString("userPreferred"),
		cover = json.getJSONObject("coverImage").getString("large"),
		url = json.getString("siteUrl"),
		descriptionHtml = json.getString("description"),
	)

	@Suppress("FunctionName")
	private fun AniListUser(json: JSONObject) = ScrobblerUser(
		id = json.getLong("id"),
		nickname = json.getString("name"),
		avatar = json.getJSONObject("avatar").getStringOrNull("medium"),
		service = ScrobblerService.ANILIST,
	)

	private suspend fun doRequest(type: String, payload: String): JSONObject {
		val body = JSONObject()
		body.put("query", "$type { ${payload.shrink()} }")
		val mediaType = "application/json; charset=utf-8".toMediaType()
		val requestBody = body.toString().toRequestBody(mediaType)
		val request = Request.Builder()
			.post(requestBody)
			.url(ENDPOINT)
		val json = okHttp.newCall(request.build()).await().parseJson()
		json.optJSONArray("errors")?.let {
			if (it.length() != 0) {
				throw GraphQLException(it)
			}
		}
		return json
	}

	private fun String.shrink() = replace(shrinkRegex, " ")
}

data class AniListMediaListEntry(
	val listEntryId: Int,
	val mediaId: Long,
	val status: String,
	val progress: Int,
	val scorePoint100: Int,
	val titleUserPreferred: String,
	val titleRomaji: String?,
	val titleEnglish: String?,
	val titleNative: String?,
	val synonyms: List<String>,
	val coverUrl: String?,
	val totalChapters: Int?,
	val siteUrl: String?,
) {

	val scrobblingStatus: ScrobblingStatus
		get() = when (status) {
			"PLANNING" -> ScrobblingStatus.PLANNED
			"CURRENT" -> ScrobblingStatus.READING
			"REPEATING" -> ScrobblingStatus.RE_READING
			"COMPLETED" -> ScrobblingStatus.COMPLETED
			"PAUSED" -> ScrobblingStatus.ON_HOLD
			"DROPPED" -> ScrobblingStatus.DROPPED
			else -> ScrobblingStatus.READING
		}

	fun searchTitles(): List<String> = buildList {
		titleEnglish?.takeIf { it.isNotBlank() }?.let(::add)
		titleRomaji?.takeIf { it.isNotBlank() }?.let(::add)
		if (titleUserPreferred.isNotBlank()) add(titleUserPreferred)
		for (syn in synonyms) {
			if (syn.isNotBlank()) add(syn)
		}
		titleNative?.takeIf { it.isNotBlank() }?.let(::add)
	}.distinct()
}
