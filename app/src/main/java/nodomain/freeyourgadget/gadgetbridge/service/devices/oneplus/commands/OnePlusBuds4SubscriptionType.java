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
package nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands;

import androidx.annotation.Nullable;

public enum OnePlusBuds4SubscriptionType {
    BATTERY(0x01),
    STATUS(0x02),
    ANC_SELECTOR(0x03),
    UNKNOWN_04(0x04),
    WEAR_DETECTION(0x05),
    UNKNOWN_08(0x08),
    EQUALIZER(0x0b),
    ALARM_VOLUME(0x30),
    ONEPLUS_SETTINGS_1(0xf1),
    ONEPLUS_SETTINGS_2(0xf2),
    ONEPLUS_SETTINGS_3(0xf3),
    ;

    private final int code;

    OnePlusBuds4SubscriptionType(final int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    @Nullable
    public static OnePlusBuds4SubscriptionType fromCode(final int code) {
        for (final OnePlusBuds4SubscriptionType type : OnePlusBuds4SubscriptionType.values()) {
            if (type.code == code) {
                return type;
            }
        }

        return null;
    }
}
