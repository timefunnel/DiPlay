package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.LinearLayout
import android.widget.Toast
import com.shilapi.xcertplay.host.R
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

/** A bottom overlay owned by the visible CarPlay activity. Touch and wheel commands use the same backend. */
internal class CarPlayClimatePanel(
    context: Context,
    private val allowed: () -> Boolean,
    private val closePanel: () -> Unit,
    initialNight: Boolean,
    requestNight: (Boolean) -> Unit,
    private val backend: BydClimateBackend = BydClimateBackend(ReflectiveClimateBus(context)),
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, "diplay-climate") },
) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var active = true
    private var reading = false
    private var pending = 0
    private var snapshot: ClimateSnapshot? = null
    private var commandProblem: ClimateProblem? = null
    private val content = ClimatePanelContent(context, initialNight, requestNight, ::perform, closePanel) {
        commandProblem = null
        refresh()
    }
    private val status get() = content.status
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            if (!allowed()) { closePanel(); return }
            refresh()
            handler.postDelayed(this, 1_000)
        }
    }

    init {
        orientation = VERTICAL
        isClickable = true
        clipChildren = false
        addView(content, LayoutParams(-1, -2))
        render()
    }

    fun start() { handler.post(poll) }

    fun setNightMode(night: Boolean) { content.setNightMode(night) }

    fun stop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    fun perform(action: WheelControlAction) {
        if (action == WheelControlAction.CLOSE) { closePanel(); return }
        if (!active || !allowed()) { closePanel(); return }
        val field = field(action) ?: return
        if (snapshot?.get(field)?.enabled != true) {
            status.text = context.getString(R.string.climate_unavailable)
            return
        }
        if (pending >= 8) {
            Toast.makeText(context, context.getString(R.string.climate_busy), Toast.LENGTH_SHORT).show()
            return
        }
        pending++
        commandProblem = null
        status.text = context.getString(R.string.climate_applying, context.getString(action.label))
        render()
        submit {
            try {
                val actual = backend.execute(action) { active && allowed() }
                publish {
                    pending--
                    snapshot = actual
                    status.text = when {
                        commandProblem != null -> problemText(commandProblem!!)
                        pending > 0 -> context.getString(R.string.climate_pending, pending)
                        else -> context.getString(R.string.climate_confirmed)
                    }
                    render()
                }
            } catch (error: ClimateException) {
                fail(error, command = true)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                if (active) fail(ClimateException(ClimateProblem.CANCELLED, "Climate task interrupted", error), true)
            } catch (error: Exception) {
                fail(ClimateException(ClimateProblem.INTERFACE, error.toString(), error), true)
            }
        }
    }

    private fun refresh() {
        if (!active || reading || pending > 0) return
        reading = true
        submit {
            try {
                val actual = backend.read()
                publish {
                    reading = false
                    snapshot = actual
                    val problems = actual.fields.values.mapNotNull { it.problem }.distinct()
                    status.text = if (commandProblem != null) problemText(commandProblem!!)
                    else if (problems.isEmpty()) context.getString(R.string.climate_live)
                    else problems.joinToString(" · ") { problemText(it) }
                    actual.fields.forEach { (field, value) ->
                        if (value.problem != null) Log.w(TAG, "$field: ${value.detail}")
                    }
                    render()
                }
            } catch (error: Exception) {
                fail(error as? ClimateException ?: ClimateException(ClimateProblem.INTERFACE, error.toString(), error), false)
            }
        }
    }

    private fun submit(task: () -> Unit) {
        try { worker.execute { if (active) task() } }
        catch (error: RejectedExecutionException) {
            if (active) fail(ClimateException(ClimateProblem.INTERFACE, "Climate worker rejected task", error), pending > 0)
        }
    }

    private fun publish(update: () -> Unit) {
        handler.post { if (active && allowed()) update() }
    }

    private fun fail(error: ClimateException, command: Boolean) {
        Log.w(TAG, "Climate ${if (command) "command" else "read"} failed: ${error.message}", error)
        publish {
            if (command) { pending--; commandProblem = error.problem } else reading = false
            status.text = problemText(error.problem)
            render()
        }
    }

    private fun render() {
        content.render(snapshot, active && pending == 0)
    }

    private fun problemText(problem: ClimateProblem) = context.getString(when (problem) {
        ClimateProblem.INTERFACE -> R.string.climate_interface_error
        ClimateProblem.PERMISSION -> R.string.climate_permission_error
        ClimateProblem.UNSUPPORTED -> R.string.climate_unavailable
        ClimateProblem.VALUE -> R.string.climate_value_error
        ClimateProblem.UNIT -> R.string.climate_unit_error
        ClimateProblem.WRITE -> R.string.climate_write_error
        ClimateProblem.UNCONFIRMED -> R.string.climate_unconfirmed
        ClimateProblem.CANCELLED -> R.string.climate_cancelled
        ClimateProblem.LIMIT -> R.string.climate_limit
    })

    private fun field(action: WheelControlAction): ClimateField? = when (action) {
        WheelControlAction.TEMPERATURE_UP, WheelControlAction.TEMPERATURE_DOWN -> ClimateField.TEMPERATURE
        WheelControlAction.FAN_UP, WheelControlAction.FAN_DOWN -> ClimateField.FAN
        WheelControlAction.POWER -> ClimateField.POWER
        WheelControlAction.COMPRESSOR -> ClimateField.COMPRESSOR
        WheelControlAction.CYCLE -> ClimateField.CYCLE
        WheelControlAction.AUTO -> ClimateField.AUTO
        else -> null
    }

    companion object { private const val TAG = "DiPlay-Climate" }
}
