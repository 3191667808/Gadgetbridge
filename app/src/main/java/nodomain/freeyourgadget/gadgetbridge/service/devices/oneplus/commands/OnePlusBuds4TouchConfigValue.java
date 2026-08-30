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

public enum OnePlusBuds4TouchConfigValue {
    OFF(0x00),
    PLAY_PAUSE(0x01),
    VOICE_ASSISTANT(0x03),
    PREVIOUS(0x05),
    NEXT(0x06),
    VOLUME_CONTROL(0x07),
    SWITCH_TRACK(0x0a),
    GAME_MODE(0x11),
    DECLINE_CALL(0x1c),
    ANSWER_END_CALL(0x1d),
    ;

    private final int code;

    OnePlusBuds4TouchConfigValue(final int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    @Nullable
    public static OnePlusBuds4TouchConfigValue fromCode(final int code) {
        for (final OnePlusBuds4TouchConfigValue value : OnePlusBuds4TouchConfigValue.values()) {
            if (value.code == code) {
                return value;
            }
        }

        return null;
    }
}
