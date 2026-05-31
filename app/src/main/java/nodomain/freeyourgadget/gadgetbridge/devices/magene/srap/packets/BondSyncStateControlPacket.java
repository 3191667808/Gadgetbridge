package nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.packets;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.FunctionCode;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.PageNumber;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.ResourceType;

/**
 * A container for messages related to the BOND_SYNC_STATE_CONTROL (f2:e1) page.
 * These commands are used for device bonding, time synchronization, and state management.
 */
public final class BondSyncStateControlPacket {


    public enum BondControl {
        SET_ACTIVE_STATE(0x03),
        SET_TIMESTAMP(0x04),
        SET_TIMEZONE(0x05),
        SYNC_CONTROL(0x06),
        SET_ALTITUDE_CORRECTION(0x07),
        REQUEST_BOND(0x08),
        REQUEST_UNBOND(0x09),
        CANCEL_BOND(0x0E),
        UNKNOWN(-1);

        private final int value;
        private static final Map<Integer, BondControl> map = new HashMap<>();

        BondControl(int value) {
            this.value = value;
        }

        static {
            for (BondControl cmd : BondControl.values()) {
                map.put(cmd.value, cmd);
            }
        }

        public static BondControl fromValue(int value) {
            return map.getOrDefault(value, UNKNOWN);
        }

        public int getValue() {
            return value;
        }
    }

    public enum SyncAction {
        SYNC_START(0x00),
        SYNC_END(0x01),
        UNKNOWN(-1);

        private final int value;
        private static final Map<Integer, SyncAction> map = new HashMap<>();

        SyncAction(int value) {
            this.value = value;
        }

        static {
            for (SyncAction action : SyncAction.values()) {
                map.put(action.value, action);
            }
        }

        public static SyncAction fromValue(int value) {
            return map.getOrDefault(value, UNKNOWN);
        }

        public int getValue() {
            return value;
        }
    }

    private BondSyncStateControlPacket() {
        // This class is a namespace and should not be instantiated.
    }

    private static abstract class BondSyncStateControlWriteMessage extends AbstractSRAPMessage {
        protected final byte nodeAddress;

        protected BondSyncStateControlWriteMessage(byte nodeAddress) {
            this.nodeAddress = nodeAddress;
        }

        @Override
        public byte getNodeAddress() {
            return nodeAddress;
        }

        @Override
        public final FunctionCode getFunctionCode() {
            return FunctionCode.WRITE;
        }

        @Override
        public final PageNumber getPageNumber() {
            return PageNumber.BOND_SYNC_STATE_CONTROL;
        }

        @Override
        public final ResourceType getResourceType() {
            return ResourceType.CONTROL;
        }
    }

    /**
     * Represents a command with a single-byte payload (just the control code).
     */
    private static abstract class SingleByteControlWriteMessage extends BondSyncStateControlWriteMessage {
        private final byte[] payload;

        protected SingleByteControlWriteMessage(byte nodeAddress, BondControl control) {
            super(nodeAddress);
            this.payload = new byte[]{(byte) control.getValue()};
        }

        @Override
        public byte[] getPayload() {
            return payload;
        }
    }

    /**
     * Base class for responses on the BOND_SYNC_STATE_CONTROL page (f5:e1).
     * The protocol does not specify a payload for these responses, so this class
     * assumes a simple status code (0 for success) or an empty payload.
     */
    public static abstract class BaseResponse {
        private final int status;

        public BaseResponse(byte[] payload) {
            if (payload == null || payload.length == 0) {
                this.status = 0; // Assume success on empty payload
            } else {
                // Assuming the first byte is a status code if payload is not empty
                this.status = payload[0] & 0xFF;
            }
        }

        public int getStatus() {
            return status;
        }

        public boolean isSuccess() {
            return status == 0;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "{status=" + status + ", isSuccess=" + isSuccess() + "}";
        }
    }

    /** Namespace for the Request Bond command (0x08). */
    public static final class RequestBond {
        private RequestBond() {}
        public static class Write extends SingleByteControlWriteMessage {
            public Write(byte nodeAddress) {
                super(nodeAddress, BondControl.REQUEST_BOND);
            }
        }
        public static class Response extends BaseResponse {
            public Response(byte[] payload) { super(payload); }
        }
    }

    /** Namespace for the Request Un-bond command (0x09). */
    public static final class RequestUnbond {
        private RequestUnbond() {}
        public static class Write extends SingleByteControlWriteMessage {
            public Write(byte nodeAddress) {
                super(nodeAddress, BondControl.REQUEST_UNBOND);
            }
        }
        public static class Response extends BaseResponse {
            public Response(byte[] payload) { super(payload); }
        }
    }

    /** Namespace for the Cancel Bond command (0x0E). */
    public static final class CancelBond {
        private CancelBond() {}
        public static class Write extends SingleByteControlWriteMessage {
            public Write(byte nodeAddress) {
                super(nodeAddress, BondControl.CANCEL_BOND);
            }
        }
        public static class Response extends BaseResponse {
            public Response(byte[] payload) { super(payload); }
        }
    }

    /** Namespace for the Set Active State command (0x03). */
    public static final class SetActiveState {
        private SetActiveState() {}
        public static class Write extends BondSyncStateControlWriteMessage {
            private final byte[] payload;
            public Write(byte nodeAddress, byte activeState) {
                super(nodeAddress);
                ByteBuffer buffer = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN);
                buffer.put((byte) BondControl.SET_ACTIVE_STATE.getValue());
                buffer.put(activeState);
                buffer.put(new byte[5]); // 5 bytes of padding
                this.payload = buffer.array();
            }
            @Override public byte[] getPayload() { return payload; }
        }
        public static class Response extends BaseResponse {
            public Response(byte[] payload) { super(payload); }
        }
    }

    /** Namespace for the Set Timestamp command (0x04). */
    public static final class SetTimestamp {
        private SetTimestamp() {}
        public static class Write extends BondSyncStateControlWriteMessage {
            private final byte[] payload;
            public Write(byte nodeAddress, long timestamp) {
                super(nodeAddress);
                ByteBuffer buffer = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN);
                buffer.put((byte) BondControl.SET_TIMESTAMP.getValue());
                buffer.putInt((int) timestamp); // Lua dissector shows uint32
                buffer.put(new byte[2]); // 2 bytes of padding
                this.payload = buffer.array();
            }
            @Override public byte[] getPayload() { return payload; }
        }
        public static class Response extends BaseResponse {
            public Response(byte[] payload) { super(payload); }
        }
    }

    /** Namespace for the Set Timezone command (0x05). */
    public static final class SetTimezone {
        private SetTimezone() {}
        public static class Write extends BondSyncStateControlWriteMessage {
            private final byte[] payload;
            public Write(byte nodeAddress, int timezoneSeconds) {
                super(nodeAddress);
                ByteBuffer buffer = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN);
                buffer.put((byte) BondControl.SET_TIMEZONE.getValue());
                buffer.putInt(timezoneSeconds);
                buffer.put(new byte[2]); // 2 bytes of padding
                this.payload = buffer.array();
            }
            @Override public byte[] getPayload() { return payload; }
        }
        public static class Response extends BaseResponse {
            public Response(byte[] payload) { super(payload); }
        }
    }

    /** Namespace for the Set Altitude Correction command (0x07). */
    public static final class SetAltitudeCorrection {
        private SetAltitudeCorrection() {}
        public static class Write extends BondSyncStateControlWriteMessage {
            private final byte[] payload;
            public Write(byte nodeAddress, int altitudeCorrection) {
                super(nodeAddress);
                ByteBuffer buffer = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN);
                buffer.put((byte) BondControl.SET_ALTITUDE_CORRECTION.getValue());
                buffer.putInt(altitudeCorrection);
                buffer.put(new byte[2]); // 2 bytes of padding
                this.payload = buffer.array();
            }
            @Override public byte[] getPayload() { return payload; }
        }
        public static class Response extends BaseResponse {
            public Response(byte[] payload) { super(payload); }
        }
    }

    /** Namespace for the Sync Start command (0x06, action 0x00). */
    public static final class SyncStart {
        private SyncStart() {}
        public static class Write extends BondSyncStateControlWriteMessage {
            private final byte[] payload;
            public Write(byte nodeAddress) {
                super(nodeAddress);
                ByteBuffer buffer = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN);
                buffer.put((byte) BondControl.SYNC_CONTROL.getValue());
                buffer.putInt(SyncAction.SYNC_START.getValue());
                buffer.put(new byte[2]); // 2 bytes of padding
                this.payload = buffer.array();
            }
            @Override public byte[] getPayload() { return payload; }
        }
        public static class Response extends BaseResponse {
            private byte packetMark;
            private int syncCommand;
            public Response(byte[] payload) {
                super(payload);
                ByteBuffer buffer = ByteBuffer.wrap(payload);

                this.packetMark = buffer.get();
                this.syncCommand = buffer.get();
            }
            public int getSyncCommand() {
                return syncCommand;
            }
        }
    }

    /** Namespace for the Sync End command (0x06, action 0x01). */
    public static final class SyncEnd {
        private SyncEnd() {}
        public static class Write extends BondSyncStateControlWriteMessage {
            private final byte[] payload;
            public Write(byte nodeAddress) {
                super(nodeAddress);
                ByteBuffer buffer = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
                buffer.put((byte) BondControl.SYNC_CONTROL.getValue());
                buffer.putInt(SyncAction.SYNC_END.getValue());
                this.payload = buffer.array();
            }
            @Override public byte[] getPayload() { return payload; }
        }
        public static class Response extends BaseResponse {
            public Response(byte[] payload) { super(payload); }
        }
    }
}

