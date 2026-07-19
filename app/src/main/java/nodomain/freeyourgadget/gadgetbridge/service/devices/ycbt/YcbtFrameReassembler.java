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

public final class YcbtFrameReassembler {
    private byte[] buffered = new byte[0];

    public List<YcbtFrameCodec.Frame> accept(final byte[] fragment) {
        if (fragment == null) {
            throw new IllegalArgumentException("Fragment must not be null");
        }
        return acceptWithDiagnostics(fragment).getFrames();
    }

    public AcceptResult acceptWithDiagnostics(final byte[] fragment) {
        if (fragment == null) {
            return new AcceptResult(Collections.emptyList(), "Fragment must not be null");
        }
        if (fragment.length == 0) {
            return new AcceptResult(Collections.emptyList(), null);
        }

        final byte[] combined = Arrays.copyOf(buffered, buffered.length + fragment.length);
        System.arraycopy(fragment, 0, combined, buffered.length, fragment.length);
        buffered = combined;

        final List<YcbtFrameCodec.Frame> frames = new ArrayList<>();
        String malformedReason = null;
        while (buffered.length >= YcbtFrameCodec.HEADER_LENGTH) {
            final int declaredLength = YcbtFrameCodec.readLittleEndianUnsignedShort(buffered, 2);
            if (declaredLength < YcbtFrameCodec.MINIMUM_FRAME_LENGTH) {
                malformedReason = "Declared frame length is shorter than the minimum length";
                buffered = new byte[0];
                break;
            }
            if (buffered.length < declaredLength) {
                break;
            }

            final byte[] encodedFrame = Arrays.copyOf(buffered, declaredLength);
            try {
                frames.add(YcbtFrameCodec.decode(encodedFrame));
            } catch (final IllegalArgumentException e) {
                malformedReason = e.getMessage();
                buffered = new byte[0];
                break;
            }

            buffered = Arrays.copyOfRange(buffered, declaredLength, buffered.length);
        }

        return new AcceptResult(frames, malformedReason);
    }

    public static final class AcceptResult {
        private final List<YcbtFrameCodec.Frame> frames;
        private final String malformedReason;

        private AcceptResult(final List<YcbtFrameCodec.Frame> frames, final String malformedReason) {
            this.frames = Collections.unmodifiableList(new ArrayList<>(frames));
            this.malformedReason = malformedReason;
        }

        public List<YcbtFrameCodec.Frame> getFrames() {
            return frames;
        }

        public String getMalformedReason() {
            return malformedReason;
        }
    }
}
