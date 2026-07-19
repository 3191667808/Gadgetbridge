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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.devices.ycbt.YcbtConstants;

public final class YcbtInboundRouter {
    private static final List<UUID> INBOUND_CHARACTERISTIC_UUIDS = Collections.unmodifiableList(Arrays.asList(
            YcbtConstants.FFE1_CHARACTERISTIC_UUID,
            YcbtConstants.FFE2_CHARACTERISTIC_UUID
    ));

    private final YcbtFrameReassembler reassembler = new YcbtFrameReassembler();

    public static List<UUID> getInboundCharacteristicUuids() {
        return INBOUND_CHARACTERISTIC_UUIDS;
    }

    public boolean accepts(final UUID characteristicUuid) {
        return INBOUND_CHARACTERISTIC_UUIDS.contains(characteristicUuid);
    }

    public RouteResult accept(final UUID characteristicUuid, final byte[] value) {
        if (!accepts(characteristicUuid)) {
            return new RouteResult(false, Collections.emptyList(), null);
        }

        final YcbtFrameReassembler.AcceptResult result = reassembler.acceptWithDiagnostics(value);
        return new RouteResult(true, result.getFrames(), result.getMalformedReason());
    }

    public static final class RouteResult {
        private final boolean accepted;
        private final List<YcbtFrameCodec.Frame> frames;
        private final String malformedReason;

        private RouteResult(final boolean accepted,
                            final List<YcbtFrameCodec.Frame> frames,
                            final String malformedReason) {
            this.accepted = accepted;
            this.frames = Collections.unmodifiableList(new ArrayList<>(frames));
            this.malformedReason = malformedReason;
        }

        public boolean isAccepted() {
            return accepted;
        }

        public List<YcbtFrameCodec.Frame> getFrames() {
            return frames;
        }

        public String getMalformedReason() {
            return malformedReason;
        }
    }
}
