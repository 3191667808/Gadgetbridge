package nodomain.freeyourgadget.gadgetbridge.service.devices.dji.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.util.kotlin.getSerializableCompat

abstract class DjiCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val command = intent.getSerializableCompat<Command>(COMMAND_EXTRA)!!
        handleCommand(command)
    }

    abstract fun handleCommand(command: Command)

    fun register(context: Context) {
        LocalBroadcastManager.getInstance(context).registerReceiver(
            this,
            IntentFilter(ACTION)
        )
    }

    fun unregister(context: Context) {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(this)
    }

    companion object {
        private const val ACTION = "nodomain.freeyourgadget.gadgetbridge.dji.COMMAND"
        private const val COMMAND_EXTRA = "COMMAND"

        /**
         * Requests a fresh keyframe from the camera (SPS/PPS/IDR).
         */
        fun requestKeyframe() {
            val intent = Intent(ACTION)
            intent.putExtra(COMMAND_EXTRA, Command.KEYFRAME_REQUEST)
            LocalBroadcastManager.getInstance(GBApplication.getContext()).sendBroadcast(intent)
        }

        enum class Command {
            KEYFRAME_REQUEST
        }
    }
}
