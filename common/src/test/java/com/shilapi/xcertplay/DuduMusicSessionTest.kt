package com.shilapi.xcertplay

import android.content.Context
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

    private fun publish(info: CarPlayNowPlaying) {
        retained = info
        receive!!(info)
    }
}
