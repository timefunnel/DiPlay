package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayPlaybackStatusTest {
    private fun update(block: com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder.() -> Unit) =
        Iap2Messages.buildRaw(CarPlayPlaybackStatus.NOW_PLAYING_UPDATE, block)

    @Test
    fun reportsOnlyChangesOfThePlaybackStatus() {
        val status = CarPlayPlaybackStatus()

        assertEquals(true, status.accept(update { group(1) { u8(0, 1) } }))
        assertNull(status.accept(update { group(1) { u8(0, 1) } }))
        // Elapsed time alone, as the iPhone sends it every half second: no status, no change.
        assertNull(status.accept(update { group(1) { u32(1, 120_706L) } }))
        assertEquals(false, status.accept(update { group(1) { u8(0, 2) } }))
    }

    @Test
    fun otherMessagesAndSessionEnd() {
        val status = CarPlayPlaybackStatus()
        assertNull(status.accept(Iap2Messages.buildRaw(0x5201) { group(1) { u8(0, 1) } }))
        assertNull(status.clear())

        status.accept(update { group(1) { u8(0, 1) } })
        assertEquals(false, status.clear())
    }

    @Test
    fun retainsIncrementalNowPlayingMetadata() {
        val status = CarPlayPlaybackStatus()

        assertEquals(
            CarPlayNowPlaying(
                title = "Dreams",
                album = "Rumours",
                artist = "Fleetwood Mac",
                durationMillis = 257_000,
            ),
            status.acceptUpdate(update {
                group(0) {
                    string(1, "Dreams")
                    u32(4, 257_000)
                    string(6, "Rumours")
                    string(12, "Fleetwood Mac")
                }
            }),
        )
        assertEquals(
            CarPlayNowPlaying(
                title = "Dreams",
                album = "Rumours",
                artist = "Fleetwood Mac",
                sourceApp = "Music",
                durationMillis = 257_000,
                elapsedMillis = 12_500,
                playing = true,
                positionRevision = 1,
            ),
            status.acceptUpdate(update {
                group(1) {
                    u8(0, 1)
                    u32(1, 12_500)
                    string(7, "Music")
                }
            }),
        )
    }

    @Test
    fun emptyStringsClearFieldsAndArtworkIdIsRetained() {
        val status = CarPlayPlaybackStatus()
        status.acceptUpdate(update { group(0) { string(1, "Song") } })

        val updated = status.acceptUpdate(update {
            group(0) {
                string(1, "")
                u8(26, 0x81)
            }
        })

        assertNull(updated?.title)
        assertEquals(0x81, updated?.artworkTransferId)
        assertEquals(CarPlayNowPlaying(), status.clearAll())
    }

    @Test fun repeatedElapsedValueStillRefreshesThePhonesPositionAnchor() {
        val status = CarPlayPlaybackStatus()
        val packet = update { group(1) { u32(1, 1_000) } }
        assertEquals(1L, status.acceptUpdate(packet)?.positionRevision)
        assertEquals(2L, status.acceptUpdate(packet)?.positionRevision)
        assertEquals(2L, status.acceptUpdate(update { group(0) { string(1, "Song") } })?.positionRevision)
    }

    @Test fun subscribesToTheMediaItemIdentifierAndKeepsItsFirstTitleAcrossLyrics() {
        val requested = Iap2BodyReader.of(Iap2ControlMessages.startNowPlayingUpdates()).group(0)
        org.junit.Assert.assertTrue(requested.has(0))
        val status = CarPlayPlaybackStatus()
        val first = status.acceptUpdate(update { group(0) {
            bytes(0, byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 0, 0, 0))
            string(1, "Song")
        } })!!
        assertEquals(Long.MIN_VALUE, first.persistentId)
        assertEquals("Song", first.trackTitle)
        val lyric = status.acceptUpdate(update { group(0) { string(1, "First lyric") } })!!
        assertEquals("First lyric", lyric.title)
        assertEquals("Song", lyric.trackTitle)
        assertEquals(Long.MIN_VALUE, lyric.persistentId)
        assertEquals("Song", status.acceptUpdate(update { group(0) { string(1, "Second lyric") } })?.trackTitle)
    }

    @Test fun aNewMediaItemWaitsForItsOwnTitleAndSessionEndDiscardsTheOldOne() {
        val status = CarPlayPlaybackStatus()
        status.acceptUpdate(update { group(0) { u64(0, 1); string(1, "First song") } })
        status.acceptUpdate(update { group(0) { string(1, "A lyric") } })
        val changed = status.acceptUpdate(update { group(0) { u64(0, 2) } })!!
        assertNull(changed.trackTitle)
        assertEquals("Second song", status.acceptUpdate(update { group(0) { string(1, "Second song") } })?.trackTitle)
        status.clearAll()
        assertEquals("Third song", status.acceptUpdate(update { group(0) { u64(0, 2); string(1, "Third song") } })?.trackTitle)
    }

    @Test fun anUnspecifiedIdentifierDoesNotBindAllSongsToOneTitle() {
        val status = CarPlayPlaybackStatus()
        val info = status.acceptUpdate(update { group(0) { u64(0, 0); string(1, "Song") } })!!
        assertNull(info.persistentId)
        assertNull(info.trackTitle)
        assertEquals("Song", info.title)
    }
}
