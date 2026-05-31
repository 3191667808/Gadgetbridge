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

import java.util.UUID

object BleUUIDs {
    val UUID_SERVICE_GENERIC = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0000")
    val UUID_CHARACTERISTIC_READ = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0001")
    val UUID_CHARACTERISTIC_WRITE = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0002")

    val UUID_SERVICE_OTA = UUID.fromString("66666666-6666-6666-6666-666666666666")
    val UUID_CHARACTERISTIC_OTA = UUID.fromString("77777777-7777-7777-7777-777777777777")

    val UUID_SERVICE_UNKNOWN1 = UUID.fromString("00007033-0000-1000-8000-00805f9b34fb")
    val UUID_SERVICE_UNKNOWN2 = UUID.fromString("0000fe2c-0000-1000-8000-00805f9b34fb")
}
