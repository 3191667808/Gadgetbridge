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
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi;

/**
 * Wrapper and validator for a Xiaomi GNSS assistance (AGPS) bundle.
 *
 * The bundle is a raw stream of u-blox <b>UBX-MGA-ANO</b> records (AssistNow Offline / predictive
 * orbits): each record is {@code b5 62 13 20 <len:u16> <payload> <ck_a> <ck_b>}, class {@code 0x13}
 * (MGA), id {@code 0x20} (ANO). The public Broadcom LTO servers serve this format verbatim and the
 * band's receiver ingests it unchanged.
 *
 * {@link #isValid()} walks the whole file and verifies every record's UBX framing and checksum, so a
 * truncated or corrupt download (an HTTP error page, a tampered file) is rejected rather than pushed
 * to the band, where malformed assistance data can leave the GPS subsystem unresponsive.
 */
public class XiaomiAgpsFile {
    // UBX sync chars (b5 62) + class 0x13 (MGA) + id 0x20 (ANO) that every record starts with.
    private static final byte UBX_SYNC_1 = (byte) 0xb5;
    private static final byte UBX_SYNC_2 = 0x62;
    private static final byte UBX_CLASS_MGA = 0x13;
    private static final byte UBX_ID_ANO = 0x20;
    // A 7-day multi-GNSS bundle runs ~300 KB; keep a generous window around the LTO variants.
    private static final int MIN_SIZE = 64 * 1024;
    private static final int MAX_SIZE = 4 * 1024 * 1024;

    private final byte[] fileBytes;

    public XiaomiAgpsFile(final byte[] fileBytes) {
        this.fileBytes = fileBytes;
    }

    public boolean isValid() {
        if (fileBytes == null || fileBytes.length < MIN_SIZE || fileBytes.length > MAX_SIZE) {
            return false;
        }

        int i = 0;
        int records = 0;
        while (i + 8 <= fileBytes.length) {
            if (fileBytes[i] != UBX_SYNC_1 || fileBytes[i + 1] != UBX_SYNC_2
                    || fileBytes[i + 2] != UBX_CLASS_MGA || fileBytes[i + 3] != UBX_ID_ANO) {
                return false;
            }
            final int len = (fileBytes[i + 4] & 0xff) | ((fileBytes[i + 5] & 0xff) << 8);
            final int end = i + 6 + len; // index of ck_a
            if (end + 2 > fileBytes.length) {
                return false; // truncated record
            }
            // 8-bit Fletcher checksum over class..payload (offsets i+2 .. end-1 inclusive).
            int ckA = 0;
            int ckB = 0;
            for (int j = i + 2; j < end; j++) {
                ckA = (ckA + (fileBytes[j] & 0xff)) & 0xff;
                ckB = (ckB + ckA) & 0xff;
            }
            if ((fileBytes[end] & 0xff) != ckA || (fileBytes[end + 1] & 0xff) != ckB) {
                return false;
            }
            i = end + 2;
            records++;
        }
        // Must consume the whole file with no trailing bytes and have found real records.
        return i == fileBytes.length && records > 0;
    }

    public byte[] getBytes() {
        return fileBytes;
    }
}
