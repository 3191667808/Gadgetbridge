package nodomain.freeyourgadget.gadgetbridge.service.devices.jbl.headphones

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.LoggerFactory

@SuppressLint("MissingPermission")
class Tune720BTSupport : AbstractBTLESingleDeviceSupport(LOG) {
    init {
        addSupportedService(BleUUIDs.UUID_SERVICE_UNKNOWN1)
        addSupportedService(BleUUIDs.UUID_SERVICE_UNKNOWN2)
        addSupportedService(BleUUIDs.UUID_SERVICE_GENERIC)
        addSupportedService(BleUUIDs.UUID_SERVICE_OTA)
    }

    override fun useAutoConnect() = true

    override fun initializeDevice(builder: TransactionBuilder): TransactionBuilder {
        LOG.debug("initializing")

        builder
            .setDeviceState(GBDevice.State.INITIALIZING)
            .jblEnableResponseNotifications()
            .jblRequest(RequestBuilder.batteryInfo())
            .setDeviceState(GBDevice.State.INITIALIZED)

        device.firmwareVersion = "N/A"
        device.firmwareVersion2 = "N/A"

        return builder
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ): Boolean {
        LOG.debug(
            "onCharacteristicRead: characteristic={}, value={}",
            characteristic.uuid,
            GB.hexdump(value)
        )

        return super.onCharacteristicRead(gatt, characteristic, value, status)
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        LOG.debug(
            "onCharacteristicChanged characteristic={}, value={}",
            characteristic.uuid,
            GB.hexdump(value)
        )

        return NotificationParser.tryParse(this, value) ||
                super.onCharacteristicChanged(gatt, characteristic, value)
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(Tune720BTSupport::class.java)
    }
}
