/*  Copyright (C) 2025

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
package nodomain.freeyourgadget.gadgetbridge.devices.marshall;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCardAction;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

import static nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst.*;

public class MarshallCardActions {

    @Nullable
    private static SharedPreferences getPrefs(GBDevice device) {
        return GBApplication.getDeviceSpecificSharedPrefs(device.getAddress());
    }

    public static class SourceAction implements DeviceCardAction {
        @Override
        public int getIcon(GBDevice device) {
            String source = getSource(device);
            return "aux".equals(source) ? R.drawable.ic_music_note : R.drawable.ic_bluetooth;
        }

        @Override
        public String getDescription(GBDevice device, Context context) {
            String source = getSource(device);
            return "aux".equals(source)
                    ? context.getString(R.string.marshall_source_aux)
                    : context.getString(R.string.marshall_source_bluetooth);
        }

        @Override
        public String getLabel(GBDevice device, Context context) {
            String source = getSource(device);
            return "aux".equals(source) ? "AUX" : "BT";
        }

        @Override
        public void onClick(GBDevice device, Context context) {
            String currentSource = getSource(device);
            String newSource = "aux".equals(currentSource) ? "bluetooth" : "aux";

            SharedPreferences prefs = getPrefs(device);
            if (prefs != null) {
                prefs.edit().putString(PREF_MARSHALL_SOURCE, newSource).apply();
                GBApplication.deviceService(device).onSendConfiguration(PREF_MARSHALL_SOURCE);
                device.sendDeviceUpdateIntent(context);
            }
        }

        private String getSource(GBDevice device) {
            SharedPreferences prefs = getPrefs(device);
            return prefs != null ? prefs.getString(PREF_MARSHALL_SOURCE, "bluetooth") : "bluetooth";
        }
    }

    public static class PlacementAction implements DeviceCardAction {
        @Override
        public int getIcon(GBDevice device) {
            return R.drawable.ic_speaker;
        }

        @Override
        public String getDescription(GBDevice device, Context context) {
            String placement = getPlacement(device);
            switch (placement) {
                case "wall":
                    return context.getString(R.string.marshall_placement_wall);
                case "edge":
                    return context.getString(R.string.marshall_placement_edge);
                default:
                    return context.getString(R.string.marshall_placement_free);
            }
        }

        @Override
        public String getLabel(GBDevice device, Context context) {
            String placement = getPlacement(device);
            switch (placement) {
                case "wall":
                    return context.getString(R.string.marshall_placement_wall_short);
                case "edge":
                    return context.getString(R.string.marshall_placement_edge_short);
                default:
                    return context.getString(R.string.marshall_placement_free_short);
            }
        }

        @Override
        public void onClick(GBDevice device, Context context) {
            String currentPlacement = getPlacement(device);

            LayoutInflater inflater = LayoutInflater.from(context);
            View view = inflater.inflate(R.layout.dialog_marshall_placement, null);

            RadioGroup radioGroup = view.findViewById(R.id.placement_radio_group);
            RadioButton radioFree = view.findViewById(R.id.placement_free);
            RadioButton radioWall = view.findViewById(R.id.placement_wall);
            RadioButton radioEdge = view.findViewById(R.id.placement_edge);

            switch (currentPlacement) {
                case "wall":
                    radioWall.setChecked(true);
                    break;
                case "edge":
                    radioEdge.setChecked(true);
                    break;
                default:
                    radioFree.setChecked(true);
                    break;
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle(R.string.marshall_pref_placement_title);
            builder.setView(view);
            builder.setPositiveButton(R.string.ok, (dialog, which) -> {
                String newPlacement;
                if (radioWall.isChecked()) {
                    newPlacement = "wall";
                } else if (radioEdge.isChecked()) {
                    newPlacement = "edge";
                } else {
                    newPlacement = "free";
                }

                SharedPreferences prefs = getPrefs(device);
                if (prefs != null) {
                    prefs.edit().putString(PREF_MARSHALL_PLACEMENT, newPlacement).apply();
                    GBApplication.deviceService(device).onSendConfiguration(PREF_MARSHALL_PLACEMENT);
                    device.sendDeviceUpdateIntent(context);
                }
            });
            builder.setNegativeButton(R.string.Cancel, null);
            builder.show();
        }

        private String getPlacement(GBDevice device) {
            SharedPreferences prefs = getPrefs(device);
            return prefs != null ? prefs.getString(PREF_MARSHALL_PLACEMENT, "free") : "free";
        }
    }

    public static class VolumeAction implements DeviceCardAction {
        @Override
        public int getIcon(GBDevice device) {
            return R.drawable.ic_volume_up;
        }

        @Override
        public String getDescription(GBDevice device, Context context) {
            return context.getString(R.string.marshall_pref_volume_title);
        }

        @Override
        public String getLabel(GBDevice device, Context context) {
            int volume = getVolume(device);
            return String.valueOf(volume);
        }

        @Override
        public void onClick(GBDevice device, Context context) {
            int currentVolume = getVolume(device);

            LayoutInflater inflater = LayoutInflater.from(context);
            View view = inflater.inflate(R.layout.dialog_marshall_volume, null);

            SeekBar seekBar = view.findViewById(R.id.volume_seekbar);
            TextView volumeValue = view.findViewById(R.id.volume_value);

            seekBar.setMax(31);
            seekBar.setProgress(currentVolume);
            volumeValue.setText(String.valueOf(currentVolume));

            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    volumeValue.setText(String.valueOf(progress));
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle(R.string.marshall_pref_volume_title);
            builder.setView(view);
            builder.setPositiveButton(R.string.ok, (dialog, which) -> {
                int newVolume = seekBar.getProgress();

                SharedPreferences prefs = getPrefs(device);
                if (prefs != null) {
                    prefs.edit().putInt(PREF_MARSHALL_VOLUME, newVolume).apply();
                    GBApplication.deviceService(device).onSendConfiguration(PREF_MARSHALL_VOLUME);
                    device.sendDeviceUpdateIntent(context);
                }
            });
            builder.setNegativeButton(R.string.Cancel, null);
            builder.show();
        }

        private int getVolume(GBDevice device) {
            SharedPreferences prefs = getPrefs(device);
            return prefs != null ? prefs.getInt(PREF_MARSHALL_VOLUME, 15) : 15;
        }
    }
}
