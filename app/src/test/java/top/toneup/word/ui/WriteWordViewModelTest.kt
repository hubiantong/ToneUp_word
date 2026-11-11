package top.toneup.word.ui

import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import org.robolectric.Shadows.shadowOf
import top.toneup.word.AppContainer
import top.toneup.word.core.json.JsonCodec
import top.toneup.word.core.result.AppError
import top.toneup.word.core.result.Outcome
import top.toneup.word.data.gateway.LookupResult
import top.toneup.word.data.local.ToneUpWordDatabase
import top.toneup.word.data.repository.BatchReport
import top.toneup.word.data.repository.DictionaryHit
import top.toneup.word.data.repository.DictionarySearchRepository
import top.toneup.word.data.repository.MeaningSyncRepository
import top.toneup.word.data.settings.PlanIds
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The 「写词库」 ViewModel: the one-word-at-a-time write path into the studied pack, the live
 * "本次已写入" projection, the in-card pack creation, and the gateway picker's debounce rules.
 *
 * Runs against a **real in-memory Room database and the real DataStore** through [AppContainer]
 * (the write semantics — PENDING rows, identity by `(pack, normalized word)` — are exactly what
 * the learner depends on, so faking the repository would test nothing). [MeaningSyncRepository]
 * is faked because WorkManager has no test artifact in this project, and the gateway search is
 * faked so no test ever touches the network.
 *
 * Two behaviours carry the real risk:
 *  * the duplicate-key crash — writing the same word twice returns the *same* row id, and a
 *    recent list that keeps both copies would hand the LazyColumn a duplicated key;
 *  * the live list — the meaning shown next to a word must arrive from the `word` row *after*
 *    the write, because the gateway worker writes it asynchronously.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class WriteWordViewModelTest {

    /** Records `enqueueAll` calls; everything else is unused by this ViewModel. */
    private class RecordingMeaningSync : MeaningSyncRepository {
        var enqueues: Int = 0

        override fun observePendingCount(): Flow<Int> = flowOf(0)

        override suspend fun enqueueAll(): Outcome<Unit> {
            enqueues++
            return Outcome.Success(Unit)
        }

        override suspend fun lookupWordNow(wordId: Long): Outcome<LookupResult> =
            Outcome.Failure(AppError.unknown("not under test"))

        override suspend fun runBatch(batchLimit: Int): BatchReport =
            BatchReport(processed = 0, abortedByConfig = false)

        override suspend fun applyLookupToWord(wordId: Long, result: LookupResult, now: Long) = Unit
    }

    /** Scriptable `/dict` search: records every query and every cancelled in-flight one. */
    private class FakeDictionarySearch : DictionarySearchRepository {
        val queries = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        var result: Outcome<List<DictionaryHit>> = Outcome.Success(emptyList())
        var handler: (suspend (String) -> Outcome<List<DictionaryHit>>)? = null

        override suspend fun search(query: String): Outcome<List<DictionaryHit>> {
            queries += query
            return try {
                handler?.invoke(query) ?: result
            } catch (cancellation: CancellationException) {
                cancelled += query
                throw cancellation
            }
        }
    }

    private lateinit var database: ToneUpWordDatabase
    private lateinit var container: AppContainer
    private lateinit var meaningSync: RecordingMeaningSync
    private lateinit var dictionarySearch: FakeDictionarySearch
    private lateinit var viewModel: WriteWordViewModel

    private val now = 1_768_450_800_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ToneUpWordDatabase::class.java,
        ).allowMainThreadQueries().build()
        database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
        container = AppContainer(context, database)
        // DataStore is shared between tests in the same process; start from a known state.
        runBlocking(Dispatchers.IO) { container.settingsRepository.resetToDefaults() }
        meaningSync = RecordingMeaningSync()
        dictionarySearch = FakeDictionarySearch()
        viewModel = WriteWordViewModel(
            container = container,
            meaningSync = meaningSync,
            dictionarySearch = dictionarySearch,
            // A short debounce keeps the tests fast without changing the production pipeline.
            searchDebounceMillis = DEBOUNCE_MILLIS,
        )
        // `state` is a `WhileSubscribed` StateFlow, so a live subscriber is needed for its upstream
        // to run; the coroutine lives in [viewModelScope] and is cancelled by [tearDown].
        viewModel.viewModelScope.launch { viewModel.state.collect { } }
    }

    @After
    fun tearDown() {
        // Drain the paused main looper after cancelling, so DataStore's write actor is released
        // before the next test class in the same JVM touches it.
        viewModel.viewModelScope.cancel()
        repeat(DRAIN_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        database.close()
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * Drains Robolectric's paused main looper (where ViewModel continuations land) and polls
     * [condition] until it holds, or fails loudly after a bounded real-time wait.
     *
     * `idleFor` (not `idle`) is what advances the paused clock, so the search pipeline's
     * `debounce` delay actually elapses.
     */
    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out waiting for: $description")
            }
            shadowOf(Looper.getMainLooper()).idleFor(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Selects [word] through the picker seam and submits, waiting until the write has landed. */
    private fun writeAndAwait(word: String, note: String = "") {
        // The form no longer types words directly; the picker is the only input path.
        viewModel.selectSearchHit(DictionaryHit(word = word))
        viewModel.onNoteChanged(note)
        viewModel.write()
        // Wait for *this* word's confirmation, not just "a message is showing": the previous
        // write's line is still up when this one starts and would be mistaken for completion by
        // a `!= null` check. (Callers writing the *same* word twice await the note instead —
        // the message text would be identical.)
        awaitCondition("the write of $word to land") {
            viewModel.state.value.message == "已写入「$word」。"
        }
        // The list is a live Room projection now, so its emission can trail the confirmation.
        awaitCondition("$word to appear in the written list") {
            viewModel.state.value.written.any { it.word.equals(word, ignoreCase = true) }
        }
    }

    /** The plan's「手写词」pack: what the ViewModel creates on the first write (D6). */
    private val writePackId = PlanIds.writePackIdFor(PlanIds.DEFAULT_PLAN)

    private fun rowFor(word: String) = runBlocking(Dispatchers.IO) {
        database.wordDao().byNormalized(writePackId, word)
    }

    private fun countInPack() = runBlocking(Dispatchers.IO) {
        database.wordDao().countInPack(writePackId)
    }

    /** Simulates `MeaningSyncWorker`'s write-back for the word just written. */
    private fun storeGatewayMeaning(word: String, meaning: String) {
        runBlocking(Dispatchers.IO) {
            val row = rowFor(word)!!
            database.wordDao().update(
                row.copy(
                    meanings = JsonCodec.encodeStrings(listOf(meaning)),
                    lookupState = "OK",
                    meaningSource = "ecdict",
                ),
            )
        }
    }

    // --- writing a word --------------------------------------------------------------------

    @Test
    fun `writing a word stores it as PENDING with the note and enqueues the gateway`() {
        writeAndAwait("apple", "苹果公司")

        val state = viewModel.state.value
        assertThat(state.written).hasSize(1)
        assertThat(state.written.single().word).isEqualTo("apple")
        assertThat(state.written.single().note).isEqualTo("苹果公司")
        assertThat(state.planId).isEqualTo(PlanIds.DEFAULT_PLAN)
        assertThat(state.wordInput).isEmpty()
        assertThat(state.noteInput).isEmpty()
        assertThat(state.saving).isFalse()

        val row = rowFor("apple")!!
        assertThat(row.planId).isEqualTo(PlanIds.DEFAULT_PLAN)
        assertThat(row.lookupState).isEqualTo("PENDING")
        assertThat(row.meanings).isEqualTo("[]")
        assertThat(row.userNote).isEqualTo("苹果公司")
        assertThat(meaningSync.enqueues).isEqualTo(1)
    }

    @Test
    fun `writing a blank word is refused and writes nothing`() {
        viewModel.write()
        awaitCondition("the empty-input message") { viewModel.state.value.message != null }

        assertThat(viewModel.state.value.message).isEqualTo("请输入要写入的单词。")
        assertThat(viewModel.state.value.written).isEmpty()
        assertThat(countInPack()).isEqualTo(0)
        assertThat(meaningSync.enqueues).isEqualTo(0)
    }

    /**
     * The regression this guards: a second write of the same word (case variant included) resolves
     * to the same row id, so keeping both copies on show would give the LazyColumn two identical
     * keys — which Compose rejects with an `IllegalArgumentException`.
     */
    @Test
    fun `writing the same word twice keeps a single recent row`() {
        writeAndAwait("apple", "第一版")
        writeAndAwait("Apple", "第二版")
        awaitCondition("the newer note to win") {
            viewModel.state.value.written.single().note == "第二版"
        }

        val written = viewModel.state.value.written
        assertThat(written).hasSize(1)
        assertThat(written.single().note).isEqualTo("第二版")
        assertThat(countInPack()).isEqualTo(1)
    }

    @Test
    fun `the recent list keeps only the newest MAX_RECENT words`() {
        val total = WriteWordViewModel.MAX_RECENT + 1
        repeat(total) { index -> writeAndAwait("word-${index + 1}") }

        awaitCondition("the oldest word to fall off") {
            viewModel.state.value.written.size == WriteWordViewModel.MAX_RECENT
        }
        val written = viewModel.state.value.written
        assertThat(written.first().word).isEqualTo("word-$total")
        assertThat(written.last().word).isEqualTo("word-2") // word-1 fell off the end
    }

    // --- commit feedback (session counter, inline message) -----------------------------------

    @Test
    fun `a successful write updates the session count`() {
        writeAndAwait("apple", "苹果")

        // The header is sized from this counter; it lands in the same state emission as the
        // confirmation message, so once [writeAndAwait] returned it is guaranteed visible.
        val state = viewModel.state.value
        assertThat(state.sessionCount).isEqualTo(1)
        assertThat(state.messageIsError).isFalse()
    }

    @Test
    fun `writing the same word twice keeps one distinct word and updates the note`() {
        writeAndAwait("apple", "第一版")
        // Same word again: the confirmation text would be identical, so await the note instead.
        viewModel.selectSearchHit(DictionaryHit("apple"))
        viewModel.onNoteChanged("第二版")
        viewModel.write()
        awaitCondition("the updated note to show") {
            viewModel.state.value.written.single().note == "第二版"
        }

        val state = viewModel.state.value
        assertThat(state.sessionCount).isEqualTo(1) // distinct words, not commits
        assertThat(state.written).hasSize(1)
        assertThat(state.written.single().note).isEqualTo("第二版")
        assertThat(countInPack()).isEqualTo(1)
    }

    @Test
    fun `a blank word leaves the counters untouched and flags an error`() {
        viewModel.write()
        awaitCondition("the empty-input message") { viewModel.state.value.message != null }

        val state = viewModel.state.value
        assertThat(state.sessionCount).isEqualTo(0)
        assertThat(state.messageIsError).isTrue()
        assertThat(state.message).isEqualTo("请输入要写入的单词。")
        assertThat(countInPack()).isEqualTo(0)
    }

    // --- the written list follows the gateway write-back (P3) --------------------------------

    @Test
    fun `a dictionary meaning written back later appears without leaving the page`() {
        writeAndAwait("exaggerated")

        // The row is PENDING until the worker runs; the list already has a placeholder.
        val pending = viewModel.state.value.written.single()
        assertThat(pending.lookupState).isEqualTo("PENDING")
        assertThat(pending.meaning).isEmpty()
        assertThat(pending.secondaryText).isEqualTo("释义待补全")

        storeGatewayMeaning("exaggerated", "adj. 夸大的；夸张的")

        awaitCondition("the write-back to reach the list") {
            viewModel.state.value.written.single().meaning == "adj. 夸大的；夸张的"
        }
        val entry = viewModel.state.value.written.single()
        assertThat(entry.lookupState).isEqualTo("OK")
        assertThat(entry.secondaryText).isEqualTo("adj. 夸大的；夸张的")
        assertThat(entry.secondaryIsNote).isFalse()
    }

    @Test
    fun `a note wins over the dictionary meaning`() {
        writeAndAwait("overlooked", "忽视了")
        storeGatewayMeaning("overlooked", "v. 忽略；俯视")

        awaitCondition("the meaning to land") {
            viewModel.state.value.written.single().meaning == "v. 忽略；俯视"
        }
        val entry = viewModel.state.value.written.single()
        assertThat(entry.note).isEqualTo("忽视了")
        assertThat(entry.secondaryText).isEqualTo("忽视了")
        assertThat(entry.secondaryIsNote).isTrue()
    }

    // --- in-card pack creation (P1) ---------------------------------------------------------

    @Test
    fun `creating a pack selects it as the write target`() {
        viewModel.createPack("11.10")

        awaitCondition("the new pack to become the target") {
            viewModel.state.value.targetPackId.isNotEmpty() &&
                viewModel.state.value.targetPackName == "11.10"
        }
        val state = viewModel.state.value
        assertThat(state.message).isEqualTo("已新建词库「11.10」，后续单词将写入这里。")
        assertThat(state.messageIsError).isFalse()

        writeAndAwait("apple")
        val targetPackId = viewModel.state.value.targetPackId
        runBlocking(Dispatchers.IO) {
            assertThat(database.wordDao().byNormalized(targetPackId, "apple")).isNotNull()
        }
    }

    @Test
    fun `a blank pack name is refused and the target stays the lazy write pack`() {
        viewModel.createPack("   ")

        awaitCondition("the refusal") { viewModel.state.value.message != null }
        val state = viewModel.state.value
        assertThat(state.message).isEqualTo("词库名称不能为空。")
        assertThat(state.messageIsError).isTrue()
        assertThat(state.targetPackId).isEmpty()
        assertThat(state.targetPackName).isEqualTo(PlanIds.WRITE_PACK_NAME)
    }

    @Test
    fun `an over-long pack name is refused and the target stays the lazy write pack`() {
        viewModel.createPack("x".repeat(41))

        awaitCondition("the refusal") { viewModel.state.value.message != null }
        val state = viewModel.state.value
        assertThat(state.message).contains("名称过长")
        assertThat(state.messageIsError).isTrue()
        assertThat(state.targetPackId).isEmpty()
    }

    // --- full-screen picker (P2) ------------------------------------------------------------

    @Test
    fun `an empty query never reaches the gateway`() {
        viewModel.openSearch()
        awaitCondition("the picker to open") { viewModel.state.value.searchVisible }
        // Give the debounce window time to pass; a blank query must still not fire a request.
        shadowOf(Looper.getMainLooper()).idleFor(DEBOUNCE_SETTLE_MILLIS, TimeUnit.MILLISECONDS)

        assertThat(dictionarySearch.queries).isEmpty()
        assertThat(viewModel.state.value.searchQuery).isEmpty()
    }

    @Test
    fun `a keystroke flags loading immediately instead of leaving the list blank`() {
        // Suspend forever so the loading state is observable regardless of debounce timing.
        dictionarySearch.handler = { awaitCancellation() }
        viewModel.openSearch()
        awaitCondition("the picker to open") { viewModel.state.value.searchVisible }

        viewModel.onSearchQueryChanged("ent")

        awaitCondition("the inline loading state") { viewModel.state.value.searchLoading }
        assertThat(viewModel.state.value.searchHits).isEmpty()
    }

    @Test
    fun `rapid typing is debounced down to the last query`() {
        dictionarySearch.result = Outcome.Success(
            listOf(DictionaryHit("entire", "ɪnˈtaɪə", "adj. 全部的；整个的")),
        )
        viewModel.openSearch()
        viewModel.onSearchQueryChanged("e")
        viewModel.onSearchQueryChanged("en")
        viewModel.onSearchQueryChanged("ent")

        awaitCondition("the debounced result") { viewModel.state.value.searchSettled }
        assertThat(dictionarySearch.queries).containsExactly("ent")
        assertThat(viewModel.state.value.searchHits.single().word).isEqualTo("entire")
        assertThat(viewModel.state.value.searchHits.single().translation).isEqualTo("adj. 全部的；整个的")
    }

    @Test
    fun `a new query cancels the request still in flight`() {
        val firstStarted = CompletableDeferred<Unit>()
        dictionarySearch.handler = { query ->
            if (query == "en") {
                firstStarted.complete(Unit)
                awaitCancellation()
            } else {
                Outcome.Success(listOf(DictionaryHit("entire")))
            }
        }
        viewModel.openSearch()
        viewModel.onSearchQueryChanged("en")
        awaitCondition("the first request to start") { firstStarted.isCompleted }

        viewModel.onSearchQueryChanged("ent")

        awaitCondition("the second result") { viewModel.state.value.searchSettled }
        assertThat(dictionarySearch.cancelled).contains("en")
        assertThat(viewModel.state.value.searchHits.single().word).isEqualTo("entire")
    }

    @Test
    fun `selecting a candidate fills the word and leaves the picker`() {
        val hit = DictionaryHit("entire", "ɪnˈtaɪə", "adj. 全部的")
        dictionarySearch.result = Outcome.Success(listOf(hit))
        viewModel.openSearch()
        viewModel.onSearchQueryChanged("ent")
        awaitCondition("the candidate list") { viewModel.state.value.searchHits.isNotEmpty() }

        viewModel.selectSearchHit(hit)

        awaitCondition("the form to receive the word") {
            viewModel.state.value.wordInput == "entire" && !viewModel.state.value.searchVisible
        }
        val state = viewModel.state.value
        assertThat(state.wordInput).isEqualTo("entire")
        assertThat(state.searchVisible).isFalse()
        assertThat(state.saving).isFalse()
    }

    @Test
    fun `a gateway failure still lets the typed word be used and written`() {
        dictionarySearch.result = Outcome.Failure(AppError.network("断网"))
        viewModel.openSearch()
        viewModel.onSearchQueryChanged("exaggerated")
        awaitCondition("the error state") { viewModel.state.value.searchError != null }

        viewModel.useTypedWord()
        awaitCondition("the typed word to reach the form") {
            viewModel.state.value.wordInput == "exaggerated" && !viewModel.state.value.searchVisible
        }

        // The manual path is untouched by the failed search: the word still writes normally.
        viewModel.write()
        awaitCondition("the manual word to be written") {
            viewModel.state.value.written.any { it.word == "exaggerated" }
        }
        assertThat(rowFor("exaggerated")).isNotNull()
    }

    @Test
    fun `a 401 is reported as a configuration problem`() {
        dictionarySearch.result = Outcome.Failure(AppError.config("HTTP 401"))
        viewModel.openSearch()
        viewModel.onSearchQueryChanged("ent")

        awaitCondition("the config error") { viewModel.state.value.searchError != null }
        assertThat(viewModel.state.value.searchErrorIsConfig).isTrue()
        assertThat(viewModel.state.value.searchHits).isEmpty()
    }

    @Test
    fun `system back clears the text first and only then leaves the picker`() {
        viewModel.openSearch()
        viewModel.onSearchQueryChanged("ent")
        awaitCondition("the picker to show the query") {
            viewModel.state.value.searchVisible && viewModel.state.value.searchQuery == "ent"
        }

        viewModel.onSearchBack()
        awaitCondition("the text to be cleared") {
            viewModel.state.value.searchVisible && viewModel.state.value.searchQuery.isEmpty()
        }

        viewModel.onSearchBack()
        awaitCondition("the picker to close") { !viewModel.state.value.searchVisible }
    }

    private companion object {
        /** Real-time bound for Room / DataStore round-trips on a busy machine. */
        const val TIMEOUT_MILLIS = 10_000L

        /** Granularity at which the paused main looper is pumped while polling. */
        const val POLL_INTERVAL_MILLIS = 10L

        /** Teardown drain rounds: enough for a DataStore reader's cancellation to unwind. */
        const val DRAIN_ROUNDS = 50

        /** Test-only debounce; production uses [WriteWordViewModel.SEARCH_DEBOUNCE_MILLIS]. */
        const val DEBOUNCE_MILLIS = 20L

        /** Longer than [DEBOUNCE_MILLIS], so "no request" assertions are meaningful. */
        const val DEBOUNCE_SETTLE_MILLIS = 120L
    }
}
