package nodomain.freeyourgadget.gadgetbridge.service.devices.cardo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.messages.BatteryMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.messages.ConfigMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.messages.DeviceAliasMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.messages.DeviceSerialNumberMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.messages.DeviceStateMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.messages.SubscribeMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils.ByteUtils;
import nodomain.freeyourgadget.gadgetbridge.service.devices.cardo.utils.CardoMap;

public class CardoResponse {
    private static final Logger LOG = LoggerFactory.getLogger(CardoResponse.class);

    private final CardoMessage message;
    private final List<GBDeviceEvent> deviceEvents = new ArrayList<>();

    private final CardoMap<ByteUtils.CardoField, Object> deviceStatus = new CardoMap<>();

    CardoResponse(byte[] incoming) {
        message = CardoMessage.getByCommand(incoming[0]);
        if (null == message)
            return;

        byte[] payload = message.hasLength ? Arrays.copyOfRange(incoming, 2, 2 + incoming[1]) :
                Arrays.copyOfRange(incoming, 1, incoming.length);

        switch (message) {
            case CONFIG:
                deviceEvents.addAll(ConfigMessage.decodeMessage(payload, getDeviceStatus()));
                break;
            case SUBSCRIBE:
                SubscribeMessage.decodeMessage(payload, getDeviceStatus());
                break;
            case BATTERY_STATUS:
                deviceEvents.addAll(BatteryMessage.decodeMessage(payload));
                break;
            case DEVICE_STATE:
                deviceEvents.addAll(DeviceStateMessage.decodeMessage(payload, getDeviceStatus()));
                break;
            case DEVICE_ALIAS:
                final DeviceAliasMessage deviceAliasMessage = new DeviceAliasMessage(payload);
                deviceStatus.putAll(deviceAliasMessage.getDeviceStatus());
                break;
            case DEVICE_SERIAL_NUMBER:
                final DeviceSerialNumberMessage deviceSerialNumberMessage = new DeviceSerialNumberMessage(payload);
                deviceStatus.putAll(deviceSerialNumberMessage.getDeviceStatus());
                break;

            default:
                LOG.debug("INCOMING MESSAGE TYPE {} PAYLOAD {}", message, incoming);
        }
    }

    public CardoMap<ByteUtils.CardoField, Object> getDeviceStatus() {
        return deviceStatus;
    }

    public List<GBDeviceEvent> getDeviceEvents() {
        return deviceEvents;
    }
}
