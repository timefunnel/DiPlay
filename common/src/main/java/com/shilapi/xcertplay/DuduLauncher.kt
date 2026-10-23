package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

/** The showmap protocol used by Dudu's configurable AMap card. All access is on the main thread. */
internal object DuduLauncher {
    const val SHOW = "com.autonavi.plus.showmap"
    const val CLOSE = "com.autonavi.plus.closemap"
    const val PACKAGE = "com.dudu.autoui"
    private val listeners = mutableSetOf<() -> Unit>()
    var requestedBounds: Rect? = null
        private set
    var settingsVisible = false
        set(value) {
            if (field == value) return
            field = value
            changed()
        }
    private var lastNight: Boolean? = null

    fun observe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun clearRequest() {
        requestedBounds = null
        lastNight = null
        changed()
    }

    fun receive(context: Context, intent: Intent) {
        if (!AirPlayPersistence.loadDuduIntegration(context)) return
        when (intent.action) {
            SHOW -> {
                @Suppress("DEPRECATION")
                val metrics = DisplayMetrics().also {
                    context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(it)
                }
                val bounds = bounds(intent, metrics.widthPixels, metrics.heightPixels)
                if (bounds == null) {
                    Log.w("DiPlay-Dudu", "Rejected showmap: missing, invalid or off-screen bounds")
                    return
                }
                // A newly visible/restarted launcher may have missed the previous theme announcement.
                if (requestedBounds == null) lastNight = null
                requestedBounds = bounds
                changed()
            }
            CLOSE -> {
                requestedBounds = null
                changed()
            }
        }
    }

    /** In this protocol w/h are RIGHT/BOTTOM, not width/height. Reject rather than reinterpret. */
    internal fun bounds(intent: Intent, screenWidth: Int, screenHeight: Int): Rect? {
        val x = intent.getIntExtra("x", -1)
        val y = intent.getIntExtra("y", -1)
        val right = intent.getIntExtra("w", -1)
        val bottom = intent.getIntExtra("h", -1)
        return if (x >= 0 && y >= 0 && right > x && bottom > y &&
            right <= screenWidth && bottom <= screenHeight) Rect(x, y, right, bottom) else null
    }

    fun publishNight(context: Context, night: Boolean, sessionActive: Boolean) {
        if (!sessionActive || !AirPlayPersistence.loadDuduIntegration(context)) {
            lastNight = null
            return
        }
        if (lastNight == night) return
        context.sendBroadcast(Intent("AUTONAVI_STANDARD_BROADCAST_SEND")
            .setPackage(PACKAGE)
            .putExtra("KEY_TYPE", 10019)
            .putExtra("EXTRA_STATE", if (night) 38 else 37))
        lastNight = night
        Log.i("DiPlay-Dudu", "Day/night status sent: ${if (night) "night" else "day"}")
    }

    private fun changed() = listeners.toList().forEach { it() }
}

/** Does not start a connection or bring an Activity to the foreground. */
class DuduMapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = DuduLauncher.receive(context, intent)
}
