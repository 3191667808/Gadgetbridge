package nodomain.freeyourgadget.gadgetbridge.service.devices.dji.usb

import android.location.Location

/**
 * Delivers the phone's GPS fixes from [DjiUsbSupport.onSetGpsLocation] to whichever HUD is
 * currently showing this device (see DjiHudOverlay).
 *
 * When no listener is registered, updates are simply dropped.
 */
object DjiLocationBus {
    @Volatile
    private var listener: ((Location) -> Unit)? = null

    /** Registers the single active consumer; pass null to unregister. */
    fun setListener(l: ((Location) -> Unit)?) {
        listener = l
    }

    /** Called for every new phone GPS fix. */
    fun publish(location: Location) {
        listener?.invoke(location)
    }
}
