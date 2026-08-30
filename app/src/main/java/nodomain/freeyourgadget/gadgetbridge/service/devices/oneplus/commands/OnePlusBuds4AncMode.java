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

import java.lang.Iterable;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import androidx.annotation.Nullable;

public enum OnePlusBuds4AncMode {
    OFF(0x01, "off"),
    TRANSPARENCY(0x04, "transparency"),
    ADAPTIVE(0x08, "adaptive"),
    NC_HIGH(0x10, "nc_high"),
    MODERATE(0x20, "nc_moderate"),
    LOW(0x40, "nc_low"),
    AUTO(0x80, "nc_auto"),
    ;

    private final int code;
    private final String prefId;

    OnePlusBuds4AncMode(final int code, final String prefId) {
        this.code = code;
        this.prefId = prefId;
    }

    public int getCode() {
        return code;
    }

    public String getPrefId() {
        return prefId;
    }

    @Nullable
    public static OnePlusBuds4AncMode fromCode(final int code) {
        for (final OnePlusBuds4AncMode mode : OnePlusBuds4AncMode.values()) {
            if (mode.code == code) {
                return mode;
            }
        }

        return null;
    }

    @Nullable
    public static OnePlusBuds4AncMode fromPrefId(final String prefId) {
        for (final OnePlusBuds4AncMode mode : OnePlusBuds4AncMode.values()) {
            if (mode.prefId.equals(prefId)) {
                return mode;
            }
        }

        return null;
    }

    public static Set<String> toPrefIds(final Iterable<OnePlusBuds4AncMode> modes) {
        final Set<String> prefIds = new HashSet<>();
        for (final OnePlusBuds4AncMode mode : modes) {
            prefIds.add(mode.getPrefId());
        }
        return prefIds;
    }

    public static EnumSet<OnePlusBuds4AncMode> fromPrefIds(final Iterable<String> prefIds) {
        final EnumSet<OnePlusBuds4AncMode> modes = EnumSet.noneOf(OnePlusBuds4AncMode.class);
        for (final String prefId : prefIds) {
            final OnePlusBuds4AncMode mode = fromPrefId(prefId);
            if (mode != null) {
                modes.add(mode);
            }
        }
        return modes;
    }
}
