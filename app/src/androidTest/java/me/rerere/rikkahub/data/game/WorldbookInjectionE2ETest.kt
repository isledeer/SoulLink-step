package me.rerere.rikkahub.data.game

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.isledeer.soullink.game.WorldbookTemplates
import com.isledeer.soullink.game.updateDoc
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.transformers.transformMessages
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.GameSessionEntity
import me.rerere.rikkahub.data.db.entity.WorldBookEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.GameRepository
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 端到端（instrumented）：DB → 编译 → 映射 → 上游真实 transformMessages → system prompt。
 *
 * 这条覆盖 `createContext` 里三行胶水之外的全部链路；唯一没有覆盖的是 ChatRequestFactory 本身
 * （需要 Koin 全量依赖）。断言失败时会打印出的 system prompt 在 CI 日志里可查。
 */
@RunWith(AndroidJUnit4::class)
class WorldbookInjectionE2ETest {

    private lateinit var db: AppDatabase
    private lateinit var repository: GameRepository

    private val rule = "测试规则：玩家的名字必须以「旅人」开头"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = GameRepository(db.gameSessionDao(), db.gameEventDao(), db.worldBookDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun rulesEnterSystemPromptThroughRealTransformer() = runBlocking {
        val bookId = "book-e2e"
        val docJson = updateDoc(
            raw = WorldbookTemplates.blank(template = "blank", title = "测试世界"),
            title = "测试世界",
            premise = "",
            setting = "",
            rules = listOf(rule),
        )
        db.worldBookDao().upsert(
            WorldBookEntity(
                id = bookId,
                title = "测试世界",
                template = "blank",
                docJson = docJson,
                createdAt = 1L,
                updatedAt = 1L,
            )
        )
        db.gameSessionDao().upsert(
            GameSessionEntity(
                id = "session-e2e",
                title = "测试世界",
                worldbookId = bookId,
                initialStateJson = WorldbookTemplates.buildInitialState(
                    com.isledeer.soullink.game.WorldDoc.parse(docJson)
                ),
                createdAt = 1L,
                updatedAt = 1L,
            )
        )

        // 1) 绑定状态：编译 → 映射 → 真实 transformer
        val compiled = repository.loadedCompiledInjections("session-e2e")
        assertTrue("应能编译出注入项", compiled != null && compiled.injections.isNotEmpty())
        val book = buildWorldbookLorebook(compiled!!.worldbookId, compiled.injections)!!

        val boundAssistant = Assistant(id = Uuid.random(), name = "A").attachWorldbook(book)
        val history = listOf(UIMessage.system("基础人设"), UIMessage.user("你好"))

        val boundMessages = transformMessages(
            messages = history,
            assistant = boundAssistant,
            modeInjections = emptyList(),
            lorebooks = listOf(book),
        )
        val boundSystem = boundMessages.first { it.role == MessageRole.SYSTEM }.toText()
        println("=== system prompt (bound) ===\n$boundSystem\n=== end bound ===")
        Log.i(TAG, "bound system prompt: $boundSystem")
        assertTrue("绑定后 system prompt 应包含世界书规则", boundSystem.contains(rule))

        // 2) 解绑状态：不传 book，规则必须消失
        val unboundAssistant = Assistant(id = Uuid.random(), name = "A").attachWorldbook(null)
        val unboundMessages = transformMessages(
            messages = history,
            assistant = unboundAssistant,
            modeInjections = emptyList(),
            lorebooks = emptyList(),
        )
        val unboundSystem = unboundMessages.first { it.role == MessageRole.SYSTEM }.toText()
        println("=== system prompt (unbound) ===\n$unboundSystem\n=== end unbound ===")
        Log.i(TAG, "unbound system prompt: $unboundSystem")
        assertFalse("解绑后 system prompt 不应再包含世界书规则", unboundSystem.contains(rule))
    }

    private companion object {
        const val TAG = "SoulLinkE2E"
    }
}
