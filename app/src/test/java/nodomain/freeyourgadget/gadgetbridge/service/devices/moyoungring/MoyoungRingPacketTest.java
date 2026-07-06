package nodomain.freeyourgadget.gadgetbridge.service.devices.moyoungring;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.devices.moyoungring.MoyoungRingConstants;

public class MoyoungRingPacketTest {
    @Test
    public void encodeAndDecodeFrameUsesExpectedWireFormat() {
        final byte[] frame = MoyoungRingPacket.encode(0x02, 0x09, new byte[]{0x11, 0x22});

        Assert.assertArrayEquals(new byte[]{
                (byte) 0xFD, (byte) 0xDA, 0x10, 0x08, 0x02, 0x09, 0x11, 0x22
        }, frame);

        final MoyoungRingPacket decoded = MoyoungRingPacket.decodeFrame(frame);
        Assert.assertNotNull(decoded);
        Assert.assertEquals(0x02, decoded.cmd);
        Assert.assertEquals(0x09, decoded.sub);
        Assert.assertArrayEquals(new byte[]{0x11, 0x22}, decoded.payload);
        Assert.assertEquals(MoyoungRingConstants.OP_HIST_HR, decoded.opcode());
    }

    @Test
    public void setTimeUsesGmt8RelabelledWireFormat() {
        // The ring's set-clock command (cmd 1/1) is a 4-byte little-endian Unix epoch
        // (seconds) followed by a 1-byte timezone-hours offset. We replicate the app's
        // fixed GMT+8 relabel scheme (c1/f0.b): the local wall-clock digits are
        // reinterpreted as GMT+8 and paired with tz=8. Verified byte-for-byte against a
        // live capture (fd da 10 0b 01 01 <epochLE> 08). A malformed clock leaves the
        // daily charts empty, so lock the exact format here.
        //
        // Instant: local wall clock 2026-07-03 21:36:34 at UTC+3 (true UTC
        // 2026-07-03T18:36:34Z => epoch millis 1783103794000). The relabelled epoch is
        // (nowMs + 3h)/1000 - 8h = 1783085794 => bytes E2 BA 47 6A, tz 0x08.
        final long nowMs = 1_783_103_794_000L;
        final long expectedEpochSec = 1_783_085_794L;
        final java.util.TimeZone tz = java.util.TimeZone.getTimeZone("Etc/GMT-3"); // UTC+3
        final byte[] frame = MoyoungRingPacket.setTime(nowMs, tz);

        Assert.assertArrayEquals(new byte[]{
                (byte) 0xFD, (byte) 0xDA, 0x10,
                0x0B,                 // len = 6 header + 5 payload
                0x01, 0x01,           // cmd 1 / sub 1 = set time
                (byte) 0xE2, (byte) 0xBA, 0x47, 0x6A, // relabelled epoch, little-endian
                0x08,                 // timezone byte is always +8 (GMT+8 relabel)
        }, frame);

        // Sanity: little-endian round-trips to the expected relabelled epoch seconds.
        long le = (frame[6] & 0xFFL)
                | ((frame[7] & 0xFFL) << 8)
                | ((frame[8] & 0xFFL) << 16)
                | ((frame[9] & 0xFFL) << 24);
        Assert.assertEquals(expectedEpochSec, le);
    }

    @Test
    public void deviceTimeToUtcMsUndoesGmt8Relabel() {
        // c1/f0.a: a device timestamp is the wall clock relabelled as GMT+8, so real
        // UTC = deviceMs + 8h (undo relabel) - local offset at that instant. With a
        // device timestamp of 1783085919 s in a UTC+3 zone this yields
        // 1783085919000 + 28800000 - 10800000 = 1783103919000 (2026-07-03T18:38:39Z).
        final java.util.TimeZone tz = java.util.TimeZone.getTimeZone("Etc/GMT-3"); // UTC+3
        Assert.assertEquals(1_783_103_919_000L,
                MoyoungRingPacket.deviceTimeToUtcMs(1_783_085_919L, tz));
    }

    @Test
    public void enableTimingEncodesIntervalInFiveMinuteUnits() {
        // Wire value = minutes / 5, clamped to 1..12 (5..60 min). Verified against a
        // capture where the app's byte 6 produced ~30-minute HR slot spacing.
        Assert.assertArrayEquals(new byte[]{(byte) 0xFD, (byte) 0xDA, 0x10, 0x07,
                0x01, 0x06, 0x01}, MoyoungRingPacket.enableTiming(0x06, 5));    // 5 min -> 1
        Assert.assertEquals(0x03, MoyoungRingPacket.enableTiming(0x07, 15)[6]); // 15 min -> 3
        Assert.assertEquals(0x06, MoyoungRingPacket.enableTiming(0x08, 30)[6]); // 30 min -> 6 (app value)
        Assert.assertEquals(0x0C, MoyoungRingPacket.enableTiming(0x27, 90)[6]); // clamp high -> 12
        Assert.assertEquals(0x01, MoyoungRingPacket.enableTiming(0x06, 1)[6]);  // clamp low  -> 1
        Assert.assertEquals(0x00, MoyoungRingPacket.enableTiming(0x06, 0)[6]);  // off        -> 0

        // Temperature uses a boolean flag, not an interval.
        Assert.assertEquals(0x01, MoyoungRingPacket.enableTimingFlag(0x0D, true)[6]);
        Assert.assertEquals(0x00, MoyoungRingPacket.enableTimingFlag(0x0D, false)[6]);
    }

    @Test
    public void reassemblerReassemblesSplitFrame() {
        final MoyoungRingPacket.Reassembler reassembler = new MoyoungRingPacket.Reassembler();
        final byte[] frame = MoyoungRingPacket.encode(0x01, 0x09, new byte[]{0x48});

        Assert.assertTrue(reassembler.add(Arrays.copyOfRange(frame, 0, 4)).isEmpty());
        final List<MoyoungRingPacket> packets = reassembler.add(Arrays.copyOfRange(frame, 4, frame.length));

        Assert.assertEquals(1, packets.size());
        Assert.assertEquals(0x01, packets.get(0).cmd);
        Assert.assertEquals(0x09, packets.get(0).sub);
        Assert.assertArrayEquals(new byte[]{0x48}, packets.get(0).payload);
    }

    @Test
    public void reassemblerReturnsTwoFramesFromOneFeed() {
        final MoyoungRingPacket.Reassembler reassembler = new MoyoungRingPacket.Reassembler();
        final byte[] first = MoyoungRingPacket.encode(0x01, 0x09, new byte[]{0x48});
        final byte[] second = MoyoungRingPacket.encode(0x02, 0x0B, new byte[]{0x62});
        final byte[] both = concat(first, second);

        final List<MoyoungRingPacket> packets = reassembler.add(both);

        Assert.assertEquals(2, packets.size());
        Assert.assertEquals(MoyoungRingConstants.OP_LIVE_HR, packets.get(0).opcode());
        Assert.assertEquals(MoyoungRingConstants.OP_HIST_SPO2, packets.get(1).opcode());
    }

    @Test
    public void reassemblerSkipsLeadingGarbageAndResyncs() {
        final MoyoungRingPacket.Reassembler reassembler = new MoyoungRingPacket.Reassembler();
        final byte[] frame = MoyoungRingPacket.encode(0x02, 0x09, new byte[]{0x48});

        final List<MoyoungRingPacket> packets = reassembler.add(concat(new byte[]{0x55, 0x66, (byte) 0xFD, 0x00}, frame));

        Assert.assertEquals(1, packets.size());
        Assert.assertEquals(MoyoungRingConstants.OP_HIST_HR, packets.get(0).opcode());
        Assert.assertArrayEquals(new byte[]{0x48}, packets.get(0).payload);
    }

    @Test
    public void reassemblerBuffersIncompleteFrameUntilCompleted() {
        final MoyoungRingPacket.Reassembler reassembler = new MoyoungRingPacket.Reassembler();
        final byte[] frame = MoyoungRingPacket.encode(0x02, 0x0A, new byte[]{0x34, 0x12});

        Assert.assertTrue(reassembler.add(Arrays.copyOfRange(frame, 0, frame.length - 1)).isEmpty());
        final List<MoyoungRingPacket> packets = reassembler.add(new byte[]{frame[frame.length - 1]});

        Assert.assertEquals(1, packets.size());
        Assert.assertEquals(MoyoungRingConstants.OP_HIST_HRV, packets.get(0).opcode());
        Assert.assertArrayEquals(new byte[]{0x34, 0x12}, packets.get(0).payload);
    }

    @Test
    public void reassemblerToleratesMalformedInput() {
        final MoyoungRingPacket.Reassembler reassembler = new MoyoungRingPacket.Reassembler();

        Assert.assertTrue(reassembler.add(new byte[]{
                (byte) 0xFD, (byte) 0xDA, 0x10, 0x05, 0x01, 0x02,
                (byte) 0xFD, (byte) 0xDA, 0x10, 0x00, 0x03, 0x04,
                0x01, 0x02, 0x03
        }).isEmpty());

        final List<MoyoungRingPacket> packets = reassembler.add(MoyoungRingPacket.encode(0x01, 0x09, new byte[]{0x48}));
        Assert.assertEquals(1, packets.size());
        Assert.assertEquals(MoyoungRingConstants.OP_LIVE_HR, packets.get(0).opcode());
    }

    @Test
    public void parseHrRecordsDropsOnlySentinelsAndZeroes() {
        final long ts = 123456789L;

        final List<MoyoungRingPacket.HrRecord> valid = MoyoungRingPacket.parseHrRecords(
                concat(new byte[]{0x00}, record5(72, ts)));
        Assert.assertEquals(1, valid.size());
        Assert.assertEquals(72, valid.get(0).bpm);
        Assert.assertEquals(
                MoyoungRingPacket.deviceTimeToUtcMs(ts, java.util.TimeZone.getDefault()),
                valid.get(0).timestampMs);

        Assert.assertTrue(MoyoungRingPacket.parseHrRecords(concat(new byte[]{0x00}, record5(0xFF, ts))).isEmpty());
        Assert.assertEquals(1, MoyoungRingPacket.parseHrRecords(concat(new byte[]{0x00}, record5(250, ts))).size());
    }

    @Test
    public void parseSpo2RecordsDropsOnlyZeroSentinelAndValuesAboveOneHundred() {
        final long ts = 123456789L;

        final List<MoyoungRingPacket.Spo2Record> valid = MoyoungRingPacket.parseSpo2Records(
                concat(new byte[]{0x00}, record5(98, ts)));
        Assert.assertEquals(1, valid.size());
        Assert.assertEquals(98, valid.get(0).spo2);
        Assert.assertEquals(
                MoyoungRingPacket.deviceTimeToUtcMs(ts, java.util.TimeZone.getDefault()),
                valid.get(0).timestampMs);

        Assert.assertEquals(1, MoyoungRingPacket.parseSpo2Records(concat(new byte[]{0x00}, record5(40, ts))).size());
        Assert.assertTrue(MoyoungRingPacket.parseSpo2Records(concat(new byte[]{0x00}, record5(255, ts))).isEmpty());
    }

    @Test
    public void parseHrvRecordsDropsZeroAndU16Sentinel() {
        final long ts = 123456789L;

        final List<MoyoungRingPacket.HrvRecord> valid = MoyoungRingPacket.parseHrvRecords(
                concat(new byte[]{0x00}, hrvRecord(64, ts)));
        Assert.assertEquals(1, valid.size());
        Assert.assertEquals(64, valid.get(0).hrv);
        Assert.assertEquals(
                MoyoungRingPacket.deviceTimeToUtcMs(ts, java.util.TimeZone.getDefault()),
                valid.get(0).timestampMs);

        Assert.assertTrue(MoyoungRingPacket.parseHrvRecords(concat(new byte[]{0x00}, hrvRecord(0, ts))).isEmpty());
        Assert.assertTrue(MoyoungRingPacket.parseHrvRecords(concat(new byte[]{0x00}, hrvRecord(0xFFFF, ts))).isEmpty());
    }

    @Test
    public void parseStressRecordsDropsOnlyU8Sentinel() {
        final long ts = 123456789L;

        final List<MoyoungRingPacket.StressRecord> valid = MoyoungRingPacket.parseStressRecords(
                concat(new byte[]{0x00}, record5(42, ts)));
        Assert.assertEquals(1, valid.size());
        Assert.assertEquals(42, valid.get(0).stress);
        Assert.assertEquals(
                MoyoungRingPacket.deviceTimeToUtcMs(ts, java.util.TimeZone.getDefault()),
                valid.get(0).timestampMs);

        Assert.assertEquals(1, MoyoungRingPacket.parseStressRecords(concat(new byte[]{0x00}, record5(0, ts))).size());
        Assert.assertTrue(MoyoungRingPacket.parseStressRecords(concat(new byte[]{0x00}, record5(255, ts))).isEmpty());
    }

    @Test
    public void parseLiveStepsUsesStepsCaloriesDistanceOrder() {
        final MoyoungRingPacket.LiveSteps liveSteps = MoyoungRingPacket.parseLiveSteps(new byte[]{
                0x5b, 0x00, 0x00,
                0x4b, 0x00, 0x00,
                0x04, 0x00, 0x00
        });

        Assert.assertNotNull(liveSteps);
        Assert.assertEquals(91, liveSteps.steps);
        Assert.assertEquals(75, liveSteps.calories);
        Assert.assertEquals(4, liveSteps.distance);
    }

    @Test
    public void parseSleepSegmentsChainsDurationsToNextRecord() {
        final long startMs = 1_000_000L;
        final long endMs = startMs + 120L * 60_000L;
        final byte[] payload = new byte[]{
                0x00,
                0x00, 22, 0,
                0x01, 22, 30,
                0x02, 23, 0,
                0x03, 23, 45
        };

        final List<MoyoungRingPacket.SleepSegment> segments =
                MoyoungRingPacket.parseSleepSegments(payload, startMs, endMs);

        Assert.assertEquals(4, segments.size());
        Assert.assertEquals(MoyoungRingPacket.SleepSegment.STATE_AWAKE, segments.get(0).state);
        Assert.assertEquals(MoyoungRingPacket.SleepSegment.STATE_LIGHT, segments.get(1).state);
        Assert.assertEquals(MoyoungRingPacket.SleepSegment.STATE_DEEP, segments.get(2).state);
        Assert.assertEquals(MoyoungRingPacket.SleepSegment.STATE_REM, segments.get(3).state);
        Assert.assertEquals(startMs, segments.get(0).startTimeMs);
        Assert.assertEquals(startMs + 30L * 60_000L, segments.get(1).startTimeMs);
        Assert.assertEquals(30 * 60, segments.get(0).durationSec);
        Assert.assertEquals(30 * 60, segments.get(1).durationSec);
        Assert.assertEquals(45 * 60, segments.get(2).durationSec);
        Assert.assertEquals(15 * 60, segments.get(3).durationSec);
    }

    @Test
    public void plausibleConstantsApplyDocumentedClamps() {
        Assert.assertFalse(MoyoungRingConstants.plausibleHr(0));
        Assert.assertFalse(MoyoungRingConstants.plausibleHr(255));
        Assert.assertFalse(MoyoungRingConstants.plausibleHr(0xFFFF));
        Assert.assertTrue(MoyoungRingConstants.plausibleHr(30));
        Assert.assertTrue(MoyoungRingConstants.plausibleHr(220));
        Assert.assertFalse(MoyoungRingConstants.plausibleHr(29));
        Assert.assertFalse(MoyoungRingConstants.plausibleHr(221));

        Assert.assertTrue(MoyoungRingConstants.plausibleSpo2(70));
        Assert.assertTrue(MoyoungRingConstants.plausibleSpo2(100));
        Assert.assertFalse(MoyoungRingConstants.plausibleSpo2(69));
        Assert.assertFalse(MoyoungRingConstants.plausibleSpo2(101));

        Assert.assertTrue(MoyoungRingConstants.plausibleHrv(3));
        Assert.assertTrue(MoyoungRingConstants.plausibleHrv(200));
        Assert.assertFalse(MoyoungRingConstants.plausibleHrv(0));
        Assert.assertFalse(MoyoungRingConstants.plausibleHrv(65535));
        Assert.assertFalse(MoyoungRingConstants.plausibleHrv(201));

        Assert.assertFalse(MoyoungRingConstants.plausibleStress(0));
        Assert.assertFalse(MoyoungRingConstants.plausibleStress(255));
        Assert.assertTrue(MoyoungRingConstants.plausibleStress(1));
        Assert.assertTrue(MoyoungRingConstants.plausibleStress(100));
        Assert.assertFalse(MoyoungRingConstants.plausibleStress(101));

        Assert.assertFalse(MoyoungRingConstants.plausibleBp(0, 80));
        Assert.assertFalse(MoyoungRingConstants.plausibleBp(120, 0));
        Assert.assertFalse(MoyoungRingConstants.plausibleBp(0xFFFF, 80));
        Assert.assertFalse(MoyoungRingConstants.plausibleBp(120, 0xFFFF));
        Assert.assertTrue(MoyoungRingConstants.plausibleBp(60, 30));
        Assert.assertTrue(MoyoungRingConstants.plausibleBp(250, 150));
        Assert.assertFalse(MoyoungRingConstants.plausibleBp(59, 80));
        Assert.assertFalse(MoyoungRingConstants.plausibleBp(251, 80));
        Assert.assertFalse(MoyoungRingConstants.plausibleBp(120, 29));
        Assert.assertFalse(MoyoungRingConstants.plausibleBp(120, 151));
    }

    private static byte[] record5(final int value, final long timestampSec) {
        final byte[] out = new byte[5];
        out[0] = (byte) value;
        writeU32Le(out, 1, timestampSec);
        return out;
    }

    private static byte[] hrvRecord(final int hrv, final long timestampSec) {
        final byte[] out = new byte[6];
        out[0] = (byte) hrv;
        out[1] = (byte) (hrv >> 8);
        writeU32Le(out, 2, timestampSec);
        return out;
    }

    private static void writeU32Le(final byte[] out, final int offset, final long value) {
        out[offset] = (byte) value;
        out[offset + 1] = (byte) (value >> 8);
        out[offset + 2] = (byte) (value >> 16);
        out[offset + 3] = (byte) (value >> 24);
    }

    private static byte[] concat(final byte[] first, final byte[] second) {
        final byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    @Test
    public void parseRealtimeU16Le_decodesTempAndHrv() {
        // Skin temp 0x0166 = 358 (=> 35.8 C after /10); HRV 0x0026 = 38 ms. Verified live.
        Assert.assertEquals(358, MoyoungRingPacket.parseRealtimeU16Le(new byte[]{0x66, 0x01}));
        Assert.assertEquals(38, MoyoungRingPacket.parseRealtimeU16Le(new byte[]{0x26, 0x00}));
    }

    @Test
    public void parseRealtimeU16Le_rejectsSentinelsAndShort() {
        Assert.assertEquals(-1, MoyoungRingPacket.parseRealtimeU16Le(new byte[]{(byte) 0xFF, (byte) 0xFF}));
        Assert.assertEquals(-1, MoyoungRingPacket.parseRealtimeU16Le(new byte[0]));
    }

    @Test
    public void decodeFrame_rejectsWrongThirdMagicByte() {
        final byte[] frame = MoyoungRingPacket.encode(0x01, 0x09, new byte[]{0x3C});
        frame[2] = 0x11; // corrupt the 0x10 length-mode marker
        Assert.assertNull(MoyoungRingPacket.decodeFrame(frame));
    }

    @Test
    public void reassembler_resyncsPastWrongThirdByte() {
        final byte[] good = MoyoungRingPacket.encode(0x01, 0x09, new byte[]{0x3C});
        final byte[] bad = good.clone();
        bad[2] = 0x11; // corrupted header: FD DA 11 ...
        final MoyoungRingPacket.Reassembler r = new MoyoungRingPacket.Reassembler();
        final List<MoyoungRingPacket> out = r.add(concat(bad, good));
        Assert.assertEquals(1, out.size());
        Assert.assertEquals(0x01, out.get(0).cmd);
        Assert.assertEquals(0x09, out.get(0).sub);
    }

    @Test
    public void enableTiming_buildsCmd1Frame() {
        // FD DA 10 07 01 06 01 = enable timed HR every 5 min (wire = minutes/5 = 1).
        Assert.assertArrayEquals(
                new byte[]{(byte) 0xFD, (byte) 0xDA, 0x10, 0x07, 0x01, 0x06, 0x01},
                MoyoungRingPacket.enableTiming(MoyoungRingConstants.SUB_ENABLE_HR, 5));
    }

    @Test
    public void setUserInfo_buildsCmd1Sub0() {
        // FD DA 10 0B 01 00 AF 46 1E 01 4B  (height175 weight70 age30 gender1 stepLen75)
        Assert.assertArrayEquals(
                new byte[]{(byte) 0xFD, (byte) 0xDA, 0x10, 0x0B, 0x01, 0x00,
                        (byte) 175, 70, 30, 1, 75},
                MoyoungRingPacket.setUserInfo(175, 70, 30, 1, 75));
    }
}
