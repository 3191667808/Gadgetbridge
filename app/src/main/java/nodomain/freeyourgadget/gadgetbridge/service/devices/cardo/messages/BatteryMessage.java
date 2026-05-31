package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.messages;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils.ByteUtils;

public class BatteryMessage {
    static final ByteUtils.CardoField chargeStatus = new ByteUtils.CardoField("charging", 1);
    static final ByteUtils.CardoField batteryCharge = new ByteUtils.CardoField("batteryCharge", 7);
    private static final Logger LOG = LoggerFactory.getLogger(BatteryMessage.class);

    public static List<GBDeviceEvent> decodeMessage(byte[] payload) {
        final List<GBDeviceEvent> deviceEvents = new ArrayList<>();
        List<ByteUtils.CardoField> fields = Arrays.asList(
                chargeStatus,
                batteryCharge
        );

        Map<ByteUtils.CardoField, Object> result = ByteUtils.parseStructure(payload, fields);

        LOG.debug("Battery: {}", result);
        final GBDeviceEventBatteryInfo gbDeviceEventBatteryInfo = new GBDeviceEventBatteryInfo();
        gbDeviceEventBatteryInfo.level = (int) result.get(batteryCharge);
        gbDeviceEventBatteryInfo.state = (boolean) result.get(chargeStatus) ? BatteryState.BATTERY_CHARGING : BatteryState.BATTERY_NORMAL;

        deviceEvents.add(gbDeviceEventBatteryInfo);
        return deviceEvents;
    }
}
