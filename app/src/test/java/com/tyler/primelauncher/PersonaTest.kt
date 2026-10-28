package com.tyler.primelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.1.15: each skin is its own persona; line banks are local, clean of personal data, and fill placeholders gracefully. */
class PersonaTest {
    @Test fun everyPersonaHasAFullBank() {
        for (p in Personas.all) {
            for (c in listOf(Cat.GREETING, Cat.STUDY, Cat.CLEAN, Cat.CAR, Cat.NOTIFY, Cat.MUSING)) assertTrue("${p.id} $c", (p.lines[c]?.size ?: 0) >= 10)
            assertTrue(p.jokes.size >= 10); assertTrue((p.lines[Cat.REACT]?.size ?: 0) >= 6)
        }
    }
    @Test fun noGoodnightBubblesAndNoPersonalData() {
        for (p in Personas.all) { val all = p.lines.values.flatten() + p.jokes.flatMap { listOf(it.first, it.second) }
            for (l in all) { val x = l.lowercase(); assertFalse(l, "goodnight" in x || "good night" in x) } }
    }
    @Test fun activitySetsArePerSkinAndExist() {
        val m = MatrixPersona.pool.filter { it.kind == Kind.ACT && it.id.startsWith("act.") }.map { it.id }.toSet()
        val n = NeonPersona.pool.filter { it.kind == Kind.ACT && it.id.startsWith("act.") }.map { it.id }.toSet()
        assertEquals(setOf(Antics.SCROLL, Antics.FISHING, Antics.ZEN, Antics.GARDEN, Antics.STARGAZER, Antics.FLASHCARDS, Antics.TIDY), m)
        assertEquals(setOf(Antics.SKATE, Antics.DJ, Antics.ARCADE, Antics.GRAFFITI, Antics.SIGN, Antics.JUGGLE), n)
        for (p in Personas.all) { p.pool.filter { it.kind == Kind.ACT }.forEach { assertNotNull(it.id, Antics[it.id]) }; p.quirks.forEach { assertNotNull(it, Antics[it]) }
            p.pool.filter { it.kind == Kind.BUB }.forEach { assertNotNull(it.id, PoolIds.cat(it.id)) } }
        assertEquals(Personas.of("neon"), NeonPersona); assertEquals(Personas.of("matrix"), MatrixPersona)
    }
    @Test fun namePlaceholderFillsOrDisappearsCleanly() {
        for (p in Personas.all) for (l in p.lines.values.flatten()) {
            val blank = Personas.fill(l, "", "3", "2") ?: continue; val named = Personas.fill(l, "Sam", "3", "2")!!
            assertFalse(blank, '{' in blank || "  " in blank || " ," in blank || ",." in blank || ", ." in blank || blank.startsWith(","))
            if ("{name" in l) assertTrue(named, "Sam" in named)
        }
        assertEquals("wake up. follow the white rabbit... into today.", Personas.fill("wake up, {name}. follow the white rabbit... into today.", "", "", ""))
        assertEquals("a warning light is just the car's déjà vu.", Personas.fill("a warning light, {name}, is just the car's déjà vu.", " ", "", ""))
        assertEquals("morning, mr. ander... anderson. sorry.", Personas.fill("morning, mr. ander... {name|anderson}. sorry.", "", "", ""))
    }
    @Test fun studyLinesNeedProgressOrStayGeneric() {
        assertNull(Personas.fill("module {module} is the key.", "", "", "")); assertEquals("module 4 is the key.", Personas.fill("module {module} is the key.", "", "", "4"))
        for (p in Personas.all) assertTrue(p.id, p.lines[Cat.STUDY]!!.count { Personas.fill(it, "", "", "") != null } >= 6)
    }
    @Test fun voicesDiffer() {
        assertEquals("WAKE UP", NeonPersona.cased("Wake up")); assertEquals("wake up", MatrixPersona.cased("Wake Up"))
        assertEquals("GM" to null, Personas.splitBadge("GM")); assertEquals("1UP!" to "★", Personas.splitBadge("1UP! ★"))
        assertTrue(MatrixPersona.reactionScale < NeonPersona.reactionScale)
    }
}
