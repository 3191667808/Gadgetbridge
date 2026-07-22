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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure decoder for YCBT health history payloads. */
public final class YcbtHealthRecordParser {
    public static final int HISTORY_ACTIVITY = 0x02;
    public static final int HISTORY_SLEEP = 0x04;
    public static final int HISTORY_HEART_RATE = 0x06;
    public static final int HISTORY_BLOOD_PRESSURE = 0x08;
    public static final int HISTORY_COMBINED_VITALS = 0x09;
    public static final int HISTORY_SPO2 = 0x1a;
    public static final int HISTORY_TEMPERATURE = 0x1e;
    public static final int HISTORY_BLOOD_SUGAR = 0x2f;
    public static final int HISTORY_BODY_DATA = 0x33;

    private static final long YCBT_EPOCH_OFFSET_SECONDS = 946_684_800L;
    private static final int MAX_SLEEP_SESSION_MINUTES = 24 * 60;
    private static final int TEMPERATURE_FILLER = 15;
    private static final double MGDL_PER_MMOL = 18.016;

    private YcbtHealthRecordParser() {
    }

    public static List<Record> parse(final int historyKey, final byte[] payload) {
        return parse(historyKey, payload, ZoneId.systemDefault());
    }

    public static List<Record> parse(final int historyKey, final byte[] payload, final ZoneId zoneId) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload must not be null");
        }
        if (zoneId == null) {
            throw new IllegalArgumentException("Zone ID must not be null");
        }

        final List<Record> records;
        switch (historyKey) {
            case HISTORY_ACTIVITY:
                records = parseActivity(payload, zoneId);
                break;
            case HISTORY_SLEEP:
                records = parseSleep(payload, zoneId);
                break;
            case HISTORY_HEART_RATE:
                records = parseHeartRate(payload, zoneId);
                break;
            case HISTORY_BLOOD_PRESSURE:
                records = parseBloodPressure(payload, zoneId);
                break;
            case HISTORY_COMBINED_VITALS:
                records = parseCombinedVitals(payload, zoneId);
                break;
            case HISTORY_SPO2:
                records = parseSpo2(payload, zoneId);
                break;
            case HISTORY_TEMPERATURE:
                records = parseTemperature(payload, zoneId);
                break;
            case HISTORY_BLOOD_SUGAR:
                records = parseBloodSugar(payload, zoneId);
                break;
            case HISTORY_BODY_DATA:
                records = parseBodyData(payload, zoneId);
                break;
            default:
                return Collections.emptyList();
        }
        return Collections.unmodifiableList(records);
    }

    private static List<Record> parseActivity(final byte[] payload, final ZoneId zoneId) {
        final List<Record> records = new ArrayList<>();
        for (int offset = 0; offset + 14 <= payload.length; offset += 14) {
            final int steps = readUnsignedShort(payload, offset + 8);
            final int distanceMeters = readUnsignedShort(payload, offset + 10);
            if ((steps > 0 || distanceMeters > 0)
                    && steps <= 5_000
                    && distanceMeters <= 6_000) {
                records.add(new ActivityRecord(timestamp(payload, offset, zoneId), steps, distanceMeters));
            }
        }
        return records;
    }

    private static List<Record> parseHeartRate(final byte[] payload, final ZoneId zoneId) {
        final List<Record> records = new ArrayList<>();
        for (int offset = 0; offset + 6 <= payload.length; offset += 6) {
            final int bpm = unsigned(payload[offset + 5]);
            if (between(bpm, 30, 220)) {
                records.add(new MeasurementRecord(
                        timestamp(payload, offset, zoneId),
                        MeasurementKind.HEART_RATE,
                        bpm
                ));
            }
        }
        return records;
    }

    private static List<Record> parseBloodPressure(final byte[] payload, final ZoneId zoneId) {
        final List<Record> records = new ArrayList<>();
        for (int offset = 0; offset + 8 <= payload.length; offset += 8) {
            final Instant timestamp = timestamp(payload, offset, zoneId);
            addBloodPressure(
                    records,
                    timestamp,
                    unsigned(payload[offset + 5]),
                    unsigned(payload[offset + 6])
            );
            final int bpm = unsigned(payload[offset + 7]);
            if (between(bpm, 30, 220)) {
                records.add(new MeasurementRecord(timestamp, MeasurementKind.HEART_RATE, bpm));
            }
        }
        return records;
    }

    private static List<Record> parseCombinedVitals(final byte[] payload, final ZoneId zoneId) {
        final List<Record> records = new ArrayList<>();
        for (int offset = 0; offset + 20 <= payload.length; offset += 20) {
            final Instant timestamp = timestamp(payload, offset, zoneId);
            addBloodPressure(
                    records,
                    timestamp,
                    unsigned(payload[offset + 7]),
                    unsigned(payload[offset + 8])
            );
            addMeasurement(records, timestamp, MeasurementKind.SPO2,
                    unsigned(payload[offset + 9]), 70, 100);
            addMeasurement(records, timestamp, MeasurementKind.RESPIRATORY_RATE,
                    unsigned(payload[offset + 10]), 5, 60);
            addMeasurement(records, timestamp, MeasurementKind.HRV,
                    unsigned(payload[offset + 11]), 1, 300);
            addTemperature(
                    records,
                    timestamp,
                    unsigned(payload[offset + 13]),
                    unsigned(payload[offset + 14])
            );

            final int bloodSugarTenths = unsigned(payload[offset + 17]);
            if (bloodSugarTenths > 0) {
                addBloodSugar(records, timestamp, bloodSugarTenths);
            }
        }
        return records;
    }

    private static List<Record> parseSpo2(final byte[] payload, final ZoneId zoneId) {
        final List<Record> records = new ArrayList<>();
        for (int offset = 0; offset + 6 <= payload.length; offset += 6) {
            addMeasurement(
                    records,
                    timestamp(payload, offset, zoneId),
                    MeasurementKind.SPO2,
                    unsigned(payload[offset + 5]),
                    70,
                    100
            );
        }
        return records;
    }

    private static List<Record> parseTemperature(final byte[] payload, final ZoneId zoneId) {
        final List<Record> records = new ArrayList<>();
        for (int offset = 0; offset + 7 <= payload.length; offset += 7) {
            addTemperature(
                    records,
                    timestamp(payload, offset, zoneId),
                    unsigned(payload[offset + 5]),
                    unsigned(payload[offset + 6])
            );
        }
        return records;
    }

    private static List<Record> parseBloodSugar(final byte[] payload, final ZoneId zoneId) {
        final List<Record> records = new ArrayList<>();
        for (int offset = 0; offset + 44 <= payload.length; offset += 44) {
            final int tenthsOfMmol = unsigned(payload[offset + 5]) * 10
                    + unsigned(payload[offset + 6]);
            if (tenthsOfMmol > 0) {
                addBloodSugar(records, timestamp(payload, offset, zoneId), tenthsOfMmol);
            }
        }
        return records;
    }

    private static List<Record> parseBodyData(final byte[] payload, final ZoneId zoneId) {
        final List<Record> records = new ArrayList<>();
        for (int offset = 0; offset + 28 <= payload.length; offset += 28) {
            final Instant timestamp = timestamp(payload, offset, zoneId);
            final int hrvInteger = unsigned(payload[offset + 6]);
            if (hrvInteger > 0) {
                addMeasurement(
                        records,
                        timestamp,
                        MeasurementKind.HRV,
                        decimalComposite(hrvInteger, unsigned(payload[offset + 7])),
                        1,
                        300
                );
            }

            final int stressInteger = unsigned(payload[offset + 8]);
            if (stressInteger > 0) {
                addMeasurement(
                        records,
                        timestamp,
                        MeasurementKind.STRESS,
                        digitComposite(stressInteger, unsigned(payload[offset + 9])),
                        1,
                        100
                );
            }

            final int fatigueInteger = unsigned(payload[offset + 10]);
            if (fatigueInteger > 0) {
                addMeasurement(
                        records,
                        timestamp,
                        MeasurementKind.FATIGUE,
                        digitComposite(fatigueInteger, unsigned(payload[offset + 11])),
                        1,
                        100
                );
            }

            addMeasurement(
                    records,
                    timestamp,
                    MeasurementKind.VO2_MAX,
                    unsigned(payload[offset + 16]),
                    1,
                    100
            );
        }
        return records;
    }

    private static List<Record> parseSleep(final byte[] payload, final ZoneId zoneId) {
        final int headerLength = 20;
        final int segmentLength = 8;
        final List<Record> records = new ArrayList<>();
        int cursor = 0;
        while (cursor + headerLength <= payload.length) {
            final int recordLength = readUnsignedShort(payload, cursor + 2);
            final int segmentsStart = cursor + headerLength;
            final int declaredSegments = Math.max(0, recordLength - headerLength) / segmentLength;
            final int availableSegments = (payload.length - segmentsStart) / segmentLength;
            final int segmentCount = Math.min(declaredSegments, availableSegments);
            final boolean completeSession = recordLength >= headerLength
                    && recordLength <= payload.length - cursor
                    && (recordLength - headerLength) % segmentLength == 0;

            final List<SleepSegment> segments = new ArrayList<>();
            final Set<Long> seenStarts = new HashSet<>();
            int totalMinutes = 0;
            for (int index = 0; index < segmentCount; index++) {
                final int offset = segmentsStart + index * segmentLength;
                final SleepStage stage = sleepStage(unsigned(payload[offset]));
                if (stage == null) {
                    continue;
                }
                final long segmentStart = readUnsignedInt(payload, offset + 1);
                if (!seenStarts.add(segmentStart)) {
                    continue;
                }
                final int remaining = MAX_SLEEP_SESSION_MINUTES - totalMinutes;
                if (remaining <= 0) {
                    break;
                }
                final int segmentSeconds = readUnsigned24(payload, offset + 5);
                final int minutes = Math.max(1, Math.min(remaining, (int) Math.round(segmentSeconds / 60.0)));
                segments.add(new SleepSegment(timestamp(segmentStart, zoneId), stage, minutes));
                totalMinutes += minutes;
            }

            if (!segments.isEmpty()) {
                segments.sort((left, right) -> left.getTimestamp().compareTo(right.getTimestamp()));
                records.add(new SleepRecord(segments, completeSession));
            }
            cursor = segmentsStart + segmentCount * segmentLength;
        }
        return records;
    }

    private static SleepStage sleepStage(final int tag) {
        switch (tag & 0x0f) {
            case 1:
                return SleepStage.DEEP;
            case 2:
                return SleepStage.LIGHT;
            case 3:
                return SleepStage.REM;
            case 4:
                return SleepStage.AWAKE;
            case 5:
                return SleepStage.UNKNOWN;
            default:
                return null;
        }
    }

    private static void addBloodPressure(final List<Record> records,
                                         final Instant timestamp,
                                         final int systolic,
                                         final int diastolic) {
        if (between(systolic, 60, 250) && between(diastolic, 30, 150)) {
            records.add(new BloodPressureRecord(timestamp, systolic, diastolic));
        }
    }

    private static void addTemperature(final List<Record> records,
                                       final Instant timestamp,
                                       final int integer,
                                       final int fraction) {
        if (integer <= 0 || fraction == TEMPERATURE_FILLER) {
            return;
        }
        addMeasurement(
                records,
                timestamp,
                MeasurementKind.TEMPERATURE,
                decimalComposite(integer, fraction),
                30,
                45
        );
    }

    private static void addBloodSugar(final List<Record> records,
                                      final Instant timestamp,
                                      final int tenthsOfMmol) {
        addMeasurement(
                records,
                timestamp,
                MeasurementKind.BLOOD_SUGAR,
                tenthsOfMmol / 10.0 * MGDL_PER_MMOL,
                20,
                600
        );
    }

    private static void addMeasurement(final List<Record> records,
                                       final Instant timestamp,
                                       final MeasurementKind kind,
                                       final double value,
                                       final double minimum,
                                       final double maximum) {
        if (Double.isFinite(value) && value >= minimum && value <= maximum) {
            records.add(new MeasurementRecord(timestamp, kind, value));
        }
    }

    private static boolean between(final int value, final int minimum, final int maximum) {
        return value >= minimum && value <= maximum;
    }

    private static double decimalComposite(final int integer, final int fraction) {
        return Double.parseDouble(integer + "." + fraction);
    }

    private static double digitComposite(final int integer, final int fraction) {
        return Double.parseDouble(Integer.toString(integer) + fraction);
    }

    private static Instant timestamp(final byte[] payload, final int offset, final ZoneId zoneId) {
        return timestamp(readUnsignedInt(payload, offset), zoneId);
    }

    private static Instant timestamp(final long ringSeconds, final ZoneId zoneId) {
        final LocalDateTime localDateTime = LocalDateTime.ofEpochSecond(
                ringSeconds + YCBT_EPOCH_OFFSET_SECONDS,
                0,
                ZoneOffset.UTC
        );
        return localDateTime.atZone(zoneId).toInstant();
    }

    private static int readUnsignedShort(final byte[] data, final int offset) {
        return unsigned(data[offset]) | (unsigned(data[offset + 1]) << 8);
    }

    private static int readUnsigned24(final byte[] data, final int offset) {
        return unsigned(data[offset])
                | (unsigned(data[offset + 1]) << 8)
                | (unsigned(data[offset + 2]) << 16);
    }

    private static long readUnsignedInt(final byte[] data, final int offset) {
        return unsigned(data[offset])
                | ((long) unsigned(data[offset + 1]) << 8)
                | ((long) unsigned(data[offset + 2]) << 16)
                | ((long) unsigned(data[offset + 3]) << 24);
    }

    private static int unsigned(final byte value) {
        return value & 0xff;
    }

    public interface Record {
        Instant getTimestamp();
    }

    public enum MeasurementKind {
        HEART_RATE,
        SPO2,
        RESPIRATORY_RATE,
        HRV,
        TEMPERATURE,
        BLOOD_SUGAR,
        STRESS,
        FATIGUE,
        VO2_MAX
    }

    public enum SleepStage {
        LIGHT,
        DEEP,
        REM,
        AWAKE,
        UNKNOWN
    }

    public static final class ActivityRecord implements Record {
        private final Instant timestamp;
        private final int steps;
        private final int distanceMeters;

        private ActivityRecord(final Instant timestamp, final int steps, final int distanceMeters) {
            this.timestamp = timestamp;
            this.steps = steps;
            this.distanceMeters = distanceMeters;
        }

        @Override
        public Instant getTimestamp() {
            return timestamp;
        }

        public int getSteps() {
            return steps;
        }

        public int getDistanceMeters() {
            return distanceMeters;
        }
    }

    public static final class SleepRecord implements Record {
        private final Instant timestamp;
        private final List<SleepSegment> segments;
        private final List<SleepStage> stages;
        private final boolean completeSession;

        private SleepRecord(final List<SleepSegment> segments,
                            final boolean completeSession) {
            this.timestamp = segments.get(0).getTimestamp();
            this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
            final List<SleepStage> expandedStages = new ArrayList<>();
            for (final SleepSegment segment : segments) {
                for (int minute = 0; minute < segment.getDurationMinutes(); minute++) {
                    expandedStages.add(segment.getStage());
                }
            }
            this.stages = Collections.unmodifiableList(expandedStages);
            this.completeSession = completeSession;
        }

        @Override
        public Instant getTimestamp() {
            return timestamp;
        }

        public List<SleepStage> getStages() {
            return stages;
        }

        public List<SleepSegment> getSegments() {
            return segments;
        }

        public Instant getEndTimestamp() {
            Instant end = timestamp;
            for (final SleepSegment segment : segments) {
                final Instant segmentEnd = segment.getTimestamp().plusSeconds(segment.getDurationMinutes() * 60L);
                if (segmentEnd.isAfter(end)) {
                    end = segmentEnd;
                }
            }
            return end;
        }

        public boolean isCompleteSession() {
            return completeSession;
        }
    }

    public static final class SleepSegment {
        private final Instant timestamp;
        private final SleepStage stage;
        private final int durationMinutes;

        private SleepSegment(final Instant timestamp, final SleepStage stage, final int durationMinutes) {
            this.timestamp = timestamp;
            this.stage = stage;
            this.durationMinutes = durationMinutes;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public SleepStage getStage() {
            return stage;
        }

        public int getDurationMinutes() {
            return durationMinutes;
        }
    }

    public static final class MeasurementRecord implements Record {
        private final Instant timestamp;
        private final MeasurementKind kind;
        private final double value;

        private MeasurementRecord(final Instant timestamp,
                                  final MeasurementKind kind,
                                  final double value) {
            this.timestamp = timestamp;
            this.kind = kind;
            this.value = value;
        }

        @Override
        public Instant getTimestamp() {
            return timestamp;
        }

        public MeasurementKind getKind() {
            return kind;
        }

        public double getValue() {
            return value;
        }
    }

    public static final class BloodPressureRecord implements Record {
        private final Instant timestamp;
        private final int systolic;
        private final int diastolic;

        private BloodPressureRecord(final Instant timestamp, final int systolic, final int diastolic) {
            this.timestamp = timestamp;
            this.systolic = systolic;
            this.diastolic = diastolic;
        }

        @Override
        public Instant getTimestamp() {
            return timestamp;
        }

        public int getSystolic() {
            return systolic;
        }

        public int getDiastolic() {
            return diastolic;
        }
    }
}
