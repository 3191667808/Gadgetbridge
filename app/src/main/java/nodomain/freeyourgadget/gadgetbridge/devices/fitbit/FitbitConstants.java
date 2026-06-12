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
    public static final boolean EXPERIMENTAL_ONBOARDING_MODE = true;
    public static final String CONFIG_PAIRING_CODE_PREFIX = "fitbit_pairing_code:";
    public static final String ACTION_PAIRING_CODE_ACCEPTED = "nodomain.freeyourgadget.gadgetbridge.devices.fitbit.ACTION_PAIRING_CODE_ACCEPTED";
    public static final String SYNC_RESPONSE_FIXTURE_DIRECTORY = "fitbit-sync-response-fixtures";
    public static final String PAIRING_CODE_FILE = "pairing-code.txt";

    public static final UUID FITBIT_GATTLINK_ADVERTISING_SERVICE = UUID.fromString("0000fd62-0000-1000-8000-00805f9b34fb");

    public static final UUID BOOTSTRAP_SERVICE = UUID.fromString("ac2f0045-8182-4be5-91e0-2992e6b40ebb");
    public static final UUID BOOTSTRAP_READ_CHARACTERISTIC = UUID.fromString("ac2f0145-8182-4be5-91e0-2992e6b40ebb");
    public static final UUID BOOTSTRAP_WRITE_CHARACTERISTIC = UUID.fromString("ac2f2745-8182-4be5-91e0-2992e6b40ebb");

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

    public static final byte[] BOOTSTRAP_WRITE_OPEN = new byte[]{0x00};
    public static final byte[] GATTLINK_CONTROL_OPEN = new byte[]{(byte) 0x80};
    public static final byte[] GATTLINK_CONTROL_WINDOW = new byte[]{(byte) 0x81, 0x00, 0x00, 0x08, 0x08};

    public static final byte[] BOOTSTRAP_READ_EXPECTED_VALUE = new byte[]{
            (byte) 0xac, 0x2f, 0x27, 0x45,
            (byte) 0x81, (byte) 0x82, 0x4b, (byte) 0xe5,
            (byte) 0x91, (byte) 0xe0, 0x29, (byte) 0x92,
            (byte) 0xe6, (byte) 0xb4, 0x0e, (byte) 0xbb
    };

    private FitbitConstants() {
    }
}
