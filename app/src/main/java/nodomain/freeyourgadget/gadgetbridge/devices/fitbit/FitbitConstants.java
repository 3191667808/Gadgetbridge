/*  Copyright (C) 2026 Marc

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
package nodomain.freeyourgadget.gadgetbridge.devices.fitbit;

import java.util.UUID;

public final class FitbitConstants {
    public static final String PREF_MOBILE_DATA_KEYS = "fitbit_mobile_data_keys";

    public static final UUID FITBIT_GATTLINK_ADVERTISING_SERVICE = UUID.fromString("0000fd62-0000-1000-8000-00805f9b34fb");

    public static final UUID GATTLINK_SERVICE = UUID.fromString("abbaff00-e56a-484c-b832-8b17cf6cbfe8");
    public static final UUID GATTLINK_WRITE_CHARACTERISTIC = UUID.fromString("abbaff01-e56a-484c-b832-8b17cf6cbfe8");
    public static final UUID GATTLINK_NOTIFY_CHARACTERISTIC = UUID.fromString("abbaff02-e56a-484c-b832-8b17cf6cbfe8");

    public static final UUID PHONE_LOCATION_SERVICE = UUID.fromString("16bcfd00-253f-c348-e831-0db3e334d580");
    public static final UUID PHONE_LOCATION_STATUS_1_CHARACTERISTIC = UUID.fromString("16bcfd01-253f-c348-e831-0db3e334d580");
    public static final UUID PHONE_LOCATION_STATUS_2_CHARACTERISTIC = UUID.fromString("16bcfd02-253f-c348-e831-0db3e334d580");
    public static final UUID PHONE_LOCATION_WRITE_CHARACTERISTIC = UUID.fromString("16bcfd03-253f-c348-e831-0db3e334d580");
    public static final UUID PHONE_LOCATION_STATUS_4_CHARACTERISTIC = UUID.fromString("16bcfd04-253f-c348-e831-0db3e334d580");

    public static final UUID PHONE_GATTLINK_SERVICE = UUID.fromString("abbafc00-e56a-484c-b832-8b17cf6cbfe8");
    public static final UUID PHONE_GATTLINK_STATUS_1_CHARACTERISTIC = UUID.fromString("abbafc01-e56a-484c-b832-8b17cf6cbfe8");
    public static final UUID PHONE_GATTLINK_STATUS_2_CHARACTERISTIC = UUID.fromString("abbafc02-e56a-484c-b832-8b17cf6cbfe8");
    public static final UUID PHONE_GATTLINK_STATUS_3_CHARACTERISTIC = UUID.fromString("abbafc03-e56a-484c-b832-8b17cf6cbfe8");

    public static final byte[] GATTLINK_CONTROL_OPEN = new byte[]{(byte) 0x80};
    public static final byte[] GATTLINK_CONTROL_WINDOW = new byte[]{(byte) 0x81, 0x00, 0x00, 0x08, 0x08};

    private FitbitConstants() {
    }
}
