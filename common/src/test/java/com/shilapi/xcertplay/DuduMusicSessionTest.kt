package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.time.Duration
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.MockedConstruction
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class DuduMusicSessionTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val phone = mock(CarPlayController::class.java)
    private var receive: ((CarPlayNowPlaying) -> Unit)? = null
    private var retained = CarPlayNowPlaying()
    private lateinit var sessions: MockedConstruction<MediaSession>
    private val session: MediaSession? get() = CarPlayMediaKeys.javaClass.getDeclaredField("session")
        .apply { isAccessible = true }.get(CarPlayMediaKeys) as MediaSession?

    @Before fun setup() {
        // Robolectric does not implement the system media-session Binder bridge. Check the real
        // publication boundary and registered callback; cross-app discovery remains a device check.
        sessions = mockConstruction(MediaSession::class.java)
        AirPlayPersistence.saveDuduIntegration(context, true)
        `when`(phone.activeAirPlaySessionToken()).thenReturn(Any())
        doAnswer { retained }.`when`(phone).nowPlayingSnapshot()
        doAnswer { receive = it.getArgument(0); null }.`when`(phone).nowPlayingListener = any()
        CarPlayMediaKeys.attach(context, phone)
        CarPlayMediaKeys.ensureDuduSession(context)
    }
    @After fun cleanup() {
        CarPlayMediaKeys.detach(phone)
        AirPlayPersistence.saveDuduIntegration(context, false)
        shadowOf(Looper.getMainLooper()).idle()
        sessions.close()
    }

    @Test fun aPausedPhoneIsDiscoverableBeforeAudioAndCommandsReturnThroughTheSameSession() {
        val current = session!!
        assertSame(current, sessions.constructed().single())
        verify(current).setActive(true)
        publish(CarPlayNowPlaying(title = "Song", artist = "Artist", durationMillis = 180_000,
            elapsedMillis = 12_000, positionRevision = 1))
        shadowOf(Looper.getMainLooper()).idle()
        val metadataCaptor = ArgumentCaptor.forClass(MediaMetadata::class.java)
        val playbackCaptor = ArgumentCaptor.forClass(PlaybackState::class.java)
        verify(current, atLeastOnce()).setMetadata(metadataCaptor.capture())
        verify(current, atLeastOnce()).setPlaybackState(playbackCaptor.capture())
        val metadata = metadataCaptor.allValues.last()
        val playback = playbackCaptor.allValues.last()
        assertEquals("Song", metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertEquals("Artist", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
        assertEquals(180_000, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
        assertEquals(PlaybackState.STATE_PAUSED, playback.state)
        assertEquals(12_000, playback.position)
        CarPlayMediaKeys.onMediaAudioChanged(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(current, session)
        publish(CarPlayNowPlaying(title = "Song", artist = "Artist", durationMillis = 180_000,
            elapsedMillis = 12_000, positionRevision = 1, playing = true))
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        CarPlayMediaKeys.onMediaAudioChanged(false)
        shadowOf(Looper.getMainLooper()).idle()
        verify(current, atLeastOnce()).setPlaybackState(playbackCaptor.capture())
        assertEquals(14_000L, playbackCaptor.allValues.last().position)
        assertEquals(SystemClock.elapsedRealtime(), playbackCaptor.allValues.last().lastPositionUpdateTime)
        val callbacks = ArgumentCaptor.forClass(MediaSession.Callback::class.java)
        verify(current).setCallback(callbacks.capture(), any(Handler::class.java))
        callbacks.value.onPlay()
        callbacks.value.onPause()
        callbacks.value.onSkipToNext()
        callbacks.value.onSkipToPrevious()
        shadowOf(Looper.getMainLooper()).idle()
        verify(phone).sendMediaButton(CarPlayMediaButton.PLAY)
        verify(phone).sendMediaButton(CarPlayMediaButton.PAUSE)
        verify(phone).sendMediaButton(CarPlayMediaButton.NEXT)
        verify(phone).sendMediaButton(CarPlayMediaButton.PREVIOUS)
        CarPlayMediaKeys.detach(phone)
        assertNull(session)
        verify(current).setActive(false)
        verify(current).release()
    }

    @Test fun lyricUpdatesKeepTheTitleAndCoverWithoutRepublishingMetadata() {
        val current = session!!
        val cover = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        ReflectionHelpers.setField(CarPlayMediaKeys, "artwork", cover)
        val song = CarPlayNowPlaying(title = "Song", artist = "Artist", album = "Album",
            durationMillis = 180_000, artworkTransferId = 7, elapsedMillis = 20_000, playing = true)
        publish(song)
        idle()
        val metadata = ArgumentCaptor.forClass(MediaMetadata::class.java)
        verify(current, atLeastOnce()).setMetadata(metadata.capture())
        assertEquals("Song", metadata.value.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertSame(cover, metadata.value.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        clearInvocations(current)
        repeat(20) {
            publish(song.copy(title = "Lyric $it", elapsedMillis = 20_500L + it * 500, positionRevision = it + 1L))
            idle()
        }
        verify(current, never()).setMetadata(any())
        verify(current, times(20)).setPlaybackState(any())
        verify(current, never()).setActive(anyBoolean())
    }

    @Test fun mediaItemChangesUpdateTheTitleEvenForTheSameAlbumArtistDurationAndArtworkReference() {
        val song = CarPlayNowPlaying(title = "First song", trackTitle = "First song", persistentId = 1,
            artist = "Artist", album = "Album", durationMillis = 180_000, artworkTransferId = 7)
        publish(song); idle()
        clearInvocations(session!!)
        publish(song.copy(title = "A lyric")); idle()
        verify(session!!, never()).setMetadata(any())
        publish(song.copy(title = "Second song", trackTitle = "Second song", persistentId = 2)); idle()
        val metadata = ArgumentCaptor.forClass(MediaMetadata::class.java)
        verify(session!!).setMetadata(metadata.capture())
        assertEquals("Second song", metadata.value.getString(MediaMetadata.METADATA_KEY_TITLE))
    }

    @Test fun missingStableItemTitleKeepsThePreviousTitleForDuduLookup() {
        val song = CarPlayNowPlaying(title = "Song", persistentId = 1, artist = "Artist",
            durationMillis = 180_000)
        publish(song); idle()
        val metadata = ArgumentCaptor.forClass(MediaMetadata::class.java)
        verify(session!!, atLeastOnce()).setMetadata(metadata.capture())
        assertEquals("Song", metadata.allValues.last().getString(MediaMetadata.METADATA_KEY_TITLE))

        clearInvocations(session!!)
        publish(song.copy(title = null, trackTitle = null, elapsedMillis = 10_000)); idle()

        verify(session!!, never()).setMetadata(any())
        assertEquals("Song", metadata.allValues.last().getString(MediaMetadata.METADATA_KEY_TITLE))
    }

    @Test fun sourcesWithoutAnItemIdentifierStillUpdateAtTheExistingTrackBoundary() {
        val song = CarPlayNowPlaying(title = "Song", artist = "Artist", durationMillis = 180_000,
            artworkTransferId = 7, elapsedMillis = 40_000)
        publish(song); idle()
        publish(song.copy(title = "A lyric")); idle()
        clearInvocations(session!!)
        publish(song.copy(title = "Next song", durationMillis = 200_000, elapsedMillis = 0)); idle()
        val metadata = ArgumentCaptor.forClass(MediaMetadata::class.java)
        verify(session!!).setMetadata(metadata.capture())
        assertEquals("Next song", metadata.value.getString(MediaMetadata.METADATA_KEY_TITLE))
    }

    @Test fun aLateCoverUsesTheStableTitleAndDisconnectDoesNotCarryItIntoTheNextSession() {
        val song = CarPlayNowPlaying(title = "Song", artworkTransferId = 7)
        publish(song); idle()
        publish(song.copy(title = "A lyric")); idle()
        clearInvocations(session!!)
        val cover = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val resultType = CarPlayMediaKeys.javaClass.declaredClasses.single { it.simpleName == "AmbientArtwork" }
        val result = resultType.declaredConstructors.single().apply { isAccessible = true }.newInstance(cover, null)
        val owner = ReflectionHelpers.getField<Any>(CarPlayMediaKeys, "artworkOwner")
        CarPlayMediaKeys.javaClass.declaredMethods.single { it.name == "onArtworkDecoded" }
            .apply { isAccessible = true }.invoke(CarPlayMediaKeys, owner, 7, result)
        val metadata = ArgumentCaptor.forClass(MediaMetadata::class.java)
        verify(session!!).setMetadata(metadata.capture())
        assertEquals("Song", metadata.value.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertSame(cover, metadata.value.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        CarPlayMediaKeys.detach(phone)
        retained = CarPlayNowPlaying(title = "New session")
        CarPlayMediaKeys.attach(context, phone)
        idle()
        CarPlayMediaKeys.ensureDuduSession(context)
        verify(session!!).setMetadata(metadata.capture())
        assertEquals("New session", metadata.value.getString(MediaMetadata.METADATA_KEY_TITLE))
    }

    @Test fun withoutDuduTheSessionStillPublishesThePhonesCurrentTitle() {
        AirPlayPersistence.saveDuduIntegration(context, false)
        val song = CarPlayNowPlaying(title = "Song", artworkTransferId = 7)
        publish(song); idle()
        clearInvocations(session!!)
        publish(song.copy(title = "A lyric")); idle()
        val metadata = ArgumentCaptor.forClass(MediaMetadata::class.java)
        verify(session!!).setMetadata(metadata.capture())
        assertEquals("A lyric", metadata.value.getString(MediaMetadata.METADATA_KEY_TITLE))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun publish(info: CarPlayNowPlaying) {
        retained = info
        receive!!(info)
    }
}
