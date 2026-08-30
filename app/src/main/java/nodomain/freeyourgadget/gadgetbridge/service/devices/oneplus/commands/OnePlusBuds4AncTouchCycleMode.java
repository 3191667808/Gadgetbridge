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

/**
 * Modes selectable in the ANC touch-and-hold cycle config (0x0201 type).
 *
 * This uses its OWN bit code space, independent of {@link OnePlusBuds4AncMode}
 * (the main ANC mode-selection codes). Verified against the real-app btsnoop capture:
 *   - 020102 = nc only
 *   - 02010608 = nc (0x02) | transparency (0x04) with the adaptive (0x08) flag split out
 *   - 02010708 = nc | transparency | off (0x01) with the adaptive flag
 */
public enum OnePlusBuds4AncTouchCycleMode {
    OFF(0x01, "off"),
    NC(0x02, "nc"),
    TRANSPARENCY(0x04, "transparency"),
    ADAPTIVE(0x08, "adaptive"),
    ;

    private final int bit;
    private final String prefId;

    OnePlusBuds4AncTouchCycleMode(final int bit, final String prefId) {
        this.bit = bit;
        this.prefId = prefId;
    }

    public int getBit() {
        return bit;
    }

    public String getPrefId() {
        return prefId;
    }

    @Nullable
    public static OnePlusBuds4AncTouchCycleMode fromPrefId(final String prefId) {
        for (final OnePlusBuds4AncTouchCycleMode mode : values()) {
            if (mode.prefId.equals(prefId)) {
                return mode;
            }
        }
        return null;
    }

    public static int toMask(final Iterable<OnePlusBuds4AncTouchCycleMode> modes) {
        int mask = 0;
        for (final OnePlusBuds4AncTouchCycleMode mode : modes) {
            if (mode != ADAPTIVE) {
                mask |= mode.getBit();
            }
        }
        return mask;
    }

    public static EnumSet<OnePlusBuds4AncTouchCycleMode> fromMask(final int mask) {
        final EnumSet<OnePlusBuds4AncTouchCycleMode> modes = EnumSet.noneOf(OnePlusBuds4AncTouchCycleMode.class);
        for (final OnePlusBuds4AncTouchCycleMode mode : values()) {
            if ((mask & mode.getBit()) == mode.getBit()) {
                modes.add(mode);
            }
        }
        return modes;
    }

    public static Set<String> toPrefIds(final Iterable<OnePlusBuds4AncTouchCycleMode> modes) {
        final Set<String> prefIds = new HashSet<>();
        for (final OnePlusBuds4AncTouchCycleMode mode : modes) {
            prefIds.add(mode.getPrefId());
        }
        return prefIds;
    }

    public static EnumSet<OnePlusBuds4AncTouchCycleMode> fromPrefIds(final Iterable<String> prefIds) {
        final EnumSet<OnePlusBuds4AncTouchCycleMode> modes = EnumSet.noneOf(OnePlusBuds4AncTouchCycleMode.class);
        for (final String prefId : prefIds) {
            final OnePlusBuds4AncTouchCycleMode mode = fromPrefId(prefId);
            if (mode != null) {
                modes.add(mode);
            }
        }
        return modes;
    }
}
