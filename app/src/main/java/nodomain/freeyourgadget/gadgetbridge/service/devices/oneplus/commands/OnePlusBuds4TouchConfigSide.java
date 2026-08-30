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

public enum OnePlusBuds4TouchConfigSide {
    LEFT(0x01),
    RIGHT(0x02),
    BOTH(0x04),
    ;

    private final int code;

    OnePlusBuds4TouchConfigSide(final int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    @Nullable
    public static OnePlusBuds4TouchConfigSide fromCode(final int code) {
        for (final OnePlusBuds4TouchConfigSide side : OnePlusBuds4TouchConfigSide.values()) {
            if (side.code == code) {
                return side;
            }
        }

        return null;
    }
}
