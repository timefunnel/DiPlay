package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlayMediaPositionTest {
    @Test fun pauseAndResumeWithoutElapsedFieldsKeepTheDisplayedPosition() {
        val position = CarPlayMediaPosition()
        val playing = CarPlayNowPlaying(elapsedMillis = 1_000, playing = true, positionRevision = 1)
        position.update(playing, 10_000)
        position.update(playing.copy(playing = false), 12_000)
        assertEquals(3_000L, position.position)
        assertEquals(12_000L, position.updatedAt)
        position.update(playing, 30_000)
        assertEquals(3_000L, position.position)
        assertEquals(30_000L, position.updatedAt)
    }

    @Test fun repeatedPhonePositionAndBackwardSeekResetTheAnchor() {
        val position = CarPlayMediaPosition()
        val playing = CarPlayNowPlaying(elapsedMillis = 1_000, playing = true, positionRevision = 1)
        position.update(playing, 10_000)
        position.update(playing.copy(positionRevision = 2), 12_000)
        assertEquals(1_000L, position.position)
        assertEquals(12_000L, position.updatedAt)
        position.update(playing.copy(elapsedMillis = 100, positionRevision = 3), 13_000)
        assertEquals(100L, position.position)
        assertEquals(13_000L, position.updatedAt)
    }

    @Test fun metadataDoesNotMoveThePositionAnchor() {
        val position = CarPlayMediaPosition()
        val playing = CarPlayNowPlaying(title = "Song", elapsedMillis = 1_000, playing = true, positionRevision = 1)
        position.update(playing, 10_000)
        position.update(playing.copy(artist = "Artist"), 12_000)
        assertEquals(10_000L, position.updatedAt)
        assertEquals(3_000L, position.currentPosition(12_000))
    }
}
