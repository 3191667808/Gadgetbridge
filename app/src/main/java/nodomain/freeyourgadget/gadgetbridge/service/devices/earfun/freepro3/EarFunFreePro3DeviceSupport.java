package nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.freepro3;

import nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.EarFunDeviceSupport;

/**
 * Device support implementation for EarFun Free Pro 3 (TW400)
 */
public class EarFunFreePro3DeviceSupport extends EarFunDeviceSupport {

    @Override
    protected EarFunFreePro3Protocol createDeviceProtocol() {
        return new EarFunFreePro3Protocol(getDevice());
    }
}
