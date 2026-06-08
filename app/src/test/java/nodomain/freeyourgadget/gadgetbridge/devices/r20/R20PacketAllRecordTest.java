/*  Copyright (C) 2026 The Gadgetbridge Project

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
package nodomain.freeyourgadget.gadgetbridge.devices.r20;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.service.devices.r20.R20Packet;

/**
 * Validates the 20-byte composite-history record parser against the reference
 * hex dump captured from an R20 device by the
 * <a href="https://github.com/auroraphtgrp01/ble-sleeping">ble-sleeping</a>
 * project. Each 20-byte record carries:
 * timestamp / steps / HR / SBP / DBP / SpO2 / respiratory rate / HRV / CVRR /
 * temperature / body fat / blood sugar.
 *
 * <p>This locks the byte layout decoded from
 * {@code com.yucheng.ycbtsdk.core.DataUnpack.unpackHealthData} case 9,
 * preventing regressions when the parser is refactored.
 */
public class R20PacketAllRecordTest {

    private static byte[] hex(String s) {
        s = s.replace(" ", "").replace("\n", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    /**
     * First record from the {@code Loại dữ liệu (i1): 9} capture in
     * ble-sleeping/log.md:
     * <pre>
     *   a7 49 9c 2f   timestamp (2000-epoch seconds, LE)
     *   00 00         steps  = 0
     *   57            HR     = 87
     *   73            SBP    = 115
     *   4a            DBP    = 74
     *   62            SpO2   = 98
     *   12            resp   = 18
     *   28            hrv    = 40
     *   05            cvrr   = 5
     *   00            tempInt = 0
     *   0f            tempFrac = 15 -> 0.15 (sensor-off sentinel)
     *   00 00         bodyFat = 0
     *   00            bloodSugar = 0
     *   ae 11         padding
     * </pre>
     */
    @Test
    public void parsesReferenceCompositeRecord() {
        byte[] payload = hex(
                "a7 49 9c 2f 00 00 57 73 4a 62 12 28 05 00 0f 00 00 00 ae 11");

        List<R20Packet.AllRecord> records = R20Packet.parseAllRecords(payload);
        assertEquals("one record per 20 bytes", 1, records.size());

        R20Packet.AllRecord r = records.get(0);
        assertEquals("HR",   87,  r.hr);
        assertEquals("SBP",  115, r.systolic);
        assertEquals("DBP",  74,  r.diastolic);
        assertEquals("SpO2", 98,  r.spo2);
        assertEquals("steps", 0,  r.steps);
        assertEquals("respiration", 18, r.respiratoryRate);
        assertEquals("HRV",  40,  r.hrv);
        assertEquals("CVRR", 5,   r.cvrr);
        assertEquals("bloodSugar", 0, r.bloodSugar);

        // Timestamp: 0x2f9c49a7 = 798,852,007 sec since 2000-01-01 UTC
        //   + 946684800 (2000-01-01 in Unix epoch) = 1,745,536,807 -> 2025-04-24T22:00:07Z
        long expectedSec = 0x2f9c49a7L + 946684800L;
        assertEquals("timestamp", expectedSec * 1000L, r.timestampMs);
    }

    @Test
    public void parsesFullCompositeStream() {
        // Five back-to-back records, exactly as streamed by the firmware.
        byte[] payload = hex(
                "a7 49 9c 2f 00 00 57 73 4a 62 12 28 05 00 0f 00 00 00 ae 11" +
                "c9 57 9c 2f 00 00 51 72 4a 62 10 2a 02 00 0f 00 00 00 4b e1" +
                "cf 65 9c 2f 00 00 38 69 44 5e 0b 2c 02 00 0f 00 00 00 c4 f4" +
                "d7 73 9c 2f 00 00 4c 6e 4a 62 0f 25 03 00 0f 00 00 00 27 32" +
                "e4 81 9c 2f 00 00 49 6e 49 62 0f 25 02 00 0f 00 00 00 70 44");

        List<R20Packet.AllRecord> records = R20Packet.parseAllRecords(payload);
        assertEquals("five composite records", 5, records.size());

        // Sanity: timestamps strictly increasing (~3556 s = ~59 min spacing).
        for (int i = 1; i < records.size(); i++) {
            assertTrue("ts ascending at " + i,
                    records.get(i).timestampMs > records.get(i - 1).timestampMs);
        }

        // Sanity: HR/SBP/DBP/SpO2 fall in physiological ranges.
        for (R20Packet.AllRecord r : records) {
            assertTrue("HR plausible: " + r.hr,   r.hr >= 30 && r.hr <= 220);
            assertTrue("SBP plausible: " + r.systolic,  r.systolic >= 60 && r.systolic <= 220);
            assertTrue("DBP plausible: " + r.diastolic, r.diastolic >= 40 && r.diastolic <= 130);
            assertTrue("SpO2 plausible: " + r.spo2,     r.spo2 >= 80 && r.spo2 <= 100);
            assertTrue("Resp plausible: " + r.respiratoryRate,
                    r.respiratoryRate >= 8 && r.respiratoryRate <= 40);
        }
    }

    /** Empty payload must round-trip to an empty list, not crash. */
    @Test
    public void emptyPayload() {
        assertEquals(0, R20Packet.parseAllRecords(new byte[0]).size());
        assertEquals(0, R20Packet.parseAllRecords(null).size());
    }

    /** Partial trailing bytes (< 20) must be dropped silently. */
    @Test
    public void truncatedTrailingBytes() {
        byte[] payload = hex(
                "a7 49 9c 2f 00 00 57 73 4a 62 12 28 05 00 0f 00 00 00 ae 11" +
                "de ad be ef"); // 4-byte tail
        assertEquals(1, R20Packet.parseAllRecords(payload).size());
    }
}
