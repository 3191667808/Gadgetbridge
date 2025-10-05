package nodomain.freeyourgadget.gadgetbridge.service.devices.jbl.headphones

import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder

fun TransactionBuilder.jblEnableResponseNotifications() =
    notify(BleUUIDs.UUID_CHARACTERISTIC_READ, true)

fun TransactionBuilder.jblRequest(data: ByteArray) =
    write(BleUUIDs.UUID_CHARACTERISTIC_WRITE, *data)
