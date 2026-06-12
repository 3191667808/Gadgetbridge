/*  Copyright (C) 2026 Marc

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.fitbit;

final class FitbitProtobufReader {
    private FitbitProtobufReader() {
    }

    static int skipField(final byte[] payload, final int offset, final int wireType) {
        switch (wireType) {
            case 1:
                return checkedOffset(payload, offset, 8);
            case 2:
                final Varint length = readVarint(payload, offset);
                return checkedOffset(payload, length.nextOffset, (int) length.value);
            case 5:
                return checkedOffset(payload, offset, 4);
            default:
                throw new IllegalArgumentException("Unsupported protobuf wire type " + wireType);
        }
    }

    static int checkedOffset(final byte[] payload, final int offset, final int length) {
        final int nextOffset = offset + length;
        if (length < 0 || nextOffset < offset || nextOffset > payload.length) {
            throw new IllegalArgumentException("Malformed protobuf field length");
        }
        return nextOffset;
    }

    static Varint readVarint(final byte[] payload, final int offset) {
        long value = 0;
        int shift = 0;
        int pos = offset;
        while (pos < payload.length && shift < 64) {
            final int b = payload[pos++] & 0xff;
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return new Varint(value, pos);
            }
            shift += 7;
        }
        throw new IllegalArgumentException("Malformed protobuf varint");
    }

    static final class Varint {
        final long value;
        final int nextOffset;

        private Varint(final long value, final int nextOffset) {
            this.value = value;
            this.nextOffset = nextOffset;
        }
    }
}
