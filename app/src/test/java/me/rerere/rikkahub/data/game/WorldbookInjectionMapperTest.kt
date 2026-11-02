package me.rerere.rikkahub.data.game

import com.isledeer.soullink.game.CompiledInjection
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.InjectionPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldbookInjectionMapperTest {

    @Test
    fun `compiled injection maps every field`() {
        val compiled = CompiledInjection(
            name = "e1",
            content = "剑宗山门",
            keywords = listOf("剑", "御剑"),
            alwaysOn = true,
            priority = 7,
        )

        val injection = compiled.toRegexInjection("book-1")

        assertEquals("e1", injection.name)
        assertEquals("剑宗山门", injection.content)
        assertEquals(listOf("剑", "御剑"), injection.keywords)
        assertEquals(7, injection.priority)
        assertTrue(injection.enabled)
        assertTrue(injection.constantActive)
        assertEquals(InjectionPosition.AFTER_SYSTEM_PROMPT, injection.position)
        assertEquals(MessageRole.USER, injection.role)
        assertEquals(4, injection.scanDepth)
        assertEquals(4, injection.injectDepth)
    }

    @Test
    fun `id is stable for same book and name`() {
        val a = stableInjectionId("book-1", "e1")
        val b = stableInjectionId("book-1", "e1")
        val c = stableInjectionId("book-1", "e2")
        val d = stableInjectionId("book-2", "e1")

        assertEquals(a, b)
        assertNotEquals(a, c)
        assertNotEquals(a, d)
    }
}
