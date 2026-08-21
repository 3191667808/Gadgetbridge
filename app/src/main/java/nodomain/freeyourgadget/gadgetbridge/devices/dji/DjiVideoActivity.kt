package nodomain.freeyourgadget.gadgetbridge.devices.dji

import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity
import nodomain.freeyourgadget.gadgetbridge.databinding.ActivityDjiVideoBinding
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.usb.DjiVideoFrameBus
import nodomain.freeyourgadget.gadgetbridge.util.DeviceChangeReceiver
import nodomain.freeyourgadget.gadgetbridge.util.kotlin.getDevice
import org.slf4j.LoggerFactory

/**
 * Full-screen live view for the DJI RC's USB video stream.
 *
 * [DjiVideoDecoder] does the actual video decoding, and [DjiHudOverlay] drives the
 * telemetry overlay. This class just owns the activity lifecycle and wires the two to it.
 */
class DjiVideoActivity : AbstractGBActivity() {
    private lateinit var device: GBDevice
    private lateinit var binding: ActivityDjiVideoBinding

    private val videoDecoder = DjiVideoDecoder()
    private lateinit var hudOverlay: DjiHudOverlay

    private lateinit var deviceChangeReceiver: DeviceChangeReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        init(this, NO_ACTIONBAR)
        supportActionBar?.hide()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityDjiVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        device = intent.getDevice()!!
        hudOverlay = DjiHudOverlay(this, device, binding)

        deviceChangeReceiver = object : DeviceChangeReceiver(device) {
            override fun onChange(updatedDevice: GBDevice) {
                device = updatedDevice

                if (!updatedDevice.isConnected) {
                    LOG.warn("Device disconnected")
                    finish()
                    return
                }

                hudOverlay.onDeviceChanged(device)
            }
        }

        binding.surfaceVideo.holder.addCallback(videoDecoder)

        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onResume() {
        super.onResume()
        if (!device.isConnected) {
            LOG.debug("Device already disconnected on resume")
            finish()
            return
        }

        deviceChangeReceiver.register(this)

        DjiVideoFrameBus.setListener { nal ->
            hudOverlay.onVideoBytes(nal.size)
            videoDecoder.feedNal(nal)
        }

        hudOverlay.start()
        hudOverlay.onDeviceChanged(device)
    }

    override fun onPause() {
        super.onPause()
        DjiVideoFrameBus.setListener(null)
        hudOverlay.stop()
        deviceChangeReceiver.unregister(this)
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(DjiVideoActivity::class.java)
    }
}
