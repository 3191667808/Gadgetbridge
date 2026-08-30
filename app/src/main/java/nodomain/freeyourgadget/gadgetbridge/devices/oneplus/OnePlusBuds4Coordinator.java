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

import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLClassicDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryConfig;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.OnePlusBuds4Support;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4TouchConfigSide;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4TouchConfigType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oneplus.commands.OnePlusBuds4TouchConfigValue;

public class OnePlusBuds4Coordinator extends AbstractBLClassicDeviceCoordinator {
    private static final UUID SERVICE_UUID = UUID.fromString("0000079a-d102-11e1-9b23-00025b00a5a5");

    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("OnePlus Buds 4", Pattern.LITERAL);
    }

    @Override
    public boolean supports(@NonNull final GBDeviceCandidate candidate) {
        return super.supports(candidate) && candidate.supportsService(SERVICE_UUID);
    }

    @Override
    public String getManufacturer() {
        return "OnePlus";
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_oneplus_buds4;
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return OnePlusBuds4Support.class;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_nothingear;
    }

    @Override
    public int getBatteryCount(final GBDevice device) {
        return 3;
    }

    @Override
    public BatteryConfig[] getBatteryConfig(final GBDevice device) {
        return new BatteryConfig[]{
                new BatteryConfig(0, R.drawable.ic_nothing_ear_l, R.string.left_earbud),
                new BatteryConfig(1, R.drawable.ic_nothing_ear_r, R.string.right_earbud),
                new BatteryConfig(2, R.drawable.ic_tws_case, R.string.battery_case)
        };
    }

    @Override
    @Nullable
    public DeviceSettingsSpec getDeviceSettings(@NonNull final GBDevice device) {
        return OnePlusBuds4DeviceSettingsKt.onePlusBuds4DeviceSettings(device);
    }

    @Override
    public DeviceSpecificSettingsCustomizer getDeviceSpecificSettingsCustomizer(final GBDevice device) {
        return new OnePlusBuds4SettingsCustomizer(getTouchOptions());
    }

    private static Map<Pair<OnePlusBuds4TouchConfigSide, OnePlusBuds4TouchConfigType>, List<OnePlusBuds4TouchConfigValue>> getTouchOptions() {
        final Map<Pair<OnePlusBuds4TouchConfigSide, OnePlusBuds4TouchConfigType>, List<OnePlusBuds4TouchConfigValue>> map = new LinkedHashMap<>();
        final List<OnePlusBuds4TouchConfigValue> tapOptions = List.of(
                OnePlusBuds4TouchConfigValue.OFF,
                OnePlusBuds4TouchConfigValue.PLAY_PAUSE,
                OnePlusBuds4TouchConfigValue.PREVIOUS,
                OnePlusBuds4TouchConfigValue.NEXT,
                OnePlusBuds4TouchConfigValue.VOICE_ASSISTANT,
                OnePlusBuds4TouchConfigValue.GAME_MODE
        );
        final List<OnePlusBuds4TouchConfigValue> slideOptions = List.of(
                OnePlusBuds4TouchConfigValue.OFF,
                OnePlusBuds4TouchConfigValue.VOLUME_CONTROL,
                OnePlusBuds4TouchConfigValue.SWITCH_TRACK
        );
        for (final OnePlusBuds4TouchConfigSide side : List.of(OnePlusBuds4TouchConfigSide.LEFT, OnePlusBuds4TouchConfigSide.RIGHT)) {
            map.put(Pair.create(side, OnePlusBuds4TouchConfigType.SINGLE_TAP), tapOptions);
            map.put(Pair.create(side, OnePlusBuds4TouchConfigType.DOUBLE_TAP), tapOptions);
            map.put(Pair.create(side, OnePlusBuds4TouchConfigType.TRIPLE_TAP), tapOptions);
            map.put(Pair.create(side, OnePlusBuds4TouchConfigType.SLIDE), slideOptions);
        }
        map.put(Pair.create(OnePlusBuds4TouchConfigSide.BOTH, OnePlusBuds4TouchConfigType.ON_CALL_DOUBLE_TAP), List.of(
                OnePlusBuds4TouchConfigValue.OFF,
                OnePlusBuds4TouchConfigValue.ANSWER_END_CALL
        ));
        map.put(Pair.create(OnePlusBuds4TouchConfigSide.BOTH, OnePlusBuds4TouchConfigType.ON_CALL_HOLD), List.of(
                OnePlusBuds4TouchConfigValue.OFF,
                OnePlusBuds4TouchConfigValue.DECLINE_CALL
        ));
        return map;
    }

    @Override
    public boolean supportsFindDevice(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public final DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return DeviceKind.EARBUDS;
    }
}
