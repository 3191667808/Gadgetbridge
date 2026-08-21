package nodomain.freeyourgadget.gadgetbridge.service.devices.dji.usb

import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages.DumlCommand

/**
 * Delivers decoded DUML commands from [DjiUsbSupport]'s background read thread to the video
 * activity.
 *
 * When no listener is registered, updates are simply dropped.
 */
object DjiTelemetryBus {
    @Volatile
    private var listener: ((DumlCommand) -> Unit)? = null

    /** Registers the single active consumer; pass null to unregister. */
    fun setListener(l: ((DumlCommand) -> Unit)?) {
        listener = l
    }

    /** Called for every decoded command that carries telemetry, on every packet that carries it. */
    fun publish(command: DumlCommand) {
        listener?.invoke(command)
    }
}
