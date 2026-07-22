/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.ycbt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * State machine for YCBT health-history block transfers.
 *
 * <p>The caller owns transport and scheduling. Commands returned by this class are logical,
 * unframed YCBT commands. The caller should invoke {@link #onTimeout(long)} when a timeout may
 * have elapsed.</p>
 */
public final class YcbtHistoryTransfer {
    public static final long DEFAULT_INACTIVITY_TIMEOUT_MILLIS = 10_000;
    public static final long DEFAULT_TYPE_TIMEOUT_MILLIS = 30_000;
    public static final int DEFAULT_MAX_BUFFER_BYTES = 0xffff;

    private static final int GROUP_HEALTH = 0x05;
    private static final int TERMINAL_COMMAND = 0x80;
    private static final int ACK_ACCEPTED = 0x00;
    private static final int ACK_CRC_FAILURE = 0x04;
    private static final int HEADER_PAYLOAD_LENGTH = 10;
    private static final int TERMINAL_PAYLOAD_LENGTH = 6;
    private static final int ERROR_UNSUPPORTED_COMMAND = 0xfb;
    private static final int ERROR_UNSUPPORTED_KEY = 0xfc;
    private static final int ERROR_CRC = 0xff;

    private enum State {
        IDLE,
        REQUEST_SENT,
        RECEIVING
    }

    public enum HistoryType {
        SPORT(0x02, 0x11),
        SLEEP(0x04, 0x13),
        HEART_RATE(0x06, 0x15),
        BLOOD_PRESSURE(0x08, 0x17),
        VITALS(0x09, 0x18),
        BLOOD_OXYGEN(0x1a, 0x22),
        TEMPERATURE(0x1e, 0x26),
        COMPREHENSIVE(0x2f, 0x30),
        BODY_DATA(0x33, 0x34);

        private final int queryKey;
        private final int dataKey;

        HistoryType(final int queryKey, final int dataKey) {
            this.queryKey = queryKey;
            this.dataKey = dataKey;
        }

        public int getQueryKey() {
            return queryKey;
        }

        public int getDataKey() {
            return dataKey;
        }
    }

    public enum Status {
        IGNORED,
        STARTED,
        QUEUED,
        HEADER_ACCEPTED,
        DATA_ACCEPTED,
        BLOCK_ACCEPTED,
        CRC_RETRY,
        CRC_FAILED,
        TYPE_SKIPPED,
        UNSUPPORTED,
        PROTOCOL_ERROR,
        COUNT_MISMATCH,
        BUFFER_LIMIT_EXCEEDED,
        INACTIVITY_TIMEOUT,
        TYPE_TIMEOUT,
        FINISHED
    }

    public enum ActionType {
        REQUEST,
        ACK,
        NACK
    }

    private final long inactivityTimeoutMillis;
    private final long typeTimeoutMillis;
    private final int maxBufferBytes;
    private final ArrayDeque<HistoryType> queue = new ArrayDeque<>();
    private final Set<Integer> unsupportedKeys = new HashSet<>();

    private State state = State.IDLE;
    private HistoryType currentType;
    private byte[] buffer = new byte[0];
    private int bufferedBytes;
    private int expectedBytes;
    private int receivedPackets;
    private Status rejectedBlockStatus;
    private boolean retriedCurrentType;
    private long typeStartedAtMillis;
    private long lastActivityAtMillis;

    public YcbtHistoryTransfer() {
        this(DEFAULT_INACTIVITY_TIMEOUT_MILLIS, DEFAULT_TYPE_TIMEOUT_MILLIS, DEFAULT_MAX_BUFFER_BYTES);
    }

    public YcbtHistoryTransfer(final long inactivityTimeoutMillis,
                               final long typeTimeoutMillis,
                               final int maxBufferBytes) {
        if (inactivityTimeoutMillis <= 0 || typeTimeoutMillis <= 0) {
            throw new IllegalArgumentException("Timeouts must be positive");
        }
        if (maxBufferBytes <= 0) {
            throw new IllegalArgumentException("Maximum buffer size must be positive");
        }
        this.inactivityTimeoutMillis = inactivityTimeoutMillis;
        this.typeTimeoutMillis = typeTimeoutMillis;
        this.maxBufferBytes = maxBufferBytes;
    }

    public synchronized Result start(final List<HistoryType> types, final long nowMillis) {
        requireTypes(types);
        if (state != State.IDLE) {
            return result(Status.IGNORED, Collections.emptyList(), null, null);
        }

        queue.clear();
        for (final HistoryType type : types) {
            if (!unsupportedKeys.contains(type.getQueryKey())) {
                queue.add(type);
            }
        }
        if (queue.isEmpty()) {
            return result(Status.FINISHED, Collections.emptyList(), null, null);
        }
        return advance(Status.STARTED, new ArrayList<>(), null, null, nowMillis);
    }

    public synchronized Result append(final List<HistoryType> types, final long nowMillis) {
        requireTypes(types);
        boolean added = false;
        for (final HistoryType type : types) {
            if (!unsupportedKeys.contains(type.getQueryKey())
                    && type != currentType
                    && !queue.contains(type)) {
                queue.add(type);
                added = true;
            }
        }
        if (!added) {
            return result(Status.IGNORED, Collections.emptyList(), null, null);
        }
        if (state == State.IDLE) {
            return advance(Status.STARTED, new ArrayList<>(), null, null, nowMillis);
        }
        return result(Status.QUEUED, Collections.emptyList(), null, null);
    }

    public synchronized Result handle(final int command, final byte[] payload, final long nowMillis) {
        if (command < 0 || command > 0xff) {
            throw new IllegalArgumentException("Command must fit in one byte");
        }
        if (payload == null) {
            throw new IllegalArgumentException("Payload must not be null");
        }
        if (state == State.IDLE || currentType == null) {
            return result(Status.IGNORED, Collections.emptyList(), null, null);
        }

        final Integer error = command == currentType.getQueryKey() ? detectError(payload) : null;
        if (error != null) {
            final Status status;
            if (error == ERROR_UNSUPPORTED_COMMAND || error == ERROR_UNSUPPORTED_KEY) {
                unsupportedKeys.add(currentType.getQueryKey());
                queue.removeIf(type -> type.getQueryKey() == currentType.getQueryKey());
                status = Status.UNSUPPORTED;
            } else {
                status = Status.PROTOCOL_ERROR;
            }
            return advance(status, new ArrayList<>(), null, null, nowMillis);
        }

        if (command == currentType.getQueryKey()) {
            return handleHeader(payload, nowMillis);
        }
        if (command == currentType.getDataKey()) {
            return handleData(payload, nowMillis);
        }
        if (command == TERMINAL_COMMAND) {
            return handleTerminal(payload, nowMillis);
        }
        return result(Status.IGNORED, Collections.emptyList(), null, null);
    }

    public synchronized Result onTimeout(final long nowMillis) {
        if (state == State.IDLE) {
            return result(Status.IGNORED, Collections.emptyList(), null, null);
        }
        final Status status;
        if (hasElapsed(nowMillis, typeStartedAtMillis, typeTimeoutMillis)) {
            status = Status.TYPE_TIMEOUT;
        } else if (hasElapsed(nowMillis, lastActivityAtMillis, inactivityTimeoutMillis)) {
            status = Status.INACTIVITY_TIMEOUT;
        } else {
            return result(Status.IGNORED, Collections.emptyList(), null, null);
        }
        return advance(status, new ArrayList<>(), null, null, nowMillis);
    }

    public synchronized void cancel() {
        queue.clear();
        state = State.IDLE;
        currentType = null;
        retriedCurrentType = false;
        unsupportedKeys.clear();
        resetBlock();
    }

    public synchronized boolean isActive() {
        return state != State.IDLE;
    }

    public synchronized HistoryType getCurrentType() {
        return currentType;
    }

    public synchronized int getBufferedByteCount() {
        return bufferedBytes;
    }

    public synchronized boolean isUnsupported(final HistoryType type) {
        if (type == null) {
            throw new IllegalArgumentException("History type must not be null");
        }
        return unsupportedKeys.contains(type.getQueryKey());
    }

    private Result handleHeader(final byte[] payload, final long nowMillis) {
        if (payload.length < HEADER_PAYLOAD_LENGTH) {
            if (payload.length == 1 && payload[0] == 0) {
                return advance(Status.TYPE_SKIPPED, new ArrayList<>(), null, null, nowMillis);
            }
            return retryOrSkip(Status.PROTOCOL_ERROR, nowMillis);
        }

        final long declaredBytes = u32(payload, 6);
        expectedBytes = (int) declaredBytes;
        receivedPackets = 0;
        bufferedBytes = 0;
        state = State.RECEIVING;
        lastActivityAtMillis = nowMillis;
        if (declaredBytes > maxBufferBytes || declaredBytes > 0xffffL) {
            buffer = new byte[0];
            rejectedBlockStatus = Status.BUFFER_LIMIT_EXCEEDED;
            return result(Status.BUFFER_LIMIT_EXCEEDED, Collections.emptyList(), null, null);
        }
        buffer = new byte[expectedBytes];
        return result(Status.HEADER_ACCEPTED, Collections.emptyList(), null, null);
    }

    private Result handleData(final byte[] payload, final long nowMillis) {
        if (state != State.RECEIVING) {
            return result(Status.IGNORED, Collections.emptyList(), null, null);
        }

        receivedPackets++;
        lastActivityAtMillis = nowMillis;
        if (rejectedBlockStatus != null) {
            return result(rejectedBlockStatus, Collections.emptyList(), null, null);
        }
        if (payload.length > expectedBytes - bufferedBytes) {
            rejectedBlockStatus = Status.COUNT_MISMATCH;
            return result(Status.COUNT_MISMATCH, Collections.emptyList(), null, null);
        }
        System.arraycopy(payload, 0, buffer, bufferedBytes, payload.length);
        bufferedBytes += payload.length;
        return result(Status.DATA_ACCEPTED, Collections.emptyList(), null, null);
    }

    private Result handleTerminal(final byte[] payload, final long nowMillis) {
        if (state != State.RECEIVING) {
            return result(Status.IGNORED, Collections.emptyList(), null, null);
        }
        if (payload.length < TERMINAL_PAYLOAD_LENGTH) {
            return retryOrSkip(Status.PROTOCOL_ERROR, nowMillis);
        }

        final int terminalPackets = u16(payload, 0);
        final int terminalBytes = u16(payload, 2);
        if (rejectedBlockStatus != null) {
            return retryOrSkip(rejectedBlockStatus, nowMillis);
        }
        final boolean terminalMatchesHeader = terminalBytes == expectedBytes;
        final boolean terminalMatchesBuffer = terminalBytes == bufferedBytes;
        if (!terminalMatchesHeader && !terminalMatchesBuffer) {
            return result(Status.IGNORED, Collections.emptyList(), null, null);
        }
        if (!terminalMatchesHeader
                || !terminalMatchesBuffer
                || receivedPackets != terminalPackets) {
            return retryOrSkip(Status.COUNT_MISMATCH, nowMillis);
        }

        final int expectedCrc = u16(payload, 4);
        final int actualCrc = crc16(buffer, bufferedBytes);
        final List<Action> actions = new ArrayList<>();
        if (actualCrc != expectedCrc) {
            return retryOrSkip(Status.CRC_FAILED, nowMillis);
        }

        final HistoryType completedType = currentType;
        final byte[] completedBlock = Arrays.copyOf(buffer, bufferedBytes);
        actions.add(Action.ack(currentType));
        return advance(Status.BLOCK_ACCEPTED, actions, completedType, completedBlock, nowMillis);
    }

    private Result retryOrSkip(final Status failureStatus, final long nowMillis) {
        final List<Action> actions = new ArrayList<>();
        actions.add(Action.nack(currentType));
        if (!retriedCurrentType) {
            retriedCurrentType = true;
            resetBlock();
            state = State.REQUEST_SENT;
            typeStartedAtMillis = nowMillis;
            lastActivityAtMillis = nowMillis;
            actions.add(Action.request(currentType));
            final Status retryStatus = failureStatus == Status.CRC_FAILED ? Status.CRC_RETRY : failureStatus;
            return result(retryStatus, actions, null, null);
        }
        return advance(failureStatus, actions, null, null, nowMillis);
    }

    private Result advance(final Status status,
                           final List<Action> actions,
                           final HistoryType completedType,
                           final byte[] completedBlock,
                           final long nowMillis) {
        resetBlock();
        retriedCurrentType = false;
        if (queue.isEmpty()) {
            state = State.IDLE;
            currentType = null;
            return new Result(status, actions, completedType, completedBlock, true, null);
        }

        currentType = queue.removeFirst();
        state = State.REQUEST_SENT;
        typeStartedAtMillis = nowMillis;
        lastActivityAtMillis = nowMillis;
        actions.add(Action.request(currentType));
        return new Result(status, actions, completedType, completedBlock, false, currentType);
    }

    private Result result(final Status status,
                          final List<Action> actions,
                          final HistoryType completedType,
                          final byte[] completedBlock) {
        return new Result(status, actions, completedType, completedBlock, state == State.IDLE, currentType);
    }

    private void resetBlock() {
        buffer = new byte[0];
        bufferedBytes = 0;
        expectedBytes = 0;
        receivedPackets = 0;
        rejectedBlockStatus = null;
    }

    private static void requireTypes(final List<HistoryType> types) {
        if (types == null || types.contains(null)) {
            throw new IllegalArgumentException("History types must not be null");
        }
    }

    private static Integer detectError(final byte[] payload) {
        if (payload.length != 1) {
            return null;
        }
        final int value = payload[0] & 0xff;
        return value >= ERROR_UNSUPPORTED_COMMAND && value <= ERROR_CRC ? value : null;
    }

    private static boolean hasElapsed(final long nowMillis, final long sinceMillis, final long durationMillis) {
        return nowMillis >= sinceMillis && nowMillis - sinceMillis >= durationMillis;
    }

    private static int u16(final byte[] payload, final int offset) {
        return (payload[offset] & 0xff) | ((payload[offset + 1] & 0xff) << 8);
    }

    private static long u32(final byte[] payload, final int offset) {
        return (payload[offset] & 0xffL)
                | ((payload[offset + 1] & 0xffL) << 8)
                | ((payload[offset + 2] & 0xffL) << 16)
                | ((payload[offset + 3] & 0xffL) << 24);
    }

    private static int crc16(final byte[] bytes, final int length) {
        int crc = 0xffff;
        for (int index = 0; index < length; index++) {
            crc ^= (bytes[index] & 0xff) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xffff : (crc << 1) & 0xffff;
            }
        }
        return crc;
    }

    public static final class Action {
        private final ActionType type;
        private final HistoryType historyType;
        private final byte[] command;

        private Action(final ActionType type, final HistoryType historyType, final byte[] command) {
            this.type = type;
            this.historyType = historyType;
            this.command = command;
        }

        private static Action request(final HistoryType historyType) {
            return new Action(ActionType.REQUEST, historyType,
                    new byte[]{GROUP_HEALTH, (byte) historyType.getQueryKey()});
        }

        private static Action ack(final HistoryType historyType) {
            return new Action(ActionType.ACK, historyType,
                    new byte[]{GROUP_HEALTH, (byte) TERMINAL_COMMAND, ACK_ACCEPTED});
        }

        private static Action nack(final HistoryType historyType) {
            return new Action(ActionType.NACK, historyType,
                    new byte[]{GROUP_HEALTH, (byte) TERMINAL_COMMAND, ACK_CRC_FAILURE});
        }

        public ActionType getType() {
            return type;
        }

        public HistoryType getHistoryType() {
            return historyType;
        }

        public byte[] getCommand() {
            return Arrays.copyOf(command, command.length);
        }
    }

    public static final class Result {
        private final Status status;
        private final List<Action> actions;
        private final HistoryType completedType;
        private final byte[] completedBlock;
        private final boolean finished;
        private final HistoryType currentType;

        private Result(final Status status,
                       final List<Action> actions,
                       final HistoryType completedType,
                       final byte[] completedBlock,
                       final boolean finished,
                       final HistoryType currentType) {
            this.status = status;
            this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
            this.completedType = completedType;
            this.completedBlock = completedBlock == null ? null : Arrays.copyOf(completedBlock, completedBlock.length);
            this.finished = finished;
            this.currentType = currentType;
        }

        public Status getStatus() {
            return status;
        }

        public List<Action> getActions() {
            return actions;
        }

        public HistoryType getCompletedType() {
            return completedType;
        }

        public byte[] getCompletedBlock() {
            return completedBlock == null ? null : Arrays.copyOf(completedBlock, completedBlock.length);
        }

        public boolean isFinished() {
            return finished;
        }

        public HistoryType getCurrentType() {
            return currentType;
        }
    }
}
