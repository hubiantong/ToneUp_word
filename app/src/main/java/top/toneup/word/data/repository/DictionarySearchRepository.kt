package top.toneup.word.data.repository

import retrofit2.HttpException
import top.toneup.word.core.result.AppError
import top.toneup.word.core.result.Outcome
import top.toneup.word.data.gateway.DictEntry
import top.toneup.word.data.gateway.GatewayClientProvider
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.SocketTimeoutException

/** One candidate the full-screen dictionary picker can hand back to the write-word form. */
data class DictionaryHit(
    val word: String,
    val phonetic: String = "",
    /** Chinese translation, with the gateway's newline-separated lines joined by「；」. */
    val translation: String = "",
)

/**
 * The gateway's `/dict` endpoint as a **plain search** — deliberately not a meaning lookup.
 *
 * `MeaningLookupService` owns the ECDICT → FreeDictionary → LLM cascade and its 24h shared
 * cache; this repository must not touch either. It only asks ECDICT what words start with the
 * learner's letters (the gateway answers an exact hit with `entry`, and a miss with at most ten
 * frequency-ordered `suggestions`), so typing `ent` can offer `entire` / `enter` / … without a
 * local prefix index and without polluting `meaning_cache`.
 *
 * Every failure is a value; nothing is cached here, so a retry costs exactly one request.
 */
interface DictionarySearchRepository {

    /** Empty query → `Success(emptyList())` without a request. */
    suspend fun search(query: String): Outcome<List<DictionaryHit>>
}

class DefaultDictionarySearchRepository(
    private val clients: GatewayClientProvider,
) : DictionarySearchRepository {

    override suspend fun search(query: String): Outcome<List<DictionaryHit>> {
        val token = query.trim()
        if (token.isEmpty()) return Outcome.Success(emptyList())

        val session = when (val opened = clients.session()) {
            is Outcome.Success -> opened.value
            is Outcome.Failure -> return opened
        }
        val url = session.config.baseUrl.trim().trimEnd('/') + "/" +
            session.config.dictPath.trimStart('/')

        return try {
            val response = session.api.dict(url, token)
            val entry = response.entry
            val hits = if (response.found && entry != null) {
                listOf(entry.toHit())
            } else {
                // The gateway already orders suggestions by frequency and caps them at ten.
                // De-duplicating here is what keeps the picker's LazyColumn keys unique, and the
                // explicit cap survives a gateway that one day returns more than it promises.
                response.suggestions
                    .asSequence()
                    .mapNotNull { suggestion ->
                        suggestion.word.trim().takeIf { it.isNotEmpty() }?.let { word ->
                            DictionaryHit(
                                word = word,
                                phonetic = suggestion.phonetic.trim(),
                                translation = joinTranslation(suggestion.translation),
                            )
                        }
                    }
                    .distinctBy { it.word }
                    .take(MAX_SUGGESTIONS)
                    .toList()
            }
            Outcome.Success(hits)
        } catch (cancelled: CancellationException) {
            // A newer keystroke cancelled this request (`collectLatest`); that is not a failure.
            throw cancelled
        } catch (t: Throwable) {
            // `/dict` normally answers `found=false`; a 404 means the same "nothing matched"
            // and must not be shown as an error the learner has to retry.
            if (t is HttpException && t.code() == 404) {
                Outcome.Success(emptyList())
            } else {
                Outcome.Failure(t.toSearchError())
            }
        }
    }

    private fun DictEntry.toHit(): DictionaryHit = DictionaryHit(
        word = word.trim(),
        phonetic = phonetic.trim(),
        translation = joinTranslation(translation),
    )

    /** Mirrors the cascade's per-line handling, but joins the lines for a one-row display. */
    private fun joinTranslation(raw: String): String = raw.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("；")

    /**
     * Same classification as [top.toneup.word.data.gateway.MeaningLookupService]'s call wrapper:
     * 401 is a configuration problem and every transport/parse failure is a retryable network
     * error (404 is handled by the caller as "nothing matched").
     */
    private fun Throwable.toSearchError(): AppError = when (this) {
        is HttpException -> when (code()) {
            401 -> AppError.config("HTTP 401")
            else -> AppError.network("HTTP ${code()}")
        }

        is SocketTimeoutException -> AppError.network("读取超时")
        is IOException -> AppError.network(message)
        is SerializationException -> AppError.network("响应无法解析")
        else -> AppError.unknown(message ?: javaClass.simpleName)
    }

    private companion object {
        /** The gateway's documented cap; applied client-side so the UI never renders more. */
        const val MAX_SUGGESTIONS = 10
    }
}
