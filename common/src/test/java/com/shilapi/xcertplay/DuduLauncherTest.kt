package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class DuduLauncherTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        DuduLauncher.clearRequest()
        context.getSharedPreferences("diplay_dudu_card", Context.MODE_PRIVATE).edit().clear().commit()
        AirPlayPersistence.saveDuduIntegration(context, true)
    }
    @After fun cleanup() { AirPlayPersistence.saveDuduIntegration(context, false) }

    private fun show() = Intent(DuduLauncher.SHOW).putExtra("x", 100).putExtra("y", 50)
        .putExtra("w", 900).putExtra("h", 500)

    @Test fun cardSizeSurvivesCloseAndIsInvalidatedByADifferentPhysicalDisplay() {
        val display = context.getSystemService(android.view.WindowManager::class.java).defaultDisplay
        shadowOf(display).setRealWidth(1920)
        shadowOf(display).setRealHeight(1080)
        DuduLauncher.receive(context, Intent(DuduLauncher.SHOW).putExtra("x", 0).putExtra("y", 0)
            .putExtra("w", 1328).putExtra("h", 1012))
        assertEquals(1328 to 1012, DuduLauncher.rememberedSize(context, 1920, 1080))
        DuduLauncher.receive(context, Intent(DuduLauncher.CLOSE))
        DuduLauncher.clearRequest()
        assertEquals(1328 to 1012, DuduLauncher.rememberedSize(context, 1920, 1080))
        assertNull(DuduLauncher.rememberedSize(context, 1080, 1920))
        DuduLauncher.receive(context, show().putExtra("x", -1))
        assertEquals(1328 to 1012, DuduLauncher.rememberedSize(context, 1920, 1080))
    }

    @Test fun coordinatesAreRightAndBottomAndInvalidRequestsAreRejected() {
        assertEquals(Rect(100, 50, 900, 500), DuduLauncher.bounds(show(), 1920, 1080))
        assertNull(DuduLauncher.bounds(show().putExtra("w", 50), 1920, 1080))
        assertNull(DuduLauncher.bounds(show().putExtra("x", -10), 1920, 1080))
        assertNull(DuduLauncher.bounds(show(), 800, 480))
        assertNull(DuduLauncher.bounds(Intent(DuduLauncher.SHOW), 1920, 1080))
    }

    @Test fun cardRequestsDoNotBroadcastDayOrNightToTheLauncher() {
        val app = shadowOf(RuntimeEnvironment.getApplication())
        val initial = app.broadcastIntents.size
        val display = context.getSystemService(android.view.WindowManager::class.java).defaultDisplay
        shadowOf(display).setRealWidth(1920)
        shadowOf(display).setRealHeight(1080)
        DuduLauncher.receive(context, show())
        DuduLauncher.receive(context, Intent(DuduLauncher.CLOSE))
        DuduLauncher.receive(context, show())
        assertTrue(app.broadcastIntents.drop(initial).none {
            it.action == "AUTONAVI_STANDARD_BROADCAST_SEND" && it.getIntExtra("KEY_TYPE", -1) == 10019
        })
    }
}
