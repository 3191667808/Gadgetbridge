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

public enum OnePlusBuds4Feature {
    AUTO_PLAY_PAUSE(0x04),
    WEAR_DETECTION(0x05),
    GAME_MODE(0x06),
    THREE_D_AUDIO(0x0b),
    DUAL_CONNECTION(0x11),
    HI_RES_AUDIO(0x18),
    SPATIAL_AUDIO(0x1b),
    UNKNOWN_1C(0x1c),
    UNKNOWN_1D(0x1d),
    ;

    private final int code;

    OnePlusBuds4Feature(final int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    @Nullable
    public static OnePlusBuds4Feature fromCode(final int code) {
        for (final OnePlusBuds4Feature feature : OnePlusBuds4Feature.values()) {
            if (feature.code == code) {
                return feature;
            }
        }

        return null;
    }
}
