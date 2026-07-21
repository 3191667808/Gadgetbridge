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

final class YcbtBloodPressureOperation {
    enum State {
        IDLE,
        START_QUEUED,
        WAITING_START_REPLY,
        MEASURING,
        STOP_QUEUED,
        WAITING_STOP_REPLY,
        CLEANUP_STOP_QUEUED,
        CLEANUP_STOP_SENT
    }

    enum Reply {
        IGNORED,
        START_ACCEPTED,
        START_REJECTED,
        STOP_REPLY
    }

    private State state = State.IDLE;

    boolean requestStart() {
        if (state != State.IDLE) {
            return false;
        }
        state = State.START_QUEUED;
        return true;
    }

    boolean markStartRequested() {
        if (state != State.START_QUEUED) {
            return false;
        }
        state = State.WAITING_START_REPLY;
        return true;
    }

    Reply handleReply(final int status) {
        if (state == State.WAITING_START_REPLY) {
            if (status == 0) {
                state = State.MEASURING;
                return Reply.START_ACCEPTED;
            }
            state = State.IDLE;
            return Reply.START_REJECTED;
        }
        if (state == State.WAITING_STOP_REPLY) {
            state = State.IDLE;
            return Reply.STOP_REPLY;
        }
        return Reply.IGNORED;
    }

    boolean handleResult() {
        if (state != State.MEASURING) {
            return false;
        }
        state = State.STOP_QUEUED;
        return true;
    }

    boolean markStopRequested() {
        if (state != State.STOP_QUEUED) {
            return false;
        }
        state = State.WAITING_STOP_REPLY;
        return true;
    }

    boolean requestCleanupStop() {
        if (state == State.IDLE || state == State.CLEANUP_STOP_QUEUED || state == State.CLEANUP_STOP_SENT) {
            return false;
        }
        state = State.CLEANUP_STOP_QUEUED;
        return true;
    }

    boolean markCleanupStopRequested() {
        if (state != State.CLEANUP_STOP_QUEUED) {
            return false;
        }
        state = State.CLEANUP_STOP_SENT;
        return true;
    }

    boolean finishCleanup() {
        if (state != State.CLEANUP_STOP_QUEUED && state != State.CLEANUP_STOP_SENT) {
            return false;
        }
        state = State.IDLE;
        return true;
    }

    boolean cancel() {
        if (state == State.IDLE) {
            return false;
        }
        state = State.IDLE;
        return true;
    }

    State getState() {
        return state;
    }
}
