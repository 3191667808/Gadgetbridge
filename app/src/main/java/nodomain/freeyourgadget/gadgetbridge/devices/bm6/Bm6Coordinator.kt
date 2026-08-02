package nodomain.freeyourgadget.gadgetbridge.devices.bm6

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.GenericTemperatureSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.bm6.Bm6Constants
import nodomain.freeyourgadget.gadgetbridge.service.devices.bm6.Bm6Support
import java.util.regex.Pattern


class Bm6Coordinator : AbstractBLEDeviceCoordinator() {
    override fun isExperimental(): Boolean {
        // #6236 - Untested
        return true
    }

    override fun getSupportedDeviceName(): Pattern {
        return Pattern.compile("^BM6$")
    }

    override fun getManufacturer(): String {
        return "BM6"
    }

    override fun getDeviceSupportClass(device: GBDevice): Class<out DeviceSupport> {
        return Bm6Support::class.java
    }

    override fun getDeviceNameResource(): Int {
        return R.string.devicetype_battery_monitor
    }

    override fun suggestUnbindBeforePair(): Boolean {
        return false
    }

    override fun getBondingStyle(): Int {
        return BONDING_STYLE_NONE
    }

    override fun getDefaultIconResource(): Int {
        return R.drawable.ic_device_car
    }

    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind {
        return DeviceCoordinator.DeviceKind.BATTERY_MONITOR
    }

    override fun getTemperatureSampleProvider(
        device: GBDevice,
        session: DaoSession
    ): TimeSampleProvider<out TemperatureSample?> {
        return GenericTemperatureSampleProvider(device, session)
    }

    override fun supportsTemperatureMeasurement(device: GBDevice): Boolean {
        return true
    }

    override fun supportsContinuousTemperature(device: GBDevice): Boolean {
        return true
    }

    override fun getDeviceSettings(device: GBDevice): DeviceSettingsSpec = deviceSettings {
        list(
            key = Bm6Constants.PREF_BATTERY_TYPE,
            title = R.string.prefs_bm6_battery_type_title,
            icon = R.drawable.ic_battery_profile,
            entriesRes = R.array.bm6_battery_type_entries,
            entryValuesRes = R.array.bm6_battery_type_values,
            defaultValue = Bm6Constants.DEFAULT_BATTERY_TYPE_VALUE
        )
    }
}
