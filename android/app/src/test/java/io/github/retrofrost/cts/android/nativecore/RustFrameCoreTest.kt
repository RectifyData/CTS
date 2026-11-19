package io.github.retrofrost.cts.android.nativecore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RustFrameCoreTest {
    @Test fun sequencePauseRepeatAndEnd() {
        assertEquals(-1, RustFrameCore.sequenceIndex(-1, 3, 2, 2))
        assertEquals(0, RustFrameCore.sequenceIndex(0, 3, 2, 2))
        assertEquals(2, RustFrameCore.sequenceIndex(4, 3, 2, 2))
        assertEquals(0, RustFrameCore.sequenceIndex(5, 3, 2, 2))
        assertEquals(2, RustFrameCore.sequenceIndex(9, 3, 2, 2))
        assertEquals(-1, RustFrameCore.sequenceIndex(10, 3, 2, 2))
        assertEquals(2, RustFrameCore.sequenceIndex(Int.MAX_VALUE, 3, 2, 0))
    }

    @Test fun easingMatchesReferenceAcrossFrameGrid() {
        for (style in 0..6) {
            var previous = 0f
            for (i in 0..1000) {
                val t = i / 1000f
                val got = RustFrameCore.ease(t, style)
                assertEquals(RustFrameCore.referenceEase(t, style), got, 0.000001f)
                assertTrue(got >= previous && got <= 1f)
                previous = got
            }
        }
    }

    @Test fun materialMatchesReferenceAcrossFrameGrid() {
        for (i in 0..1000) {
            val t = i / 1000f
            assertEquals(
                RustFrameCore.referenceMaterial(t, 0.4f, 0f, 0.2f, 1f),
                RustFrameCore.material(t, 0.4f, 0f, 0.2f, 1f), 0.000001f,
            )
        }
    }
}
