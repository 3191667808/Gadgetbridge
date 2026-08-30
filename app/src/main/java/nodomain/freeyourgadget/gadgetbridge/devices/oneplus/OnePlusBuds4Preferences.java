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
package nodomain.freeyourgadget.gadgetbridge.devices.oneplus;

import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4TouchConfigSide;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4TouchConfigType;

public final class OnePlusBuds4Preferences {
    private OnePlusBuds4Preferences() {}

    public static final String TOUCH_PREFIX = "oneplus_buds4_touch__";

    public static final String ANC_SELECTOR = "oneplus_buds4_anc_selector";
    public static final String ANC_TOUCH_CYCLE_MODES = "oneplus_buds4_anc_cycle_modes";
    public static final String WEAR_DETECTION = "oneplus_buds4_wear_detection";
    public static final String GAME_MODE = "oneplus_buds4_game_mode";
    public static final String DUAL_CONNECTION = "oneplus_buds4_dual_connection";
    public static final String SPATIAL_AUDIO = "oneplus_buds4_spatial_audio";
    public static final String AUTO_PLAY_PAUSE = "oneplus_buds4_auto_play_pause";
    public static final String ALARM_VOLUME = "oneplus_buds4_alarm_volume";
    public static final String ALARM_VOLUME_QUERY = "oneplus_buds4_alarm_volume_query";
    public static final String EQ_PRESET = "oneplus_buds4_eq_preset";
    public static final String EQ_CUSTOM_PRESETS = "oneplus_buds4_eq_custom_presets";
    public static final String EQ_BAND_PREFIX = "oneplus_buds4_eq_band_";
    public static final String EQ_ACTIVE_STATE_QUERY = "oneplus_buds4_eq_active_state_query";
    public static final String BASS_BOOST = "oneplus_buds4_bass_boost";

    public static final String EQ_PRESET_UNSET = "";
    public static final String EQ_PRESET_BUILTIN_PREFIX = "builtin:";
    public static final String EQ_PRESET_CUSTOM_PREFIX = "custom:";

    public static String getTouchKey(final OnePlusBuds4TouchConfigSide side, final OnePlusBuds4TouchConfigType type) {
        return TOUCH_PREFIX + side.name().toLowerCase(Locale.ROOT) + "__" + type.name().toLowerCase(Locale.ROOT);
    }

    public static String getEqBandKey(final int band) {
        return EQ_BAND_PREFIX + band;
    }
}
