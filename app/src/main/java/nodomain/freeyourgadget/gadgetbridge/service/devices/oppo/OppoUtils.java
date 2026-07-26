/*  Copyright (C) 2026 NTeditor

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.oppo;

import androidx.annotation.NonNull;

public final class OppoUtils {
    private OppoUtils() {
    }

    @NonNull
    public static String numberToHex(@NonNull final Number code) {
        long val = code.longValue();
        int byteSize = (code instanceof Byte) ? 1 : (code instanceof Short) ? 2 : (code instanceof Integer) ? 4 : 8;
        return String.format("%0" + (byteSize * 2) + "X", val & (0xFFFFFFFFFFFFFFFFL >>> (64 - byteSize * 8)));
    }

    @NonNull
    public static byte[] bytesReverse(@NonNull final byte[] bytes) {
        byte[] reversed = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            reversed[i] = bytes[bytes.length - 1 - i];
        }
        return reversed;
    }
}
