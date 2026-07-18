/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.devices.ycbt;

import java.util.UUID;

public final class YcbtConstants {
    public static final String R10M_DEVICE_NAME = "R10M FCF4";
    public static final byte[] OBSERVED_MANUFACTURER_BYTES = new byte[]{0x10, 0x78};

    public static final UUID SERVICE_UUID = UUID.fromString("be940000-7333-be46-b7ae-689e71722bd5");
    public static final UUID COMMAND_REPLY_CHARACTERISTIC_UUID = UUID.fromString("be940001-7333-be46-b7ae-689e71722bd5");
    public static final UUID STREAM_HISTORY_CHARACTERISTIC_UUID = UUID.fromString("be940003-7333-be46-b7ae-689e71722bd5");

    public static final boolean COMMAND_REPLY_REQUIRES_INDICATIONS = true;
    public static final boolean STREAM_HISTORY_REQUIRES_INDICATIONS = true;

    private YcbtConstants() {
    }
}
