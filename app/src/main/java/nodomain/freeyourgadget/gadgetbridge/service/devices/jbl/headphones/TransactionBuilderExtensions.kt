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

import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger("nodomain.freeyourgadget.gadgetbridge.service.devices.jbl.headphones.TransactionBuilderExtensions")

fun TransactionBuilder.jblEnableResponseNotifications() =
    notify(JBLHeadphonesUUIDs.UUID_CHARACTERISTIC_READ, true)

fun TransactionBuilder.jblRequest(data: ByteArray): TransactionBuilder {
    LOG.debug("Sending request: {}", data.toHexString())
    return write(JBLHeadphonesUUIDs.UUID_CHARACTERISTIC_WRITE, *data)
}
