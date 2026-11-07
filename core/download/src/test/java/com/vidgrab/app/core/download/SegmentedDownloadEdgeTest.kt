package com.vidgrab.app.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Edge cases of the resume bookkeeping that the main planning test doesn't cover. */
class SegmentedDownloadEdgeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun lastChunkCountsOnlyItsRealBytes() {
        val part = tmp.newFile("small.part")
        val state = ChunkState.load(part, 10, chunk = 4)
        state.markDone(2)
        assertEquals(2L, state.doneBytes())
        assertFalse(state.isComplete())
    }

    @Test
    fun deleteRemovesThePersistedState() {
        val part = tmp.newFile("v.part")
        val state = ChunkState.load(part, 10, chunk = 4)
        state.markDone(0)
        assertTrue(ChunkState.stateFile(part).exists())
        state.delete()
        assertFalse(ChunkState.stateFile(part).exists())
    }

    @Test
    fun rangesTrimTheFinalChunk() {
        assertEquals(0L..3L, SegmentPlan.range(0, 10, chunk = 4))
        assertEquals(4L..7L, SegmentPlan.range(1, 10, chunk = 4))
        assertEquals(8L..9L, SegmentPlan.range(2, 10, chunk = 4))
    }
}
