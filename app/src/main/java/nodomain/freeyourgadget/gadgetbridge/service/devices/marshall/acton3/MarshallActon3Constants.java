/*  Copyright (C) 2025

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.marshall.acton3;

import java.util.UUID;

public final class MarshallActon3Constants {
    // Marshall Custom Control Service
    public static final UUID UUID_SERVICE_MARSHALL_CONTROL = UUID.fromString("0000fccd-0000-1000-8000-00805f9b34fb");

    // Characteristics for control
    public static final UUID UUID_CHARACTERISTIC_VOLUME = UUID.fromString("00000007-1337-1dea-feed-c0ffee70c0de");
    public static final UUID UUID_CHARACTERISTIC_EQ = UUID.fromString("0000000f-1337-1dea-feed-c0ffee70c0de");
    public static final UUID UUID_CHARACTERISTIC_SOURCE = UUID.fromString("0000001b-1337-1dea-feed-c0ffee70c0de");
    public static final UUID UUID_CHARACTERISTIC_PLACEMENT = UUID.fromString("0000001e-1337-1dea-feed-c0ffee70c0de");

    // Volume range
    public static final int VOLUME_MIN = 0;
    public static final int VOLUME_MAX = 31;

    // Source values
    public static final byte SOURCE_BLUETOOTH = 0x00;
    public static final byte SOURCE_AUX = 0x02;

    // Placement values
    public static final byte PLACEMENT_WALL = 0x03;
    public static final byte PLACEMENT_EDGE = 0x04;
    public static final byte PLACEMENT_FREE = 0x05;

    // EQ range
    public static final int EQ_MIN = 0;
    public static final int EQ_MAX = 10;
    public static final int EQ_DEFAULT = 5;

    private MarshallActon3Constants() {
        // Utility class
    }
}
