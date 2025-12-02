package nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.freepro3;

import nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.EarFunDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.serial.GBDeviceProtocol;

/**
 * Device support implementation for EarFun Free Pro 3 (TW400)
 */
public class EarFunFreePro3DeviceSupport extends EarFunDeviceSupport {

    @Override
    protected GBDeviceProtocol createDeviceProtocol() {
        return new EarFunFreePro3Protocol(getDevice());
    }
}
