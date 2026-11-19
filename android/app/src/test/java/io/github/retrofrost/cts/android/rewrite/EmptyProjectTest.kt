package io.github.retrofrost.cts.android.rewrite

import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.persistence.ProjectJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyProjectTest {
    @Test fun cleanProjectRemainsEmptyAfterAutosaveRoundTrip() {
        val before = CtsProject(name = "Fresh project", cards = emptyList())
        val restored = ProjectJson.decode(ProjectJson.encode(before))
        assertTrue(restored.cards.isEmpty())
        assertEquals("Fresh project", restored.name)
    }
}
