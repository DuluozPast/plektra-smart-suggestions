package rocks.pkb.plektra.core.suggestions

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentWordTrackerTest {

    @Test
    fun overlongWordStaysSuppressedUntilOverflowHasBeenDeleted() {
        val updates = mutableListOf<String>()
        val tracker = CurrentWordTracker(updates::add, { updates.add("") }, maxLength = 4)
        tracker.onCharacterCommitted("abcdefg")
        assertEquals("", tracker.currentWord)
        assertEquals(listOf("a", "ab", "abc", "abcd", ""), updates)
        tracker.onBackspace()
        tracker.onBackspace()
        assertEquals("", tracker.currentWord)
        assertEquals(5, updates.size)
        tracker.onBackspace()
        assertEquals("abcd", tracker.currentWord)
        tracker.onBackspace()
        assertEquals("abc", tracker.currentWord)
    }

    @Test
    fun restoredOverlongWordDoesNotSuggestOrLearnItsSuffix() {
        val updates = mutableListOf<String>()
        val tracker = CurrentWordTracker(updates::add, {}, maxLength = 4)
        tracker.setWord("abcdef")
        assertEquals("", tracker.currentWord)
        tracker.onBackspace()
        assertEquals("", tracker.currentWord)
        tracker.onBackspace()
        assertEquals("abcd", tracker.currentWord)
        tracker.onCharacterCommitted("extra ")
        tracker.onCharacterCommitted("hi")
        assertEquals("hi", tracker.currentWord)
    }


    @Test
    fun testOnCharacterCommitted_Basic() {
        var wordChanged = ""
        val tracker = CurrentWordTracker(
            onWordChanged = { wordChanged = it },
            onWordReset = { wordChanged = "" }
        )

        tracker.onCharacterCommitted("H")
        assertEquals("H", wordChanged)
        tracker.onCharacterCommitted("i")
        assertEquals("Hi", wordChanged)
    }

    @Test
    fun testOnCharacterCommitted_Boundary() {
        var wordChanged = ""
        var resetCalled = false
        val tracker = CurrentWordTracker(
            onWordChanged = { wordChanged = it },
            onWordReset = { resetCalled = true; wordChanged = "" }
        )

        tracker.onCharacterCommitted("Hello")
        assertEquals("Hello", wordChanged)
        
        // Satzzeichen sollte den Tracker resetten
        tracker.onCharacterCommitted("!")
        assertTrue(resetCalled)
        assertEquals("", wordChanged)
    }

    @Test
    fun testOnBackspace() {
        var wordChanged = ""
        val tracker = CurrentWordTracker(
            onWordChanged = { wordChanged = it },
            onWordReset = { wordChanged = "" }
        )

        tracker.onCharacterCommitted("Tests")
        assertEquals("Tests", tracker.currentWord)
        
        tracker.onBackspace()
        assertEquals("Test", wordChanged)
        
        tracker.onBackspace()
        tracker.onBackspace()
        tracker.onBackspace()
        // Word was "Tests" (length 5)
        // 1st backspace -> "Test"
        // 2nd backspace -> "Tes"
        // 3rd backspace -> "Te"
        // 4th backspace -> "T"
        assertEquals("T", tracker.currentWord)
        
        tracker.onBackspace()
        assertEquals("", tracker.currentWord)
    }

    @Test
    fun testMultiTap_BackspacePattern() {
        var wordChanged = ""
        val tracker = CurrentWordTracker(
            onWordChanged = { wordChanged = it },
            onWordReset = { wordChanged = "" }
        )

        // Multi-tap Simulation: 'a' -> backspace -> 'b'
        tracker.onCharacterCommitted("a")
        assertEquals("a", wordChanged)
        
        tracker.onCharacterCommitted("\u0008b") // \b ist Backspace
        assertEquals("b", wordChanged)
    }

    @Test
    fun testApostropheHandling() {
        var wordChanged = ""
        val tracker = CurrentWordTracker(
            onWordChanged = { wordChanged = it },
            onWordReset = { wordChanged = "" }
        )

        // Apostroph nach einem Buchstaben gehört zum Wort
        tracker.onCharacterCommitted("l")
        tracker.onCharacterCommitted("'")
        assertEquals("l'", wordChanged)
        
        tracker.onCharacterCommitted("a")
        assertEquals("l'a", wordChanged)
    }

    @Test
    fun testMaxLength() {
        var wordChanged = ""
        val tracker = CurrentWordTracker(
            onWordChanged = { wordChanged = it },
            onWordReset = { wordChanged = "" },
            maxLength = 5
        )

        tracker.onCharacterCommitted("1234567")
        assertEquals("", tracker.currentWord)
        assertEquals("", wordChanged)
        tracker.onBackspace()
        tracker.onBackspace()
        assertEquals("12345", tracker.currentWord)
    }

    // Hilfsfunktion da assertTrue in JUnit benötigt wird
    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}

