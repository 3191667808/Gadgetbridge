/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol;

import java.util.UUID;

public final class OuraUUIDs {
    public static final UUID PRIMARY_SERVICE = UUID.fromString("98ED0001-A541-11E4-B6A0-0002A5D5C51B");
    public static final UUID CHAR_WRITE = UUID.fromString("98ED0002-A541-11E4-B6A0-0002A5D5C51B");
    public static final UUID CHAR_NOTIFY = UUID.fromString("98ED0003-A541-11E4-B6A0-0002A5D5C51B");

    public static final UUID DFU_SERVICE = UUID.fromString("00060000-F8CE-11E4-ABF4-0002A5D5C51B");

    public static final int MANUFACTURER_ID = 0x02B2;

    private OuraUUIDs() {
    }
}
