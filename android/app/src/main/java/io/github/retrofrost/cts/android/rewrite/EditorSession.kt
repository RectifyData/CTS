package io.github.retrofrost.cts.android.rewrite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject

/** One immutable project snapshot per editor operation, not per rendered frame. */
internal class EditorSession(initial: CtsProject) {
    var project by mutableStateOf(initial)
        private set
    var revision by mutableLongStateOf(0L)
        private set
    private var undoStack by mutableStateOf<List<CtsProject>>(emptyList())
    private var redoStack by mutableStateOf<List<CtsProject>>(emptyList())
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun apply(next: CtsProject) {
        if (next == project) return
        undoStack = (undoStack + project).takeLast(HISTORY_LIMIT)
        redoStack = emptyList()
        change(next)
    }

    fun undo() {
        val previous = undoStack.lastOrNull() ?: return
        redoStack = (redoStack + project).takeLast(HISTORY_LIMIT)
        undoStack = undoStack.dropLast(1)
        change(previous)
    }

    fun redo() {
        val next = redoStack.lastOrNull() ?: return
        undoStack = (undoStack + project).takeLast(HISTORY_LIMIT)
        redoStack = redoStack.dropLast(1)
        change(next)
    }

    fun updateCard(id: String, edit: (CtsCard) -> CtsCard) = apply(project.updateCard(id, edit))

    fun moveCard(id: String, delta: Int) {
        val from = project.cards.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, project.cards.lastIndex)
        if (from == to) return
        apply(project.copy(cards = project.cards.toMutableList().apply {
            add(to, removeAt(from))
        }))
    }

    private fun change(next: CtsProject) {
        project = next
        revision++
    }

    companion object { private const val HISTORY_LIMIT = 40 }
}
