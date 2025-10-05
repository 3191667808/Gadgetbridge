package nodomain.freeyourgadget.gadgetbridge.devices.jbl.headphones

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.devices.jbl.headphones.Tune720BTSupport
import java.util.regex.Pattern

class JBLTune720BTCoordinator : AbstractBLEDeviceCoordinator() {
    override fun getSupportedDeviceName(): Pattern =
        Pattern.compile(".*JBL Tune720BT.*")

    override fun getManufacturer() = "JBL"
    override fun getDeviceNameResource() = R.string.devicetype_jbl_tune_720bt
    override fun getDefaultIconResource() = R.drawable.ic_device_headphones
    override fun getDeviceSupportClass(device: GBDevice?) = Tune720BTSupport::class.java

    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind = DeviceCoordinator.DeviceKind.HEADPHONES

    override fun supportsPowerOff(device: GBDevice) = true

    override fun getBondingStyle() = BONDING_STYLE_ASK
}
