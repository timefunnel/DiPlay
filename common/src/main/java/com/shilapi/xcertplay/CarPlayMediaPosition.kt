package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** Android's extrapolation anchor, derived exclusively from the phone's incremental updates. */
internal class CarPlayMediaPosition {
    var position: Long? = null
        private set
    var updatedAt = 0L
        private set
    private var sourcePosition: Long? = null
    private var revision = 0L
    private var playing = false

    fun currentPosition(now: Long): Long? = if (playing) position?.plus((now - updatedAt).coerceAtLeast(0)) else position

    fun update(info: CarPlayNowPlaying, now: Long) {
        if (sourcePosition != info.elapsedMillis || revision != info.positionRevision) {
            position = info.elapsedMillis
            updatedAt = now
        } else if (playing != info.playing) {
            // A status-only pause freezes the extrapolated position, not the old packet's position.
            position = currentPosition(now)
            updatedAt = now
        }
        sourcePosition = info.elapsedMillis
        revision = info.positionRevision
        playing = info.playing
    }
}
