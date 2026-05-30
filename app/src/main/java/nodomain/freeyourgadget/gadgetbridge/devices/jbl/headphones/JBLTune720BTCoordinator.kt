/*  Copyright (C) 2025 hemisputnik (https://512b.dev/)

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
package nodomain.freeyourgadget.gadgetbridge.devices.jbl.headphones

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.devices.jbl.headphones.JBLHeadphoneSettingsCustomizer
import nodomain.freeyourgadget.gadgetbridge.service.devices.jbl.headphones.Tune720BTSupport
import java.util.regex.Pattern

class JBLTune720BTCoordinator : AbstractBLEDeviceCoordinator() {
    override fun getSupportedDeviceName(): Pattern =
        Pattern.compile(".*JBL Tune720BT.*")

    override fun getManufacturer() = "JBL"
    override fun getDeviceNameResource() = R.string.devicetype_jbl_tune_720bt
    override fun getDefaultIconResource() = R.drawable.ic_device_headphones
    override fun getDeviceKind(device: GBDevice) = DeviceCoordinator.DeviceKind.HEADPHONES
    override fun getDeviceSupportClass(device: GBDevice) = Tune720BTSupport::class.java

    override fun supportsPowerOff(device: GBDevice) = true

    override fun getDeviceSpecificSettingsCustomizer(device: GBDevice) =
        JBLHeadphoneSettingsCustomizer()
    override fun getSupportedDeviceSpecificSettings(device: GBDevice) =
        intArrayOf(
            R.xml.devicesettings_jbl_voiceaware
        )

    override fun getBondingStyle() = BONDING_STYLE_ASK
}
