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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class YcbtHealthRecordParserTest {
    private static final byte[] CAPTURED_BODY_RECORD = bytes(
            "1cf0de3103023e0505030402050530002a0c3700b00484030d000000"
    );
    private static final byte[] CAPTURED_HEART_RECORDS = bytes(
            "1cf0de3100471afede310042260cdf31003f3b1adf31003e4328df3100425136df31003c" +
                    "6444df3100419852df31003a"
    );
    private static final byte[] CAPTURED_ALL_RECORDS = bytes(
            "1cf0de31080d47734c620e3404000f00000033de1afede31000042704a610d2b02000f000000d24b260cdf3100003f70" +
                    "49610db106000f000000ce273b1adf3100003e6d49600c5f02000f00000077a54328df310000426f49610d2105000f00" +
                    "0000474b5136df3100003c6f47600c2104000f00000024f66444df310000416e49610d3d05000f00000015769852df31" +
                    "00003a6a465f0c8002000f000000d89d"
    );
    private static final byte[] CAPTURED_NIGHT = bytes(
            "affaa4019fe9de31bd58df31ffff971efb15733af29fe9de313c0500f1dceede312d0100f30af0de31d90100f2e4f1de31c9" +
                    "0400f1aef6de31320100f3e1f7de31c10100f2a3f9de31b50400f159fede319b0100f3f5ffde31b00100f2a501df31660200" +
                    "f10b04df313d0500f34809df31ae0800f2f611df31cc0100f1c213df31170200f3d915df317d0100f25617df31ef0000f345" +
                    "18df31060100f24b19df31670200f1b21bdf31910000f2431cdf31030000f3461cdf314c0000f2921cdf31f70100f3891edf" +
                    "31720200f2fb20df31e00000f3db21df31590100f23423df310e0100f34224df31d30100f21526df317a0000f38f26df31c1" +
                    "0000f25027df31be0400f10f2cdf312f0100f33f2ddf31aa0100f2ea2edf317a0500f16534df316b0100f3d135df319e0000" +
                    "f17036df319e0100f20e38df31450500f1543ddf318b0100f3e03edf31de0100f2bf40df31000500f1c045df315e0100f31f" +
                    "47df31730100f29348df31a00000f13449df319c0000f2d049df316d0500f13e4fdf31410100f38050df31d80100f25952df" +
                    "31050100f15f53df311e0100f27d54df31400400"
    );

    @Test
    public void parsesActivityAndDropsEmptyBucket() {
        final List<YcbtHealthRecordParser.Record> records = parse(
                YcbtHealthRecordParser.HISTORY_ACTIVITY,
                "1cf0de31a0f3de318002e00119001afede319e01df31000000000000"
        );

        assertEquals(1, records.size());
        final YcbtHealthRecordParser.ActivityRecord activity =
                (YcbtHealthRecordParser.ActivityRecord) records.get(0);
        assertEquals(Instant.parse("2026-07-06T23:00:44Z"), activity.getTimestamp());
        assertEquals(640, activity.getSteps());
        assertEquals(480, activity.getDistanceMeters());
    }

    @Test
    public void parsesEveryCapturedHeartRateRecord() {
        final List<YcbtHealthRecordParser.Record> records = YcbtHealthRecordParser.parse(
                YcbtHealthRecordParser.HISTORY_HEART_RATE,
                CAPTURED_HEART_RECORDS,
                ZoneOffset.UTC
        );

        assertEquals(Arrays.asList(71.0, 66.0, 63.0, 62.0, 66.0, 60.0, 65.0, 58.0),
                values(records, YcbtHealthRecordParser.MeasurementKind.HEART_RATE));
    }

    @Test
    public void parsesBloodPressureAndAssociatedHeartRate() {
        final List<YcbtHealthRecordParser.Record> records = parse(
                YcbtHealthRecordParser.HISTORY_BLOOD_PRESSURE,
                "1cf0de3101764f401afede3100000000"
        );

        assertEquals(2, records.size());
        final YcbtHealthRecordParser.BloodPressureRecord bloodPressure =
                (YcbtHealthRecordParser.BloodPressureRecord) records.get(0);
        assertEquals(118, bloodPressure.getSystolic());
        assertEquals(79, bloodPressure.getDiastolic());
        assertEquals(Arrays.asList(64.0),
                values(records, YcbtHealthRecordParser.MeasurementKind.HEART_RATE));
    }

    @Test
    public void parsesCapturedCombinedVitalsWithoutActivity() {
        final List<YcbtHealthRecordParser.Record> records = YcbtHealthRecordParser.parse(
                YcbtHealthRecordParser.HISTORY_COMBINED_VITALS,
                CAPTURED_ALL_RECORDS,
                ZoneOffset.UTC
        );

        assertEquals(Arrays.asList(98.0, 97.0, 97.0, 96.0, 97.0, 96.0, 97.0, 95.0),
                values(records, YcbtHealthRecordParser.MeasurementKind.SPO2));
        assertEquals(Arrays.asList(52.0, 43.0, 177.0, 95.0, 33.0, 33.0, 61.0, 128.0),
                values(records, YcbtHealthRecordParser.MeasurementKind.HRV));
        assertEquals(Arrays.asList(14.0, 13.0, 13.0, 12.0, 13.0, 12.0, 13.0, 12.0),
                values(records, YcbtHealthRecordParser.MeasurementKind.RESPIRATORY_RATE));
        assertEquals(Arrays.asList(115, 112, 112, 109, 111, 111, 110, 106),
                systolicValues(records));
        assertTrue(values(records, YcbtHealthRecordParser.MeasurementKind.TEMPERATURE).isEmpty());
        assertTrue(values(records, YcbtHealthRecordParser.MeasurementKind.BLOOD_SUGAR).isEmpty());
        assertEquals(32, records.size());
    }

    @Test
    public void parsesCombinedTemperatureAndBloodSugar() {
        final List<YcbtHealthRecordParser.Record> records = parse(
                YcbtHealthRecordParser.HISTORY_COMBINED_VITALS,
                "1cf0de31721046764f610f3a0324061504370000"
        );

        assertEquals(36.6,
                values(records, YcbtHealthRecordParser.MeasurementKind.TEMPERATURE).get(0), 0.001);
        assertEquals(99.088,
                values(records, YcbtHealthRecordParser.MeasurementKind.BLOOD_SUGAR).get(0), 0.001);
    }

    @Test
    public void parsesSpo2TemperatureAndBloodSugarCatalogRecords() {
        final List<YcbtHealthRecordParser.Record> spo2 = parse(
                YcbtHealthRecordParser.HISTORY_SPO2,
                "1cf0de3100611afede310100260cdf31005f"
        );
        assertEquals(Arrays.asList(97.0, 95.0),
                values(spo2, YcbtHealthRecordParser.MeasurementKind.SPO2));

        final List<YcbtHealthRecordParser.Record> temperature = parse(
                YcbtHealthRecordParser.HISTORY_TEMPERATURE,
                "1cf0de310024051afede31002419260cdf3100000f"
        );
        assertEquals(Arrays.asList(36.5, 36.25),
                values(temperature, YcbtHealthRecordParser.MeasurementKind.TEMPERATURE));

        final List<YcbtHealthRecordParser.Record> sugar = parse(
                YcbtHealthRecordParser.HISTORY_BLOOD_SUGAR,
                "1cf0de3101050500000000000000000000000000000000000000000000000000000000000000000000000000" +
                        "1afede31010000000000000000000000000000000000000000000000000000000000000000000000000000000000"
        );
        assertEquals(1, sugar.size());
        assertEquals(99.088,
                values(sugar, YcbtHealthRecordParser.MeasurementKind.BLOOD_SUGAR).get(0), 0.001);
    }

    @Test
    public void parsesBodyDataScoresUsingTheirDistinctComposites() {
        final List<YcbtHealthRecordParser.Record> records = YcbtHealthRecordParser.parse(
                YcbtHealthRecordParser.HISTORY_BODY_DATA,
                CAPTURED_BODY_RECORD,
                ZoneOffset.UTC
        );

        assertEquals(Arrays.asList(62.5), values(records, YcbtHealthRecordParser.MeasurementKind.HRV));
        assertEquals(Arrays.asList(53.0), values(records, YcbtHealthRecordParser.MeasurementKind.STRESS));
        assertEquals(Arrays.asList(42.0), values(records, YcbtHealthRecordParser.MeasurementKind.FATIGUE));
        assertEquals(Arrays.asList(42.0), values(records, YcbtHealthRecordParser.MeasurementKind.VO2_MAX));
        assertEquals(Instant.parse("2026-07-06T23:00:44Z"), records.get(0).getTimestamp());
    }

    @Test
    public void parsesCapturedSleepStages() {
        final List<YcbtHealthRecordParser.Record> records = YcbtHealthRecordParser.parse(
                YcbtHealthRecordParser.HISTORY_SLEEP,
                CAPTURED_NIGHT,
                ZoneOffset.UTC
        );

        assertEquals(1, records.size());
        final YcbtHealthRecordParser.SleepRecord sleep =
                (YcbtHealthRecordParser.SleepRecord) records.get(0);
        assertWithin(93, count(sleep.getStages(), YcbtHealthRecordParser.SleepStage.DEEP), 3);
        assertWithin(249, count(sleep.getStages(), YcbtHealthRecordParser.SleepStage.LIGHT), 3);
        assertWithin(130, count(sleep.getStages(), YcbtHealthRecordParser.SleepStage.REM), 3);
        assertFalse(sleep.getStages().contains(YcbtHealthRecordParser.SleepStage.AWAKE));
        assertTrue(sleep.isCompleteSession());
    }

    @Test
    public void boundsCorruptSleepDurationAndDeduplicatesSegmentStarts() {
        final byte[] corruptDuration = sleepSession(new int[][]{
                {0xf2, 0x00ffffff},
                {0xf1, 60 * 60}
        });
        final YcbtHealthRecordParser.SleepRecord bounded = (YcbtHealthRecordParser.SleepRecord)
                YcbtHealthRecordParser.parse(
                        YcbtHealthRecordParser.HISTORY_SLEEP,
                        corruptDuration,
                        ZoneOffset.UTC
                ).get(0);
        assertEquals(24 * 60, bounded.getStages().size());
        assertEquals(24 * 60, count(bounded.getStages(), YcbtHealthRecordParser.SleepStage.LIGHT));

        final byte[] repeated = sleepSession(new int[][]{
                {0xf2, 30 * 60},
                {0xf1, 30 * 60},
                {0xf3, 30 * 60},
                {0xf4, 30 * 60}
        });
        System.arraycopy(repeated, 20 + 8, repeated, 20 + 3 * 8, 8);
        final YcbtHealthRecordParser.SleepRecord deduplicated = (YcbtHealthRecordParser.SleepRecord)
                YcbtHealthRecordParser.parse(
                        YcbtHealthRecordParser.HISTORY_SLEEP,
                        repeated,
                        ZoneOffset.UTC
                ).get(0);
        assertEquals(90, deduplicated.getStages().size());
        assertEquals(30, count(deduplicated.getStages(), YcbtHealthRecordParser.SleepStage.DEEP));
    }

    @Test
    public void clampsTruncatedSleepSessionToAvailableSegments() {
        final byte[] full = sleepSession(new int[][]{{0xf2, 600}, {0xf1, 600}});
        final byte[] truncated = Arrays.copyOf(full, full.length - 8);

        final YcbtHealthRecordParser.SleepRecord sleep = (YcbtHealthRecordParser.SleepRecord)
                YcbtHealthRecordParser.parse(
                        YcbtHealthRecordParser.HISTORY_SLEEP,
                        truncated,
                        ZoneOffset.UTC
                ).get(0);
        assertEquals(10, sleep.getStages().size());
        assertFalse(sleep.isCompleteSession());
    }

    @Test
    public void rejectsImplausibleMeasurementsAndPartialRecords() {
        assertTrue(parse(YcbtHealthRecordParser.HISTORY_HEART_RATE,
                "1cf0de31001d1afede3100ff260cdf3100").isEmpty());
        assertTrue(parse(YcbtHealthRecordParser.HISTORY_SPO2,
                "1cf0de3100451afede310065").isEmpty());
        assertTrue(parse(YcbtHealthRecordParser.HISTORY_TEMPERATURE,
                "1cf0de3100ff01").isEmpty());
        assertTrue(parse(YcbtHealthRecordParser.HISTORY_ACTIVITY,
                "1cf0de3100000000891389170000").isEmpty());
        assertTrue(parse(YcbtHealthRecordParser.HISTORY_BLOOD_PRESSURE,
                "1cf0de3100ffff00").isEmpty());
        assertTrue(parse(YcbtHealthRecordParser.HISTORY_BODY_DATA,
                "1cf0de31000000000a010a010000000000000000000000000000000000").isEmpty());
    }

    @Test
    public void returnedRecordsAndSleepStagesAreImmutable() {
        final List<YcbtHealthRecordParser.Record> records = YcbtHealthRecordParser.parse(
                YcbtHealthRecordParser.HISTORY_SLEEP,
                sleepSession(new int[][]{{0xf4, 60}}),
                ZoneOffset.UTC
        );
        try {
            records.clear();
            fail("Expected immutable records");
        } catch (final UnsupportedOperationException expected) {
            // Expected.
        }

        final YcbtHealthRecordParser.SleepRecord sleep =
                (YcbtHealthRecordParser.SleepRecord) records.get(0);
        assertEquals(Arrays.asList(YcbtHealthRecordParser.SleepStage.AWAKE), sleep.getStages());
        try {
            sleep.getStages().clear();
            fail("Expected immutable sleep stages");
        } catch (final UnsupportedOperationException expected) {
            // Expected.
        }
    }

    private static List<YcbtHealthRecordParser.Record> parse(final int key, final String hex) {
        return YcbtHealthRecordParser.parse(key, bytes(hex), ZoneOffset.UTC);
    }

    private static List<Double> values(final List<YcbtHealthRecordParser.Record> records,
                                       final YcbtHealthRecordParser.MeasurementKind kind) {
        final List<Double> values = new ArrayList<>();
        for (final YcbtHealthRecordParser.Record record : records) {
            if (record instanceof YcbtHealthRecordParser.MeasurementRecord) {
                final YcbtHealthRecordParser.MeasurementRecord measurement =
                        (YcbtHealthRecordParser.MeasurementRecord) record;
                if (measurement.getKind() == kind) {
                    values.add(measurement.getValue());
                }
            }
        }
        return values;
    }

    private static List<Integer> systolicValues(final List<YcbtHealthRecordParser.Record> records) {
        final List<Integer> values = new ArrayList<>();
        for (final YcbtHealthRecordParser.Record record : records) {
            if (record instanceof YcbtHealthRecordParser.BloodPressureRecord) {
                values.add(((YcbtHealthRecordParser.BloodPressureRecord) record).getSystolic());
            }
        }
        return values;
    }

    private static int count(final List<YcbtHealthRecordParser.SleepStage> stages,
                             final YcbtHealthRecordParser.SleepStage expected) {
        int count = 0;
        for (final YcbtHealthRecordParser.SleepStage stage : stages) {
            if (stage == expected) {
                count++;
            }
        }
        return count;
    }

    private static void assertWithin(final int expected, final int actual, final int tolerance) {
        assertTrue("Expected " + actual + " within " + tolerance + " of " + expected,
                Math.abs(expected - actual) <= tolerance);
    }

    private static byte[] sleepSession(final int[][] segments) {
        final int recordLength = 20 + segments.length * 8;
        final ByteArrayOutputStream output = new ByteArrayOutputStream(recordLength);
        output.write(0xaf);
        output.write(0xfa);
        output.write(recordLength & 0xff);
        output.write((recordLength >> 8) & 0xff);
        for (int index = 0; index < 16; index++) {
            output.write(0);
        }
        for (int index = 0; index < segments.length; index++) {
            final long start = 0x31def01cL + index * 3_600L;
            output.write(segments[index][0]);
            output.write((int) (start & 0xff));
            output.write((int) ((start >> 8) & 0xff));
            output.write((int) ((start >> 16) & 0xff));
            output.write((int) ((start >> 24) & 0xff));
            output.write(segments[index][1] & 0xff);
            output.write((segments[index][1] >> 8) & 0xff);
            output.write((segments[index][1] >> 16) & 0xff);
        }
        return output.toByteArray();
    }

    private static byte[] bytes(final String hex) {
        final String clean = hex.replace(" ", "");
        if ((clean.length() & 1) != 0) {
            throw new IllegalArgumentException("Hex string must contain complete bytes");
        }
        final byte[] bytes = new byte[clean.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(clean.substring(index * 2, index * 2 + 2), 16);
        }
        return bytes;
    }
}
