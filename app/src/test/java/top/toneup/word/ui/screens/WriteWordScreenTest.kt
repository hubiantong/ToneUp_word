package top.toneup.word.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.toneup.word.AppContainer
import top.toneup.word.core.result.Outcome
import top.toneup.word.data.local.ToneUpWordDatabase
import top.toneup.word.data.repository.DictionaryHit
import top.toneup.word.data.repository.DictionarySearchRepository
import top.toneup.word.data.settings.PlanIds
import top.toneup.word.ui.theme.ToneUpWordTheme

/**
 * The「写词库」screen as the learner uses it: the word field opens the full-screen picker, a
 * candidate only fills the form (nothing is written yet), and the in-card「新建」dialog refuses
 * blank / over-long names before any database call.
 *
 * The gateway is replaced through [AppContainer]'s search seam, so no test touches the network.
 * The screen's state flows keep their Room subscriptions for `WhileSubscribed(5_000)`, hence the
 * shared database and the [AfterClass] close (same pattern as `ComposeFlowTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class WriteWordScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var database: ToneUpWordDatabase
    private lateinit var container: AppContainer

    private val search = FakeDictionarySearch()

    private class FakeDictionarySearch : DictionarySearchRepository {
        var result: Outcome<List<DictionaryHit>> = Outcome.Success(emptyList())

        override suspend fun search(query: String): Outcome<List<DictionaryHit>> = result
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = sharedDatabase(context)
        database.clearAllTables()
        container = AppContainer(
            context = context,
            databaseOverride = database,
            dictionarySearchOverride = search,
        )
        // DataStore is shared between tests in the same process; start from a known state.
        runBlocking(Dispatchers.IO) { container.settingsRepository.resetToDefaults() }
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun showScreen() {
        composeRule.setContent {
            ToneUpWordTheme { WriteWordScreen(container = container) }
        }
        awaitTag("write_word_field")
    }

    private fun awaitCondition(condition: () -> Boolean) {
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            composeRule.waitForIdle()
            condition()
        }
    }

    private fun awaitTag(tag: String) = awaitCondition {
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    // --- picker ----------------------------------------------------------------------------

    @Test
    fun `the word field opens the full-screen picker with the guide state`() {
        showScreen()

        composeRule.onNodeWithTag("write_word_field").performClick()

        awaitTag("write_search_field")
        // The picker takes focus on its own, so the learner can type immediately.
        composeRule.onNodeWithTag("write_search_field").assertIsFocused()
        composeRule.onNodeWithTag("write_search_guide").assertIsDisplayed()
        // Nothing typed yet: the manual fallback must not offer an empty word.
        composeRule.onNodeWithTag("write_search_manual").assertDoesNotExist()
    }

    @Test
    fun `a candidate fills the word field and closes the picker without writing`() {
        search.result = Outcome.Success(
            listOf(DictionaryHit("entire", "ɪnˈtaɪə", "adj. 全部的")),
        )
        showScreen()
        composeRule.onNodeWithTag("write_word_field").performClick()
        awaitTag("write_search_field")

        composeRule.onNodeWithTag("write_search_field").performTextInput("ent")

        awaitTag("write_search_hit_entire")
        composeRule.onNodeWithTag("write_search_hit_entire").performClick()

        awaitCondition {
            composeRule.onAllNodesWithTag("write_search_field").fetchSemanticsNodes().isEmpty()
        }
        // The form now carries the chosen word…
        composeRule.onNodeWithText("entire").assertExists()
        // …and selection alone never writes a row.
        runBlocking(Dispatchers.IO) {
            assertThat(database.wordDao().countInPack(PlanIds.writePackIdFor(PlanIds.DEFAULT_PLAN)))
                .isEqualTo(0)
        }
    }

    // --- in-card pack creation -------------------------------------------------------------

    @Test
    fun `the new pack dialog blocks blank and over-long names`() {
        showScreen()

        composeRule.onNodeWithTag("write_new_pack").performClick()
        awaitTag("write_new_pack_name")

        // Blank: confirm disabled, inline explanation shown.
        composeRule.onNodeWithText("确定").assertIsNotEnabled()
        composeRule.onNodeWithText("名称不能为空").assertExists()

        // 41 characters: still refused, with the length message.
        composeRule.onNodeWithTag("write_new_pack_name").performTextInput("x".repeat(41))
        composeRule.onNodeWithText("名称过长，最多 40 个字符").assertExists()
        composeRule.onNodeWithText("确定").assertIsNotEnabled()

        // Clearing the dialog leaves no database call behind (no pack was created).
        composeRule.onNodeWithText("取消").performClick()
        awaitCondition {
            composeRule.onAllNodesWithTag("write_new_pack_name").fetchSemanticsNodes().isEmpty()
        }
        runBlocking(Dispatchers.IO) {
            assertThat(database.wordDao().importedPackCount()).isEqualTo(0)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L

        private var sharedDb: ToneUpWordDatabase? = null

        /**
         * One in-memory database for the whole class: a screen's state flow keeps its Room
         * subscription for `WhileSubscribed(5_000)` after the composition is gone, so closing
         * the database between tests would make that subscription query a closed pool.
         */
        fun sharedDatabase(context: android.content.Context): ToneUpWordDatabase =
            sharedDb ?: Room.inMemoryDatabaseBuilder(context, ToneUpWordDatabase::class.java)
                .allowMainThreadQueries()
                .build()
                .also { sharedDb = it }

        @JvmStatic
        @AfterClass
        fun closeSharedDatabase() {
            sharedDb?.close()
            sharedDb = null
        }
    }
}
