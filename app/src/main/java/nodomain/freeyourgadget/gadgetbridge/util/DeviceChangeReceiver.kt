package nodomain.freeyourgadget.gadgetbridge.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.util.kotlin.getDevice

abstract class DeviceChangeReceiver(private val device: GBDevice): BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GBDevice.ACTION_DEVICE_CHANGED) return

        val changedDevice = intent.getDevice() ?: return
        if (changedDevice != device) return
    }

    abstract fun onChange(updatedDevice: GBDevice)

    fun register(context: Context) {
        LocalBroadcastManager.getInstance(context).registerReceiver(
            this,
            IntentFilter(GBDevice.ACTION_DEVICE_CHANGED)
        )
    }

    fun unregister(context: Context) {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(this)
    }
}
