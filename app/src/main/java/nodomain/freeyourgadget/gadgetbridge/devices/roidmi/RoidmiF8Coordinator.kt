/*  Copyright (C) 2026 David Girón

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
package nodomain.freeyourgadget.gadgetbridge.devices.roidmi

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.ListEntry
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCardAction
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.deviceCardAction
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.roidmi.RoidmiF8Support
import java.util.Locale
import java.util.regex.Pattern

/**
 * Coordinator for the Roidmi F8 Cordless Vacuum Cleaner (model XCQ03RM).
 *
 * The device advertises via Xiaomi MiBeacon (service UUID 0xFE95, device ID 0x0248).
 * Battery pack voltage is read from characteristic 0xFFD2 and
 * power state / battery percentage from characteristic 0xFFD8.
 */
class RoidmiF8Coordinator : AbstractBLEDeviceCoordinator() {

    override fun getManufacturer(): String = "ROIDMI"

    // The F8 model advertises as ROIDMI Cleaner F1
    override fun getSupportedDeviceName(): Pattern = Pattern.compile("ROIDMI Cleaner F1")

    override fun getBatteryCount(device: GBDevice): Int = 1

    override fun getBondingStyle(): Int = DeviceCoordinator.BONDING_STYLE_NONE

    /**
     * The Roidmi F8 follows the Xiaomi / miio standard: it must be authenticated with the
     * device token before it accepts any command, otherwise it drops the connection.
     * The token is requested during pairing via `AuthKeyActivity` and stored under
     * [DeviceSettingsPreferenceConst.PREF_AUTH_KEY].
     */
    override fun requiresAuthKey(): Boolean = true

    /**
     * Validates the user-provided device token. Accepts a hexadecimal string (optionally
     * prefixed with `0x`) with an even number of digits, up to 32 hex characters (16 bytes).
     */
    override fun validateAuthKey(authKey: String): Boolean {
        var hex = authKey.trim()
        if (hex.startsWith("0x")) {
            hex = hex.substring(2)
        }
        return hex.isNotEmpty() &&
            hex.length % 2 == 0 &&
            hex.length <= 32 &&
            hex.matches(Regex("[0-9a-fA-F]+"))
    }

    override fun getSupportedDeviceSpecificAuthenticationSettings(): IntArray =
        intArrayOf(R.xml.devicesettings_roidmi_f8_pairingkey)

    override fun getDeviceSupportClass(device: GBDevice): Class<out DeviceSupport> =
        RoidmiF8Support::class.java

    override fun getDeviceNameResource(): Int = R.string.devicetype_roidmi_f8

    override fun getDefaultIconResource(): Int = R.drawable.ic_device_roidmi

    override fun getDeviceSettings(device: GBDevice): DeviceSettingsSpec = deviceSettings {
        list(
            key = DeviceSettingsPreferenceConst.PREF_ROIDMI_F8_STANDARD_GEAR,
            title = R.string.pref_roidmi_f8_standard_gear_title,
            icon = R.drawable.ic_mode_fan,
            entries = listOf(
                ListEntry.Text("0", "80 W"),
                ListEntry.Text("1", "130 W"),
                ListEntry.Text("2", "180 W"),
            ),
            defaultValue = "0",
        )
        switchSetting(
            key = DeviceSettingsPreferenceConst.PREF_ROIDMI_F8_DUST_REMINDER,
            title = R.string.pref_roidmi_f8_dust_reminder_title,
            summary = R.string.pref_roidmi_f8_dust_reminder_summary,
            icon = R.drawable.ic_notifications,
            defaultValue = true,
        )
        action(
            key = DeviceSettingsPreferenceConst.PREF_ROIDMI_F8_RESET_FILTER,
            title = R.string.pref_roidmi_f8_reset_filter_title,
            icon = R.drawable.ic_filter_alt,
            confirmationMessage = R.string.pref_roidmi_f8_reset_filter_confirm,
        ) { handler ->
            handler.notifyPreferenceChanged(DeviceSettingsPreferenceConst.PREF_ROIDMI_F8_RESET_FILTER)
            true
        }
    }

    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind =
        DeviceCoordinator.DeviceKind.UNKNOWN

    /**
     * Extra live readings shown on the device card:
     * - motor / charge current (only while it is drawing more than [MIN_DISPLAY_CURRENT_AMPS])
     * - pack temperature
     */
    override fun getCustomActions(): List<DeviceCardAction> = listOf(
        deviceCardAction {
            icon = { R.drawable.ic_bolt }
            isVisible = { device ->
                device.isConnected &&
                    (device.getExtraInfo(RoidmiF8Support.EXTRA_CURRENT_AMPS) as? Float ?: 0f) > MIN_DISPLAY_CURRENT_AMPS
            }
            description = { _, context -> context.getString(R.string.electrical_current) }
            label = { device, _ ->
                String.format(Locale.getDefault(), "%.2f A", device.getExtraInfo(RoidmiF8Support.EXTRA_CURRENT_AMPS) as? Float ?: 0f)
            }
            onClick = { _, _ -> }
        },
        deviceCardAction {
            icon = { R.drawable.ic_temperature }
            isVisible = { device ->
                device.isConnected && device.getExtraInfo(RoidmiF8Support.EXTRA_TEMPERATURE_CELSIUS) is Float
            }
            description = { _, context -> context.getString(R.string.menuitem_temperature) }
            label = { device, _ ->
                String.format(Locale.getDefault(), "%.1f °C", device.getExtraInfo(RoidmiF8Support.EXTRA_TEMPERATURE_CELSIUS) as? Float ?: 0f)
            }
            onClick = { _, _ -> }
        },
    )

    companion object {
        /** Hide the current reading when the vacuum is idle (draws essentially nothing). */
        private const val MIN_DISPLAY_CURRENT_AMPS = 0.05f
    }
}
