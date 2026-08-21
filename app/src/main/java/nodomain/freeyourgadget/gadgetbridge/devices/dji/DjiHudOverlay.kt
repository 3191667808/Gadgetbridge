package nodomain.freeyourgadget.gadgetbridge.devices.dji

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.hypot
import nodomain.freeyourgadget.gadgetbridge.BuildConfig
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.databinding.ActivityDjiVideoBinding
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages.DumlCommand
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages.Flyc
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages.HdLink
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.usb.DjiLocationBus
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.usb.DjiTelemetryBus

/**
 * Drives every field in [DjiVideoActivity]'s HUD overlay - battery, signal, and the
 * flight-controller fields - from [DjiTelemetryBus], and battery states.
 */
class DjiHudOverlay(
    private val context: Context,
    private val device: GBDevice,
    private val binding: ActivityDjiVideoBinding
) {
    // ACTION_BATTERY_CHANGED is sticky, so registering it also delivers the current phone
    // battery level right away, not just future changes.
    private val phoneBatteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updatePhoneBatteryOverlay(intent)
        }
    }

    // Last known video signal value, and when it was seen.
    private var lastVideoConnected: Boolean? = null
    private var lastVideoSeenAtMs: Long = 0L

    // Bytes of video data seen since the last bitrate tick. Written from the USB read thread
    // (via onVideoBytes) and drained from the main thread on each telemetryTicker run
    private val videoBytesSinceTick = AtomicLong(0L)
    private var lastBitrateTickAtMs: Long = 0L
    private var lastVideoBitrateMbps: Double? = null

    // Last known flight telemetry, and when it was seen.
    private var lastFlightTelemetry: Flyc.OsdGeneral.Request? = null
    private var lastFlightTelemetrySeenAtMs: Long = 0L

    // Last known phone GPS fix, and when it was seen - see onPhoneLocation.
    private var lastPhoneLocation: Location? = null
    private var lastPhoneLocationSeenAtMs: Long = 0L

    private val telemetryHandler = Handler(Looper.getMainLooper())

    // Redraws the overlay on a timer, not just when new telemetry arrives, so a stale field
    // falls back to "unknown" after a timeout instead of freezing at its last value forever.
    private val telemetryTicker = object : Runnable {
        override fun run() {
            renderVideoSignalOverlay()
            renderFlightOverlay()
            renderPipelineTraceOverlay()
            telemetryHandler.postDelayed(this, TELEMETRY_TICK_INTERVAL_MS)
        }
    }

    /** Registers listeners/receivers and starts rendering. Call from `onResume`. */
    fun start() {
        DjiTelemetryBus.setListener(::onTelemetry)
        DjiLocationBus.setListener(::onPhoneLocation)
        ContextCompat.registerReceiver(
            context,
            phoneBatteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        videoBytesSinceTick.set(0L)
        lastBitrateTickAtMs = SystemClock.elapsedRealtime()
        lastVideoBitrateMbps = null
        telemetryTicker.run()
        updateDeviceBatteryOverlay(device)
    }

    /** Unregisters listeners/receivers and stops rendering. Call from `onPause`. */
    fun stop() {
        DjiTelemetryBus.setListener(null)
        DjiLocationBus.setListener(null)
        context.unregisterReceiver(phoneBatteryReceiver)
        telemetryHandler.removeCallbacks(telemetryTicker)
    }

    /** Call whenever the activity's `GBDevice` reference changes - battery levels live on it. */
    fun onDeviceChanged(device: GBDevice) {
        updateDeviceBatteryOverlay(device)
    }

    /** Call for every NAL unit fed to the video decoder, to track the downlink data rate. */
    fun onVideoBytes(byteCount: Int) {
        videoBytesSinceTick.addAndGet(byteCount.toLong())
    }

    private fun onTelemetry(command: DumlCommand) {
        when (command) {
            is HdLink.HdLinkState.Request -> {
                lastVideoConnected = command.state == 0x00
                lastVideoSeenAtMs = SystemClock.elapsedRealtime()
            }

            is Flyc.OsdGeneral.Request -> {
                lastFlightTelemetry = command
                lastFlightTelemetrySeenAtMs = SystemClock.elapsedRealtime()
            }

            else -> {}
        }
    }

    private fun onPhoneLocation(location: Location) {
        lastPhoneLocation = location
        lastPhoneLocationSeenAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * Distance between the phone's last GPS fix and the aircraft's OSD position,
     * or null if either is unavailable or stale.
     */
    private fun horizontalDistanceMeters(flightTelemetry: Flyc.OsdGeneral.Request): Float? {
        if (!flightTelemetry.gpsUsed) return null
        val phoneLocation = lastPhoneLocation ?: return null
        if (SystemClock.elapsedRealtime() - lastPhoneLocationSeenAtMs > TELEMETRY_STALE_TIMEOUT_MS) return null

        // Aircraft longitude/latitude are in radians
        val results = FloatArray(1)
        Location.distanceBetween(
            phoneLocation.latitude,
            phoneLocation.longitude,
            Math.toDegrees(flightTelemetry.latitude),
            Math.toDegrees(flightTelemetry.longitude),
            results
        )
        return results[0]
    }

    private fun renderVideoSignalOverlay() {
        val stale = lastVideoConnected == null ||
                SystemClock.elapsedRealtime() - lastVideoSeenAtMs > TELEMETRY_STALE_TIMEOUT_MS
        val stateText = when {
            stale -> context.getString(R.string.dji_hud_placeholder)
            lastVideoConnected == true -> context.getString(R.string.dji_hud_video_connected)
            else -> context.getString(R.string.dji_hud_video_lost)
        }
        val statusColorResId = when {
            stale -> R.color.dji_hud_warning
            lastVideoConnected == false -> R.color.dji_hud_error
            else -> android.R.color.white
        }

        binding.textSignal.text = stateText
        binding.textStatus.setTextColor(ContextCompat.getColor(context, statusColorResId))

        val now = SystemClock.elapsedRealtime()
        val elapsedMs = now - lastBitrateTickAtMs
        lastBitrateTickAtMs = now
        val bytes = videoBytesSinceTick.getAndSet(0L)
        lastVideoBitrateMbps = if (elapsedMs > 0) (bytes * 8.0) / (elapsedMs * 1000.0) else lastVideoBitrateMbps

        val bitrateMbps = lastVideoBitrateMbps
        binding.textVideo.text = if (stale || bitrateMbps == null) {
            context.getString(R.string.dji_hud_bitrate, context.getString(R.string.dji_hud_placeholder))
        } else {
            // TODO i18n
            "%.1fMbps".format(bitrateMbps)
        }
    }

    /** Renders every field sourced from DjiUsbSupport.handleFlightState. */
    private fun renderFlightOverlay() {
        val flightTelemetry = lastFlightTelemetry
        val stale = flightTelemetry == null ||
                SystemClock.elapsedRealtime() - lastFlightTelemetrySeenAtMs > TELEMETRY_STALE_TIMEOUT_MS
        val placeholder = context.getString(R.string.dji_hud_placeholder)

        binding.textGps.text = context.getString(
            R.string.dji_hud_gps,
            if (stale) placeholder else flightTelemetry.gpsNums.toString()
        )

        binding.textAltitude.text = context.getString(
            R.string.dji_hud_altitude,
            if (stale) placeholder else "%.1fm".format(flightTelemetry.relativeHeight)
        )

        val distanceMeters = if (stale) null else horizontalDistanceMeters(flightTelemetry)
        binding.textDistance.text = context.getString(
            R.string.dji_hud_distance,
            // TODO i18n
            if (distanceMeters == null) placeholder else "%.0fm".format(distanceMeters)
        )

        if (stale) {
            binding.textVerticalSpeed.text = placeholder
            binding.textHorizontalSpeed.text = placeholder
        } else {
            // vgz is positive downward - flip it so climbing shows positive.
            // TODO i18n
            binding.textVerticalSpeed.text = "%+.1fm/s".format(-flightTelemetry.vgz)
            binding.textHorizontalSpeed.text =
                "%.1fm/s".format(hypot(flightTelemetry.vgx.toDouble(), flightTelemetry.vgy.toDouble()))
        }

        val statusText = if (stale) {
            placeholder
        } else when {
            flightTelemetry.inAir -> context.getString(R.string.dji_hud_status_flying)
            flightTelemetry.motorOn -> context.getString(R.string.dji_hud_status_armed)
            else -> context.getString(R.string.dji_hud_status_ground)
        }

        // TODO go-home state

        binding.textFlightMode.text = flightTelemetry?.flycState?.javaClass?.simpleName ?: placeholder
        binding.textStatus.text = statusText
        val statusColorResId = if (stale) R.color.dji_hud_warning else android.R.color.white
        binding.textStatus.setTextColor(ContextCompat.getColor(context, statusColorResId))
    }

    /**
     * Renders every field of [DjiPipelineTrace]'s latest 5s summary into the bottom-right panel,
     * debug builds only - this is a bottleneck-hunting aid for developers, not a pilot-facing
     * field, and its numbers (process ms/chunk, queue depth, drop rates) mean nothing without
     * knowing the pipeline's internals. [DjiPipelineTrace.lastSnapshot] is read-only here: the
     * snapshot is produced and reset on [DjiUsbSupport]'s own 5s tick, independent of this 1s
     * ticker, so it only actually changes about every 5th call.
     *
     * Built as a plain Kotlin string, not routed through a string resource like the rest of this
     * file's fields - unlike those, every label here (Q, Proc, DWait, ...) is a technical
     * shorthand for a counter defined in code, not a phrase a translator could sensibly localize.
     */
    private fun renderPipelineTraceOverlay() {
        if (!BuildConfig.DEBUG) return
    }

    /** Reflects the RC's and drone's last known battery pushes. */
    private fun updateDeviceBatteryOverlay(device: GBDevice) {
        updateBatteryText(
            binding.textBatteryRc,
            R.string.dji_hud_battery_rc,
            device.getBatteryLevel(DjiConst.BATTERY_IDX_RC)
        )
        updateBatteryText(
            binding.textBatteryDrone,
            R.string.dji_hud_battery_drone,
            device.getBatteryLevel(DjiConst.BATTERY_IDX_DRONE)
        )
    }

    private fun updatePhoneBatteryOverlay(batteryChangedIntent: Intent) {
        val level = batteryChangedIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryChangedIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        updateBatteryText(binding.textBatteryPhone, R.string.dji_hud_battery_phone, percent)
    }

    private fun updateBatteryText(view: TextView, formatResId: Int, percent: Int?) {
        val known = percent != null && percent != GBDevice.BATTERY_UNKNOWN.toInt()
        view.text =
            context.getString(formatResId, if (known) "$percent%" else context.getString(R.string.dji_hud_placeholder))
        val colorResId = when {
            !known -> R.color.dji_hud_warning
            percent < LOW_BATTERY_PERCENT -> R.color.dji_hud_error
            percent < MEDIUM_BATTERY_PERCENT -> R.color.dji_hud_warning
            else -> android.R.color.white
        }
        view.setTextColor(ContextCompat.getColor(context, colorResId))
    }

    companion object {
        private const val LOW_BATTERY_PERCENT = 35
        private const val MEDIUM_BATTERY_PERCENT = 50

        private const val TELEMETRY_TICK_INTERVAL_MS = 1000L

        // Telemetry is expected every ~500ms - a 3s gap means several pushes in a row were missed,
        // not just one late packet.
        private const val TELEMETRY_STALE_TIMEOUT_MS = 3000L
    }
}
