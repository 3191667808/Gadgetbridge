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

public enum OnePlusBuds4TouchConfigType {
    SINGLE_TAP(0x0101),
    DOUBLE_TAP(0x0201),
    TRIPLE_TAP(0x0301),
    TOUCH_HOLD(0x0401),
    SLIDE(0x0501),
    ON_CALL_DOUBLE_TAP(0x0206),
    ON_CALL_HOLD(0x0606),
    ;

    private final int code;

    OnePlusBuds4TouchConfigType(final int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    @Nullable
    public static OnePlusBuds4TouchConfigType fromCode(final int code) {
        for (final OnePlusBuds4TouchConfigType type : OnePlusBuds4TouchConfigType.values()) {
            if (type.code == code) {
                return type;
            }
        }

        return null;
    }
}
