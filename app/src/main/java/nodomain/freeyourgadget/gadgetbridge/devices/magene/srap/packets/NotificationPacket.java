package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.FunctionCode;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.PageNumber;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.ResourceType;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;

/**
 * A container for messages related to the NOTIFICATION (0x44) page.
 */
public final class NotificationPacket {

    private NotificationPacket() {
        // This class is a namespace and should not be instantiated.
    }


    public enum NotificationType {
        CALL(1),
        MESSAGE(2),
        UNKNOWN(-1);

        private final int value;
        private static final Map<Integer, NotificationType> map = new HashMap<>();

        NotificationType(int value) {
            this.value = value;
        }

        static {
            for (NotificationType type : NotificationType.values()) {
                map.put(type.value, type);
            }
        }

        public static NotificationType fromValue(int value) {
            return map.getOrDefault(value, UNKNOWN);
        }

        public int getValue() {
            return value;
        }
    }

    public enum PhoneState {
        INCOMING(1),
        HANGUP(2),
        UNKNOWN(-1);

        private final int value;
        private static final Map<Integer, PhoneState> map = new HashMap<>();

        PhoneState(int value) {
            this.value = value;
        }

        static {
            for (PhoneState type : PhoneState.values()) {
                map.put(type.value, type);
            }
        }

        public static PhoneState fromValue(int value) {
            return map.getOrDefault(value, UNKNOWN);
        }

        public int getValue() {
            return value;
        }
    }

    public enum NotificationOrigin {
        PHONE(0),
        SMS(1),
        QQ(2),
        WECHAT(3),
        OTHER(4),
        SKYPE(5),
        WHATSAPP(6),
        EMAIL(7),
        LINE(8),
        UNKNOWN(-1);

        private final int value;
        private static final Map<Integer, NotificationOrigin> map = new HashMap<>();

        NotificationOrigin(int value) {
            this.value = value;
        }

        static {
            for (NotificationOrigin origin : NotificationOrigin.values()) {
                map.put(origin.value, origin);
            }
        }

        public static NotificationOrigin fromValue(int value) {
            return map.getOrDefault(value, UNKNOWN);
        }

        public int getValue() {
            return value;
        }
    }

    /**
     * Represents a WRITE command for sending a Notification (f2:44).
     */
    public static class Write extends AbstractSRAPMessage {
        private final byte nodeAddress;
        private final byte[] payload;

        public Write(byte nodeAddress, NotificationType notificationType, NotificationOrigin notificationOrigin, NotificationSpec notificationSpec) {
            this.nodeAddress = nodeAddress;
            this.payload = buildPayload(notificationType, notificationOrigin, notificationSpec);
        }

        private byte[] buildPayload(NotificationType notificationType, NotificationOrigin notificationOrigin, NotificationSpec notificationSpec) {
            String title;

            if (notificationSpec.title != null)
                title = notificationSpec.title;
            else
                title = notificationSpec.sourceName;

            String body;
            body = notificationSpec.body; // TODO: Add constraints for body length

            byte[] senderBytes = title.getBytes(StandardCharsets.UTF_8);
            byte[] messageBytes = body.getBytes(StandardCharsets.UTF_8);

            if (senderBytes.length > 255 || messageBytes.length > 255) {
                throw new IllegalArgumentException("Sender or message string is too long (max 255 bytes).");
            }

            try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
                stream.write(notificationType.getValue());
                stream.write(notificationOrigin.getValue());
                stream.write((byte) senderBytes.length);
                stream.write(senderBytes);
                stream.write((byte) messageBytes.length);
                stream.write(messageBytes);
                return stream.toByteArray();
            } catch (IOException e) {
                // This should not happen with ByteArrayOutputStream
                throw new RuntimeException("Failed to build notification payload", e);
            }
        }

        @Override public byte getNodeAddress() { return nodeAddress; }
        @Override public FunctionCode getFunctionCode() { return FunctionCode.WRITE; }
        @Override public ResourceType getResourceType() { return ResourceType.MAIN; }
        @Override public PageNumber getPageNumber() { return PageNumber.NOTIFICATION; }
        @Override public byte[] getPayload() { return payload; }
    }
}


