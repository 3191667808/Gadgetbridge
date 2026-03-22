package nodomain.freeyourgadget.gadgetbridge.devices.teufel

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettings
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLClassicDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.teufel.TeufelMyndSupport
import java.util.regex.Pattern

class TeufelMyndCoordinator : AbstractBLClassicDeviceCoordinator() {
    override fun getManufacturer(): String {
        return "Teufel"
    }

    override fun getSupportedDeviceName(): Pattern? {
        return Pattern.compile("^MYND$")
    }

    override fun getDeviceNameResource(): Int {
        return R.string.devicetype_teufel_mynd
    }

    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind {
        return DeviceCoordinator.DeviceKind.SPEAKER
    }

    override fun getDeviceSupportClass(device: GBDevice): Class<out DeviceSupport?> {
        return TeufelMyndSupport::class.java
    }

    override fun suggestUnbindBeforePair(): Boolean {
        return false
    }

    override fun getDefaultIconResource(): Int {
        // TODO dedicated icon
        return R.drawable.ic_device_headphones
    }

    override fun getDeviceSpecificSettings(device: GBDevice): DeviceSpecificSettings {
        val settings = DeviceSpecificSettings()

        // TODO the rest of the settings

        settings.addRootScreen(DeviceSpecificSettingsScreen.CALLS_AND_NOTIFICATIONS)
        settings.addSubScreen(DeviceSpecificSettingsScreen.CALLS_AND_NOTIFICATIONS, R.xml.devicesettings_headphones)

        return settings
    }
}
