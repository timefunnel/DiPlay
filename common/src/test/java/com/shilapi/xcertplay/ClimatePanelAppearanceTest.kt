package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w900dp-h600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ClimatePanelAppearanceTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun measure(view: View, width: Int, maxHeight: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun bitmap(view: View): Bitmap = Bitmap.createBitmap(view.width, view.height,
        Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    private fun save(view: View, name: String) {
        val output = File("build/reports/climate-ui/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { assertTrue(bitmap(view).compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun content(night: Boolean = true): ClimatePanelContent {
        lateinit var view: ClimatePanelContent
        view = ClimatePanelContent(context, night, { view.setNightMode(it) }, {}, {}, {}).apply {
            render(BydClimateBackend(ClimateTestBus()).read(), true)
            status.text = context.getString(R.string.climate_live)
        }
        return view
    }

    @Test fun widePanelFitsTheControlsAndProducesBothNativeThemePreviews() {
        val view = content()
        measure(view, 760, 420)
        save(view, "compact-dark")
        assertTrue("Compact panel height is ${view.height}dp", view.height <= 380)
        val scroll = descendants(view).filterIsInstance<ScrollView>().single()
        assertTrue("Wide layout should show every control without scrolling", scroll.getChildAt(0).height <= scroll.height)
        val buttons = descendants(view).filterIsInstance<Button>()
        assertEquals(11, buttons.size)
        buttons.forEach {
            assertTrue("Touch target too small: ${it.contentDescription}", it.width >= 44 && it.height >= 44)
            val parent = it.parent as View
            assertTrue("Control overflow: ${it.contentDescription}", it.left >= 0 && it.right <= parent.width)
            assertTrue(it.layout.getEllipsisCount(0) == 0)
        }
        buttons.single { it.contentDescription == context.getString(R.string.climate_switch_light) }.performClick()
        measure(view, 760, 420)
        save(view, "compact-light")
    }

    @Test fun narrowAndShortScreensKeepEveryActionInsideAReachableScrollArea() {
        val view = content()
        measure(view, 320, 360)
        assertTrue(view.height <= 360)
        val scroll = descendants(view).filterIsInstance<ScrollView>().single()
        assertTrue(scroll.getChildAt(0).height > scroll.height)
        descendants(view).filterIsInstance<Button>().forEach {
            val parent = it.parent as View
            assertTrue("Clipped control: ${it.contentDescription}", it.left >= 0 && it.right <= parent.width)
            assertTrue(it.width >= 44 && it.height >= 44)
        }
        save(view, "compact-narrow")
        scroll.scrollTo(0, scroll.getChildAt(0).height - scroll.height)
        assertEquals(scroll.getChildAt(0).height - scroll.height, scroll.scrollY)
        save(view, "compact-narrow-bottom")
        scroll.scrollTo(0, 0)
        measure(view, 760, 280)
        assertTrue(view.height <= 280)
        assertTrue(scroll.getChildAt(0).height > scroll.height)
        // Resizing the same instance restores the four-column layout.
        measure(view, 760, 420)
        assertTrue(scroll.getChildAt(0).height <= scroll.height)
    }

    @Test fun fanSegmentsUseFirmwareChoicesWithPaddingOnBothEnds() {
        val view = ClimateFanLevels(context)
        view.update(ClimateReading(5, listOf(0, 2, 5, 8), true), Color.DKGRAY, Color.WHITE)
        measure(view, 180, 28)
        val image = bitmap(view)
        assertEquals(Color.DKGRAY, image.getPixel(5, 14))
        assertEquals(Color.WHITE, image.getPixel(18, 14))
        assertEquals(Color.WHITE, image.getPixel(80, 14))
        assertEquals(Color.DKGRAY, image.getPixel(145, 14))
        assertEquals(Color.DKGRAY, image.getPixel(175, 14))
        assertEquals(context.getString(R.string.climate_fan_value, "5"), view.contentDescription)
    }

    @Test fun zeroAndUnknownFanValuesNeverPaintAnActiveSegment() {
        val view = ClimateFanLevels(context)
        measure(view, 180, 28)
        for (reading in listOf(ClimateReading(0, (0..7).toList(), true), ClimateReading(problem = ClimateProblem.VALUE))) {
            view.update(reading, Color.DKGRAY, Color.WHITE)
            val image = bitmap(view)
            for (x in 12..167) assertEquals(Color.DKGRAY, image.getPixel(x, 14))
        }
        assertEquals(context.getString(R.string.climate_fan_value, "—"), view.contentDescription)
    }

    @Test @Config(sdk = [28]) fun lightAppearanceAlsoMeasuresOnAndroidNine() {
        val view = content(night = false)
        measure(view, 600, 420)
        assertTrue(view.height <= 380)
        assertTrue(descendants(view).any { it.contentDescription == context.getString(R.string.climate_switch_dark) })
    }
}
