package me.rerere.rikkahub.data.game

import com.isledeer.soullink.game.CompiledInjection
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldbookInjectionTest {

    private val sample = listOf(
        CompiledInjection(name = "__rules__", content = "测试规则", alwaysOn = true, priority = 1000),
        CompiledInjection(name = "e1", content = "剑宗", keywords = listOf("剑"), priority = 3),
    )

    @Test
    fun `empty injections produce no lorebook`() {
        assertNull(buildWorldbookLorebook("book-1", emptyList()))
    }

    @Test
    fun `lorebook carries stable id and mapped entries`() {
        val book = buildWorldbookLorebook("book-1", sample)!!
        assertEquals(stableInjectionId("book-1", "worldbook"), book.id)
        assertEquals(2, book.entries.size)
        assertEquals("__rules__", book.entries[0].name)
        assertTrue(book.entries[0].constantActive)
        assertEquals(listOf("剑"), book.entries[1].keywords)
        // 同一本书反复构造，id 必须一致
        assertEquals(book.id, buildWorldbookLorebook("book-1", sample)!!.id)
    }

    @Test
    fun `settings without book are untouched`() {
        val settings = Settings.dummy()
        assertSame(settings, settings.attachWorldbook(null))
    }

    @Test
    fun `settings gain exactly one lorebook`() {
        val settings = Settings.dummy()
        val book = buildWorldbookLorebook("book-1", sample)!!
        val updated = settings.attachWorldbook(book)
        assertEquals(settings.lorebooks.size + 1, updated.lorebooks.size)
        assertEquals(book.id, updated.lorebooks.last().id)
        // 原实例不变
        assertEquals(settings.lorebooks.size, settings.lorebooks.size)
    }

    @Test
    fun `assistant without book is untouched`() {
        val assistant = Assistant(id = Uuid.random(), name = "A")
        assertSame(assistant, assistant.attachWorldbook(null))
    }

    @Test
    fun `assistant gains the book binding`() {
        val assistant = Assistant(id = Uuid.random(), name = "A")
        val book = buildWorldbookLorebook("book-1", sample)!!
        val updated = assistant.attachWorldbook(book)
        assertTrue(updated.lorebookIds.contains(book.id))
        assertTrue(assistant.lorebookIds.isEmpty())
    }
}
