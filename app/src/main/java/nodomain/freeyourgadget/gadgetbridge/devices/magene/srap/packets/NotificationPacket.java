package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.FunctionCode;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.PageNumber;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.ResourceType;

/**
 * A container for messages related to the NOTIFICATION (0x44) page.
 */
public final class NotificationPacket {

    private NotificationPacket() {
        // This class is a namespace and should not be instantiated.
    }

    /**
     * Represents a WRITE command for sending a Notification (f2:44).
     */
    public static class Write extends AbstractSRAPMessage {
        private final byte nodeAddress;
        private final ResourceType resourceType;
        private final byte[] payload;

        public Write(byte nodeAddress, ResourceType resourceType, byte notificationType, byte notificationOrigin, String sender, String message) {
            this.nodeAddress = nodeAddress;
            this.resourceType = resourceType;
            this.payload = buildPayload(notificationType, notificationOrigin, sender, message);
        }

        private byte[] buildPayload(byte notificationType, byte notificationOrigin, String sender, String message) {
            byte[] senderBytes = sender.getBytes(StandardCharsets.UTF_8);
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

            if (senderBytes.length > 255 || messageBytes.length > 255) {
                throw new IllegalArgumentException("Sender or message string is too long (max 255 bytes).");
            }

            try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
                stream.write(notificationType);
                stream.write(notificationOrigin);
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
        @Override public ResourceType getResourceType() { return resourceType; }
        @Override public PageNumber getPageNumber() { return PageNumber.NOTIFICATION; }
        @Override public byte[] getPayload() { return payload; }
    }
}


