/*  Copyright (C) 2026 The Gadgetbridge Project

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
package nodomain.freeyourgadget.gadgetbridge.devices.a10pro;

import androidx.annotation.NonNull;

import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettings;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryConfig;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.a10pro.A10ProSupport;

/**
 * Coordinator for FreeFit iEnjoy V2 earbuds and G2-ADV LCD charging cases.
 * The live-tested transport is BLE service 6E40FC00 with FC20 write and FC21 notify.
 */
public class A10ProCoordinator extends AbstractBLEDeviceCoordinator {
    private static final Pattern DEVICE_NAMES = Pattern.compile(
            "A10\\s*Pro|FreeFit|iEnjoy|ZWS\\s*Vibe|G2[-_ ]?ADV|G2ADV",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    protected Pattern getSupportedDeviceName() {
        return DEVICE_NAMES;
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return A10ProSupport.class;
    }

    @Override
    public String getManufacturer() {
        return "Shenzhen Zhongwei / FreeFit";
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_a10pro_earbuds;
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_NONE;
    }

    @Override
    public boolean suggestUnbindBeforePair() {
        return false;
    }

    @Override
    public BatteryConfig[] getBatteryConfig(final GBDevice device) {
        if (isCaseWithDisplay(device)) {
            return new BatteryConfig[]{
                    new BatteryConfig(0, GBDevice.BATTERY_ICON_DEFAULT, R.string.left_earbud, 15, 100),
                    new BatteryConfig(1, GBDevice.BATTERY_ICON_DEFAULT, R.string.right_earbud, 15, 100),
                    new BatteryConfig(2, GBDevice.BATTERY_ICON_DEFAULT, R.string.battery_case, 15, 100)
            };
        }
        return new BatteryConfig[]{
                new BatteryConfig(0, GBDevice.BATTERY_ICON_DEFAULT, R.string.left_earbud, 15, 100),
                new BatteryConfig(1, GBDevice.BATTERY_ICON_DEFAULT, R.string.right_earbud, 15, 100)
        };
    }

    private boolean isCaseWithDisplay(final GBDevice device) {
        final String name = device != null ? device.getName() : null;
        return name != null && Pattern.compile("G2[-_ ]?ADV|LCD|case", Pattern.CASE_INSENSITIVE).matcher(name).find();
    }

    @Override
    public int[] getSupportedDeviceSpecificSettings(final GBDevice device) {
        return new int[]{R.xml.devicesettings_a10pro};
    }

    @Override
    public DeviceSpecificSettings getDeviceSpecificSettings(final GBDevice device) {
        final DeviceSpecificSettings settings = new DeviceSpecificSettings();
        settings.addRootScreen(R.xml.devicesettings_headphones);
        settings.addRootScreen(R.xml.devicesettings_a10pro);
        settings.addConnectedPreferences(
                "pref_a10pro_anc_mode",
                "pref_a10pro_audio_model",
                "pref_a10pro_find_earphones",
                "pref_a10pro_anti_lost",
                "pref_a10pro_find_band",
                "pref_a10pro_metric_units",
                "pref_a10pro_volume_cap",
                "pref_a10pro_marquee",
                "pref_a10pro_factory_reset",
                "pref_a10pro_power_off"
        );
        return settings;
    }

    @Override
    public int getAlarmSlotCount(final GBDevice device) {
        return 5;
    }

    @Override
    public boolean supportsMusicInfo(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsWeather(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsFindDevice(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsPowerOff(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public DeviceCoordinator.DeviceKind getDeviceKind(@NonNull final GBDevice device) {
        return DeviceKind.HEADPHONES;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_headphones;
    }
}
