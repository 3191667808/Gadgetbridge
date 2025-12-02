package nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.airpro3;

import nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.EarFunDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.serial.GBDeviceProtocol;

/**
 * Device support implementation for EarFun Air Pro 3 (TW500)
 */
public class EarFunAirPro3DeviceSupport extends EarFunDeviceSupport {

    @Override
    protected GBDeviceProtocol createDeviceProtocol() {
        return new EarFunAirPro3Protocol(getDevice());
    }
}
