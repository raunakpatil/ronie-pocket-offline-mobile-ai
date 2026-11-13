package com.raunakpatil.roniepocket.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopGuardTest {
    @Test fun normalReplyIsNotALoop() {
        val g = LoopGuard()
        assertFalse(g.isLooping("Boil water, add tea leaves and ginger, then milk and sugar. Simmer five minutes and strain."))
    }

    @Test fun repeatedTailIsCaughtAndTrimmed() {
        val g = LoopGuard()
        val text = "Here is the answer. " + "I think the answer is 42. ".repeat(4)
        assertTrue(g.isLooping(text))
        assertEquals("Here is the answer. I think the answer is 42.", g.trimmed(text))
    }

    @Test fun repeatedLinesAreCaught() {
        val g = LoopGuard()
        val text = "Steps:\nCheck the router lights now.\nWait.\nCheck the router lights now.\nOk.\nCheck the router lights now.\n"
        assertTrue(g.isLooping(text))
    }

    @Test fun shortListsWithSimilarItemsAreFine() {
        val g = LoopGuard()
        assertFalse(g.isLooping("- Water bottle\n- Water shoes\n- Sunscreen\n- Sunglasses\n- Snacks for the road"))
    }
}
