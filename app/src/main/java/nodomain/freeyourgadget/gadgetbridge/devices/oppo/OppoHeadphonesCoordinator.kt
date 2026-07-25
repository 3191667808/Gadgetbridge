/*  Copyright (C) 2024 José Rebelo
    Copyright (C) 2026 NTeditor

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
package nodomain.freeyourgadget.gadgetbridge.devices.oppo

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.util.Pair
import java.nio.ByteOrder
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettings
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.components.enumList
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.components.multipointPairing
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLClassicDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate
import nodomain.freeyourgadget.gadgetbridge.model.BatteryConfig
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.OppoHeadphonesSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.TouchConfigSide
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.TouchConfigType
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.TouchConfigValue
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.AncConfigValue

abstract class OppoHeadphonesCoordinator : AbstractBLClassicDeviceCoordinator() {
    override fun getManufacturer(): String = "Oppo"

    override fun getDeviceSupportClass(device: GBDevice): Class<out DeviceSupport> =
        OppoHeadphonesSupport::class.java

    override fun getDefaultIconResource(): Int = R.drawable.ic_device_nothingear

    override fun getBatteryCount(device: GBDevice): Int = 3

    override fun supports(candidate: GBDeviceCandidate): Boolean {
        if (!super.supports(candidate)) return false
        val majorDeviceClass = candidate.device?.bluetoothClass?.majorDeviceClass
        return majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
    }

    override fun getBatteryConfig(device: GBDevice): Array<BatteryConfig> = arrayOf(
        BatteryConfig(0, R.drawable.ic_nothing_ear_l, R.string.left_earbud),
        BatteryConfig(1, R.drawable.ic_nothing_ear_r, R.string.right_earbud),
        BatteryConfig(2, R.drawable.ic_tws_case, R.string.battery_case)
    )

    protected abstract val touchOptions: Map<Pair<TouchConfigSide, TouchConfigType>, List<TouchConfigValue>>
    public final fun supportsTouchAncCycleModes(): Boolean = touchOptions.values.any { values ->
        values.contains(TouchConfigValue.ANC_CYCLE_MODES)
    }

    override fun getDeviceSettings(device: GBDevice): DeviceSettingsSpec = deviceSettings {
        if (supportsAnc(device)) {
            enumList<AncConfigValue>(
                key = OppoHeadphonesPreferences.ANC_MODE,
                title = R.string.prefs_noise_control,
                icon = R.drawable.ic_surround,
                defaultValue = AncConfigValue.OFF,
            )
            if (supportsAncLevel(device)) {
                enumList<AncConfigValue.Level>(
                    key = OppoHeadphonesPreferences.ANC_LEVEL,
                    title = R.string.prefs_active_noise_cancelling_level,
                    defaultValue = AncConfigValue.Level.HIGH,
                    dependency = OppoHeadphonesPreferences.ANC_MODE,
                    visibleWhen = {
                        val modePreference = it.getString(OppoHeadphonesPreferences.ANC_MODE, null)
                        val mode = AncConfigValue.fromPreference(modePreference)
                        mode == AncConfigValue.ON
                    }
                )
            }
        }
        if (supportsGameMode(device)) {
            switchSetting(
                key = OppoHeadphonesPreferences.GAME_MODE,
                title = R.string.prefs_game_mode,
                icon = R.drawable.ic_videogame,
            )
        }
        xmlScreen(
            DeviceSpecificSettingsScreen.TOUCH_OPTIONS,
            R.xml.devicesettings_oppo_headphones_touch_options,
            connectedOnly = true,
        )
        if (supportsLdac(device) || supportsSpatialAudio(device)) {
            screen(
                key = DeviceSpecificSettingsScreen.AUDIO.getKey(),
                title = R.string.pref_header_audio,
                icon = R.drawable.ic_music_note,
            ) {
                if (supportsLdac(device)) {
                    switchSetting(
                        key = OppoHeadphonesPreferences.LDAC,
                        title = R.string.soundcore_ldac_mode_title,
                        summary = R.string.soundcore_ldac_mode_summary,
                        icon = R.drawable.ic_music_note,
                    )
                }
                if (supportsSpatialAudio(device)) {
                    switchSetting(
                        key = OppoHeadphonesPreferences.SPATIAL_AUDIO,
                        title = R.string.nothing_prefs_spatial_audio_title,
                        icon = R.drawable.ic_surround,
                    )
                }
            }
        }
        if (supportsMultipoint(device)) {
            multipointPairing()
        }
        xmlScreen(
            DeviceSpecificSettingsScreen.CALLS_AND_NOTIFICATIONS,
            R.xml.devicesettings_headphones,
        )
    }

    override fun getDeviceSpecificSettingsCustomizer(device: GBDevice): DeviceSpecificSettingsCustomizer =
        OppoHeadphonesSettingsCustomizer(touchOptions, supportsFindPhone(device))


    final override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind =
        DeviceCoordinator.DeviceKind.EARBUDS

    open fun supportsLdac(device: GBDevice): Boolean = false
    open fun supportsMultipoint(device: GBDevice): Boolean = false
    open fun multipointMacOrder(device: GBDevice): ByteOrder = ByteOrder.BIG_ENDIAN
    open fun supportsGameMode(device: GBDevice): Boolean = false
    open fun supportsAnc(device: GBDevice): Boolean = false
    open fun supportsAncLevel(device: GBDevice): Boolean = false
    open fun supportsSpatialAudio(device: GBDevice): Boolean = false
    open fun supportsFindPhone(device: GBDevice): Boolean = false
    open fun useStandardSppUuid(device: GBDevice): Boolean = false
}

