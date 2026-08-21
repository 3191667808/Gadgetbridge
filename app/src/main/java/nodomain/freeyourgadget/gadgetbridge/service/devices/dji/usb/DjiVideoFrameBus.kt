package nodomain.freeyourgadget.gadgetbridge.service.devices.dji.usb

/**
 * Delivers decoded video NAL units from [DjiUsbSupport]'s background
 * read thread to the video activity.
 *
 * When no listener is registered, updates are simply dropped.
 */
object DjiVideoFrameBus {
    @Volatile
    private var listener: ((ByteArray) -> Unit)? = null

    /** Registers the single active consumer; pass null to unregister. */
    fun setListener(l: ((ByteArray) -> Unit)?) {
        listener = l
    }

    /** Called for every completed NAL unit. */
    fun publish(nal: ByteArray) {
        listener?.invoke(nal)
    }
}
