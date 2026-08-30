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

public enum OnePlusBuds4Command {
    BATTERY_REQ(0x0100),
    BATTERY_RET(0x8100),
    SUBSCRIPTION_QUERY(0x0200),
    SUBSCRIPTION_QUERY_RET(0x8200),
    SUBSCRIPTION_SET(0x0205),
    SUBSCRIPTION_ACK(0x8205),
    SUBSCRIPTION_RET(0x0204),
    SETTINGS_QUERY(0x012f),
    SETTINGS_RET(0x812f),
    TOUCH_CONFIG_REQ(0x0108),
    TOUCH_CONFIG_SET(0x0401),
    TOUCH_CONFIG_RET(0x8108),
    TOUCH_CONFIG_ACK(0x8401),
    FIND_DEVICE_REQ(0x0400),
    FIND_DEVICE_ACK(0x8400),
    MISC_CONFIG_SET(0x0403),
    MISC_CONFIG_REQ(0x010d),
    MISC_CONFIG_ACK(0x8403),
    MISC_CONFIG_RET(0x810d),
    ANC_CONFIG_SET(0x0404),
    ANC_CONFIG_REQ(0x010c),
    ANC_CONFIG_ACK(0x8404),
    ANC_CONFIG_RET(0x810c),
    ALARM_VOLUME_SET(0x0427),
    ALARM_VOLUME_RET(0x8427),
    ALARM_VOLUME_REQ2(0x0130),
    ALARM_VOLUME_RET2(0x8130),
    EQ_SET(0x0406),
    EQ_RET(0x8406),
    EQ_CUSTOM_SET(0x0418),
    EQ_CUSTOM_RET(0x8418),
    EQ_QUERY(0x0122),
    EQ_QUERY_RET(0x8122),
    EQ_ACTIVE_STATE_REQ(0x010f),
    EQ_ACTIVE_STATE_RET(0x810f),
    EQ_STATE_RET(0x8114),
    BASS_SET(0x041b),
    BASS_RET(0x8124),
    BASS_REQ(0x0124),
    ;

    private final short code;

    OnePlusBuds4Command(final int code) {
        this.code = (short) code;
    }

    public short getCode() {
        return code;
    }

    @Nullable
    public static OnePlusBuds4Command fromCode(final short code) {
        for (final OnePlusBuds4Command cmd : OnePlusBuds4Command.values()) {
            if (cmd.code == code) {
                return cmd;
            }
        }

        return null;
    }
}
