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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A single EQ preset as reported by the device.
 *
 * Built-in presets (Balanced / Serenade / Bass) are not reported by the device and are hardcoded
 * here for selection only.
 */
public class OnePlusEqPreset {
    public static final int BUILTIN_BALANCED_ID = 0;
    public static final int BUILTIN_SERENADE_ID = 1;
    public static final int BUILTIN_BASS_ID = 2;

    public final int subtype;
    @NonNull
    public final String name;
    @NonNull
    public final int[] gains;

    public OnePlusEqPreset(final int subtype, @NonNull final String name, @NonNull final int[] gains) {
        this.subtype = subtype;
        this.name = name;
        this.gains = gains;
    }

    @NonNull
    public static String serialize(@NonNull final List<OnePlusEqPreset> presets) {
        final JSONArray arr = new JSONArray();
        try {
            for (final OnePlusEqPreset p : presets) {
                final JSONObject o = new JSONObject();
                o.put("subtype", p.subtype);
                o.put("name", p.name);
                final JSONArray g = new JSONArray();
                for (final int v : p.gains) {
                    g.put(v);
                }
                o.put("gains", g);
                arr.put(o);
            }
        } catch (final JSONException e) {
            // should not happen
        }
        return arr.toString();
    }

    @NonNull
    public static List<OnePlusEqPreset> parse(@Nullable final String json) {
        final List<OnePlusEqPreset> out = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return out;
        }
        try {
            final JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.getJSONObject(i);
                final int subtype = o.getInt("subtype");
                final String name = o.getString("name");
                final JSONArray g = o.getJSONArray("gains");
                final int[] gains = new int[g.length()];
                for (int j = 0; j < g.length(); j++) {
                    gains[j] = g.getInt(j);
                }
                out.add(new OnePlusEqPreset(subtype, name, gains));
            }
        } catch (final JSONException e) {
            // ignore malformed data
        }
        return out;
    }

    @Nullable
    public static OnePlusEqPreset findBySubtype(@NonNull final List<OnePlusEqPreset> presets, final int subtype) {
        for (final OnePlusEqPreset p : presets) {
            if (p.subtype == subtype) {
                return p;
            }
        }
        return null;
    }
}
