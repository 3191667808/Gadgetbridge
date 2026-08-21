package nodomain.freeyourgadget.gadgetbridge.devices.dji.rc

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate

class DjiRcN1cUsbCoordinator : AbstractDjiRcUsbCoordinator() {
    override fun supports(candidate: GBDeviceCandidate): Boolean {
        return super.supports(candidate) &&
                candidate.accessory?.description == "DJI RC-N1C"
    }

    override fun getDeviceNameResource(): Int {
        return R.string.devicetype_dji_rc_n1c
    }
}
