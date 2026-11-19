package io.github.retrofrost.cts.android.rewrite

import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import org.junit.Assert.*
import org.junit.Test

class EditorSessionTest {
    @Test fun moveUndoRedoAndDivergentEdit() {
        val a = CtsCard(title = "A")
        val b = CtsCard(title = "B")
        val session = EditorSession(CtsProject(cards = listOf(a, b)))
        session.moveCard(b.id, -1)
        assertEquals(listOf(b.id, a.id), session.project.cards.map { it.id })
        session.undo()
        assertEquals(listOf(a.id, b.id), session.project.cards.map { it.id })
        assertTrue(session.canRedo)
        session.redo()
        assertEquals(b.id, session.project.cards.first().id)
        session.undo()
        session.updateCard(a.id) { it.copy(title = "New") }
        assertFalse(session.canRedo)
        assertEquals("New", session.project.cards.first().title)
        assertEquals(5L, session.revision)
    }
    @Test fun noOpMoveDoesNotCreateHistory() {
        val a = CtsCard()
        val session = EditorSession(CtsProject(cards = listOf(a)))
        session.moveCard(a.id, -1)
        assertFalse(session.canUndo)
        assertEquals(0L, session.revision)
    }
}
