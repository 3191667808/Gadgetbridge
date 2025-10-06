/*  Copyright (C) 2025 hemisputnik (https://512b.dev/)

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.jbl.headphones

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder
import nodomain.freeyourgadget.gadgetbridge.service.serial.GBDeviceProtocol
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

        NotificationParser.tryParse(value).forEach {
            handleGBDeviceEvent(it)
        }

        return super.onCharacteristicChanged(gatt, characteristic, value)
    }

    override fun onPowerOff() =
        createTransactionBuilder("Power Off")
            .jblRequest(RequestBuilder.shutDown())
            .queue()

    override fun onReset(flags: Int) {
        if (flags and GBDeviceProtocol.RESET_FLAGS_FACTORY_RESET != 0) {
            createTransactionBuilder("Factory Reset")
                .jblRequest(RequestBuilder.factoryReset())
                .queue()
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(Tune720BTSupport::class.java)
    }
}
