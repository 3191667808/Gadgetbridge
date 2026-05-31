package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

import nodomain.freeyourgadget.gadgetbridge.devices.magene.FileType;

public class NotificationsConfig extends ConfigFile {

    private static final int NOTIFICATIONS_CONFIG_PAYLOAD_SIZE = 64;
    private static final int PADDING_SIZE = 54;
    private static final FileType type = FileType.NOTIFICATION_CONFIG;
    private static final String filename = "NotifyConfig.bin";


    private final boolean notificationsEnabled;
    private final boolean phoneEnabled;
    private final boolean smsEnabled;
    private final boolean wechatEnabled;
    private final boolean qqEnabled;
    private final boolean otherEnabled;
    private final boolean skypeEnabled;
    private final boolean whatsappEnabled;
    private final boolean emailEnabled;
    private final boolean lineEnabled;

    public NotificationsConfig(boolean notificationsEnabled, boolean phoneEnabled, boolean smsEnabled,
                               boolean wechatEnabled, boolean qqEnabled, boolean otherEnabled,
                               boolean skypeEnabled, boolean whatsappEnabled, boolean emailEnabled,
                               boolean lineEnabled) {
        super(filename, type);
        this.notificationsEnabled = notificationsEnabled;
        this.phoneEnabled = phoneEnabled;
        this.smsEnabled = smsEnabled;
        this.wechatEnabled = wechatEnabled;
        this.qqEnabled = qqEnabled;
        this.otherEnabled = otherEnabled;
        this.skypeEnabled = skypeEnabled;
        this.whatsappEnabled = whatsappEnabled;
        this.emailEnabled = emailEnabled;
        this.lineEnabled = lineEnabled;
    }

    private NotificationsConfig(byte notificationsEnabledByte, byte phoneEnabledByte, byte smsEnabledByte,
                                byte wechatEnabledByte, byte qqEnabledByte, byte otherEnabledByte,
                                byte skypeEnabledByte, byte whatsappEnabledByte, byte emailEnabledByte,
                                byte lineEnabledByte) {
        super(filename, type);
        this.notificationsEnabled = notificationsEnabledByte != 0;
        this.phoneEnabled = phoneEnabledByte != 0;
        this.smsEnabled = smsEnabledByte != 0;
        this.wechatEnabled = wechatEnabledByte != 0;
        this.qqEnabled = qqEnabledByte != 0;
        this.otherEnabled = otherEnabledByte != 0;
        this.skypeEnabled = skypeEnabledByte != 0;
        this.whatsappEnabled = whatsappEnabledByte != 0;
        this.emailEnabled = emailEnabledByte != 0;
        this.lineEnabled = lineEnabledByte != 0;
    }

    @Override
    public int getSpecificDataPayloadSize() {
        return NOTIFICATIONS_CONFIG_PAYLOAD_SIZE;
    }

    @Override
    protected void writeSpecificDataPayload(ByteBuffer buffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) (notificationsEnabled ? 1 : 0));
        buffer.put((byte) (phoneEnabled ? 1 : 0));
        buffer.put((byte) (smsEnabled ? 1 : 0));
        buffer.put((byte) (wechatEnabled ? 1 : 0));
        buffer.put((byte) (qqEnabled ? 1 : 0));
        buffer.put((byte) (otherEnabled ? 1 : 0));
        buffer.put((byte) (skypeEnabled ? 1 : 0));
        buffer.put((byte) (whatsappEnabled ? 1 : 0));
        buffer.put((byte) (emailEnabled ? 1 : 0));
        buffer.put((byte) (lineEnabled ? 1 : 0));
        buffer.put(new byte[PADDING_SIZE]); // Padding
    }

    public static NotificationsConfig fromBytes(byte[] buffer, String filename, FileType type) throws IOException {
        if (buffer.length != NOTIFICATIONS_CONFIG_PAYLOAD_SIZE + ConfigFile.MAGIC_SIZE) {
            throw new IOException("Buffer length is incorrect for NotificationsConfig. Expected " +
                    (NOTIFICATIONS_CONFIG_PAYLOAD_SIZE + ConfigFile.MAGIC_SIZE) + ", got " + buffer.length);
        }

        ByteBuffer bb = ByteBuffer.wrap(buffer);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        byte notificationsEnabledByte = bb.get();
        byte phoneEnabledByte = bb.get();
        byte smsEnabledByte = bb.get();
        byte wechatEnabledByte = bb.get();
        byte qqEnabledByte = bb.get();
        byte otherEnabledByte = bb.get();
        byte skypeEnabledByte = bb.get();
        byte whatsappEnabledByte = bb.get();
        byte emailEnabledByte = bb.get();
        byte lineEnabledByte = bb.get();

        byte[] padding = new byte[PADDING_SIZE];
        bb.get(padding); // Read and discard padding

        // At this point, bb is positioned at the start of the magic bytes
        readAndValidateMagic(bb);

        return new NotificationsConfig(notificationsEnabledByte, phoneEnabledByte, smsEnabledByte,
                wechatEnabledByte, qqEnabledByte, otherEnabledByte,
                skypeEnabledByte, whatsappEnabledByte, emailEnabledByte,
                lineEnabledByte);
    }

    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    public boolean isPhoneEnabled() {
        return phoneEnabled;
    }

    public boolean isSmsEnabled() {
        return smsEnabled;
    }

    public boolean isWechatEnabled() {
        return wechatEnabled;
    }

    public boolean isQqEnabled() {
        return qqEnabled;
    }

    public boolean isOtherEnabled() {
        return otherEnabled;
    }

    public boolean isSkypeEnabled() {
        return skypeEnabled;
    }

    public boolean isWhatsappEnabled() {
        return whatsappEnabled;
    }

    public boolean isEmailEnabled() {
        return emailEnabled;
    }

    public boolean isLineEnabled() {
        return lineEnabled;
    }

    @Override
    public String toString() {
        return "NotificationsConfig{" +
                ", notificationsEnabled=" + notificationsEnabled +
                ", phoneEnabled=" + phoneEnabled +
                ", smsEnabled=" + smsEnabled +
                ", wechatEnabled=" + wechatEnabled +
                ", qqEnabled=" + qqEnabled +
                ", otherEnabled=" + otherEnabled +
                ", skypeEnabled=" + skypeEnabled +
                ", whatsappEnabled=" + whatsappEnabled +
                ", emailEnabled=" + emailEnabled +
                ", lineEnabled=" + lineEnabled +
                ", totalSize=" + (getSpecificDataPayloadSize() + ConfigFile.MAGIC_SIZE) +
                '}';
    }

}
