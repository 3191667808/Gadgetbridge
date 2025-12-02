package nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.freepro3;

import static nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.EarFunPacketEncoder.joinPackets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.EarFunPacket;
import nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.EarFunPacketEncoder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.EarFunProtocol;
import nodomain.freeyourgadget.gadgetbridge.service.devices.earfun.prefs.Equalizer;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

/**
 * Protocol implementation for EarFun Free Pro 3 (TW400)
 */
public class EarFunFreePro3Protocol extends EarFunProtocol {

    private static final Logger LOG = LoggerFactory.getLogger(EarFunFreePro3Protocol.class);

    @Override
    public byte[] encodeTestNewFunction() {
        return joinPackets(
                // new EarFunPacket(EarFunPacket.Command.COMMAND_REBOOT).encode()
        );
    }

    @Override
    public byte[] encodeSendConfiguration(String config) {
        if (Equalizer.containsKey(Equalizer.TenBandEqualizer, config)) {
            Prefs prefs = getDevicePrefs();
            return EarFunPacketEncoder.encodeSetEqualizerTenBands(prefs);
        }

        return super.encodeSendConfiguration(config);
    }


    @Override
    public byte[] encodeSettingsReq() {
        return EarFunPacketEncoder.encodeAirPro4SettingsReq();
    }

    protected EarFunFreePro3Protocol(GBDevice device) {
        super(device);
        DeviceType type = device.getType();
        LOG.debug("Initialized EarFunFreePro3Protocol for device: {}", type);
    }
}
