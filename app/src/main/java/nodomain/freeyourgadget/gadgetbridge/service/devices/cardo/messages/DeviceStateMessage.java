package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.messages;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdateDeviceInfo;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums.CardoCallDirection;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums.CardoCallState;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums.CardoDmcGroupState;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums.CardoFmState;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.enums.CardoState;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils.ByteUtils;

public class DeviceStateMessage {
    private static final Logger LOG = LoggerFactory.getLogger(DeviceStateMessage.class);

    public static List<GBDeviceEvent> decodeMessage(byte[] payload, Map<ByteUtils.CardoField, Object> deviceStatus) {
        final List<GBDeviceEvent> deviceEvents = new ArrayList<>();
        List<ByteUtils.CardoField> fields = Arrays.asList(
                new ByteUtils.CardoField("state", 8, value -> CardoState.values()[(int) value]),
                new ByteUtils.CardoField("callState", 2, value -> CardoCallState.values()[(int) value]),
                new ByteUtils.CardoField("callDirection", 6, value -> CardoCallDirection.values()[(int) value]),
                new ByteUtils.CardoField("fmState", 4, value -> CardoFmState.values()[(int) value]),
                new ByteUtils.CardoField("currentSelectedIndex", 4),
                new ByteUtils.CardoField("currentStation", 16),
                new ByteUtils.CardoField("dmcGroupState", 2, value -> CardoDmcGroupState.values()[(int) value])
        );
        deviceStatus.putAll(ByteUtils.parseStructure(payload, fields));
        Map<ByteUtils.CardoField, Object> result = ByteUtils.parseStructure(payload, fields);
        LOG.debug("Status: {}", result);
        result.forEach((cardoField, o) -> deviceEvents.add(new GBDeviceEventUpdateDeviceInfo(cardoField.getName(), o.toString())));
        return deviceEvents;
    }
}
