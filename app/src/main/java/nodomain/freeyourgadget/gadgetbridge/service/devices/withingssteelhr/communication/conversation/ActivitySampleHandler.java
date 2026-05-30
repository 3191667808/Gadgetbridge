/*  Copyright (C) 2023-2024 Frank Ertl

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericRespiratoryRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.AbstractWithingsActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericRespiratoryRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.WithingsBaseDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.activity.ActivityEntry;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.activity.WithingsActivityType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.WorkoutType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ActivitySampleCalories;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ActivitySampleCalories2;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ActivitySampleDuration;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ActivitySampleMovement;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ActivitySampleSleep;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ActivitySampleTime;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ActivitySampleUnknown;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ActivitySampleWalk;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ActivityHeartrate;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.VasistasSpo2;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.VasistasRespiratoryRate;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.WithingsStructure;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.WithingsStructureType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.Message;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.WithingsMessageType;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class ActivitySampleHandler extends AbstractResponseHandler {

    private static final Logger logger = LoggerFactory.getLogger(ActivitySampleHandler.class);
    private ActivityEntry activityEntry;
    private List<ActivityEntry> activityEntries = new ArrayList<>();
    private List<ActivityEntry> heartrateEntries = new ArrayList<>();
    private final List<long[]> spo2Entries = new ArrayList<>();
    private final List<long[]> respiratoryRateEntries = new ArrayList<>();

    public ActivitySampleHandler(WithingsBaseDeviceSupport support) {
        super(support);
    }

    @Override
    public void handleResponse(Message response) {
        List<WithingsStructure> data = response.getDataStructures();
        if (data != null) {
            handleActivityData(data, response.getType());
        }
    }

    public void onSyncFinished() {
        mergeHeartrateSamplesIntoActivitySammples();
        saveData();
    }

    private void handleActivityData(List<WithingsStructure> dataList, short activityType) {
        for (WithingsStructure data : dataList) {
            switch (data.getType()) {
                case WithingsStructureType.ACTIVITY_SAMPLE_TIME:
                    handleTimestamp(data, activityType);
                    break;
                case WithingsStructureType.ACTIVITY_SAMPLE_DURATION:
                    handleDuration(data);
                    break;
                case WithingsStructureType.ACTIVITY_SAMPLE_MOVEMENT:
                    handleMovement(data);
                    break;
                case WithingsStructureType.ACTIVITY_SAMPLE_CALORIES:
                    handleCalories1(data);
                    break;
                case WithingsStructureType.ACTIVITY_SAMPLE_CALORIES_2:
                    handleCalories2(data);
                    break;
                case WithingsStructureType.ACTIVITY_SAMPLE_SLEEP:
                    handleSleep(data);
                    break;
                case WithingsStructureType.ACTIVITY_SAMPLE_WALK:
                    handleWalk(data);
                    break;
                case WithingsStructureType.ACTIVITY_SAMPLE_RUN:
                    handleRun(data);
                    break;
                case WithingsStructureType.ACTIVITY_SAMPLE_SWIM:
                    handleSwim(data);
                    break;
                case WithingsStructureType.ACTIVITY_HR:
                    handleHeartrate(data);
                    break;
                case WithingsStructureType.WORKOUT_TYPE:
                    handleWorkoutType(data);
                    break;
                case WithingsStructureType.VASISTAS_SPO2:
                    handleSpo2(data);
                    break;
                case WithingsStructureType.VASISTAS_RESPIRATORY_RATE:
                    handleRespiratoryRate(data);
                    break;
                default:
                    logUnhandledActivityData(data);
            }
        }

        if (activityEntry != null) {
            addToList(activityEntry);
        }

    }

    private void handleTimestamp(WithingsStructure data, short activityType) {
        if (activityEntry != null) {
            addToList(activityEntry);
        }

        activityEntry = new ActivityEntry();
        activityEntry.setIsHeartrate(activityType == WithingsMessageType.GET_HEARTRATE_SAMPLES);
        activityEntry.setTimestamp((int) (((ActivitySampleTime) data).getDate().getTime() / 1000));
    }

    private void handleWorkoutType(WithingsStructure data) {
        WithingsActivityType activityType = WithingsActivityType.fromCode(((WorkoutType) data).getActivityType());
        activityEntry.setRawKind(activityType.toActivityKind().getCode());
    }

    private void handleDuration(WithingsStructure data) {
        activityEntry.setDuration(((ActivitySampleDuration) data).getDuration());
    }

    private void handleHeartrate(WithingsStructure data) {
        activityEntry.setIsHeartrate(((ActivityHeartrate) data).getHeartrate());
    }

    private void handleSpo2(final WithingsStructure data) {
        if (activityEntry == null) {
            logger.info("Received Withings SpO2 vasistas without timestamp context: {}", GB.hexdump(data.getRawData()));
            return;
        }

        final VasistasSpo2 vasistasSpo2 = (VasistasSpo2) data;
        final int spo2 = vasistasSpo2.getSpo2Percent();
        if (spo2 < 1 || spo2 > 100) {
            logger.info("Skipping Withings SpO2 vasistas with invalid value: ts={} spo2Tenths={} pulse={} status={}",
                    activityEntry.getTimestamp(),
                    vasistasSpo2.getSpo2DeciPercent(),
                    vasistasSpo2.getPulseRate(),
                    vasistasSpo2.getStatus());
            return;
        }

        final long timestampMs = activityEntry.getTimestamp() * 1000L;
        spo2Entries.add(new long[]{timestampMs, spo2});
        logger.info("Collected Withings SpO2 vasistas sample: ts={} spo2={} pulse={} status={}",
                timestampMs,
                spo2,
                vasistasSpo2.getPulseRate(),
                vasistasSpo2.getStatus());
    }

    private void handleRespiratoryRate(final WithingsStructure data) {
        if (activityEntry == null) {
            logger.info("Received Withings respiratory rate vasistas without timestamp context: {}", GB.hexdump(data.getRawData()));
            return;
        }

        final VasistasRespiratoryRate vasistasRR = (VasistasRespiratoryRate) data;
        final int rate = vasistasRR.getRespiratoryRate();
        if (!vasistasRR.isValid()) {
            logger.info("Skipping Withings respiratory rate vasistas with invalid value: ts={} rate={}",
                    activityEntry.getTimestamp(), rate);
            return;
        }

        final long timestampMs = activityEntry.getTimestamp() * 1000L;
        respiratoryRateEntries.add(new long[]{timestampMs, rate});
        logger.info("Collected Withings respiratory rate vasistas sample: ts={} rate={}",
                timestampMs, rate);
    }

    private void handleMovement(WithingsStructure data) {
        if (activityEntry.getRawKind() == ActivityKind.NOT_MEASURED.getCode()) {
            activityEntry.setRawKind(ActivityKind.UNKNOWN.getCode());
        }
        activityEntry.setSteps(((ActivitySampleMovement) data).getSteps());
        activityEntry.setDistance(((ActivitySampleMovement) data).getDistance());
    }

    private void handleWalk(WithingsStructure data) {
        activityEntry.setRawKind(ActivityKind.WALKING.getCode());
    }

    private void handleRun(WithingsStructure data) {
        activityEntry.setRawKind(ActivityKind.RUNNING.getCode());
    }

    private void handleSwim(WithingsStructure data) {
        activityEntry.setRawKind(ActivityKind.SWIMMING.getCode());
    }

    private void handleSleep(WithingsStructure data) {
        ActivityKind sleepType;
        switch (((ActivitySampleSleep) data).getSleepType()) {
            case 0:
                sleepType = ActivityKind.LIGHT_SLEEP;
                activityEntry.setRawIntensity(10);
                break;
            case 2:
                sleepType = ActivityKind.DEEP_SLEEP;
                activityEntry.setRawIntensity(70);
                break;
            case 3:
                sleepType = ActivityKind.REM_SLEEP;
                activityEntry.setRawIntensity(80);
                break;
            default:
                sleepType = ActivityKind.LIGHT_SLEEP;
                activityEntry.setRawIntensity(50);
        }

        activityEntry.setRawKind(sleepType.getCode());
    }

    private void handleCalories1(WithingsStructure data) {
        activityEntry.setRawIntensity(((ActivitySampleCalories) data).getMet());
        activityEntry.setCalories(((ActivitySampleCalories) data).getCalories());
    }

    private void handleCalories2(WithingsStructure data) {
        activityEntry.setRawIntensity(((ActivitySampleCalories2) data).getMet());
        activityEntry.setCalories(((ActivitySampleCalories2) data).getCalories());

    }

    private void logUnhandledActivityData(final WithingsStructure data) {
        if (data instanceof ActivitySampleUnknown) {
            final ActivitySampleUnknown unknown = (ActivitySampleUnknown) data;
            logger.info("Unhandled Withings activity TLV type={} ts={} rawKind={} {} rawHex={}",
                    data.getType(),
                    activityEntry != null ? activityEntry.getTimestamp() : null,
                    activityEntry != null ? activityEntry.getRawKind() : null,
                    unknown.describePayload(),
                    GB.hexdump(unknown.getPayload()));
            return;
        }

        logger.info("Received yet unhandled activity data of type '{}' with data '{}'.", data.getType(), GB.hexdump(data.getRawData()));
    }

    private void addToList(ActivityEntry activityEntry) {
        if (activityEntry.isHeartrate()) {
            heartrateEntries.add(activityEntry);
        } else {
            activityEntries.add(activityEntry);
        }
    }

    private void saveData() {
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            Long userId = DBHelper.getUser(dbHandler.getDaoSession()).getId();
            Long deviceId = DBHelper.getDevice(device, dbHandler.getDaoSession()).getId();
            AbstractSampleProvider<? extends AbstractWithingsActivitySample> provider =
                    support.createSampleProvider(device, dbHandler.getDaoSession());

            List<AbstractWithingsActivitySample> activitySamples = new ArrayList<>();
            for (ActivityEntry entry : activityEntries) {
                activitySamples.add(convertToSample(provider, entry, userId, deviceId));
            }
            for (ActivityEntry entry : heartrateEntries) {
                activitySamples.add(convertToSample(provider, entry, userId, deviceId));
            }

            writeToDB(provider, activitySamples);

            if (!spo2Entries.isEmpty()) {
                final GenericSpo2SampleProvider spo2Provider = new GenericSpo2SampleProvider(device, dbHandler.getDaoSession());
                final List<GenericSpo2Sample> spo2Samples = new ArrayList<>(spo2Entries.size());
                for (final long[] entry : spo2Entries) {
                    spo2Samples.add(new GenericSpo2Sample(entry[0], deviceId, userId, (int) entry[1]));
                }
                spo2Provider.addSamples(spo2Samples);
                logger.info("Stored {} Withings SpO2 vasistas sample(s)", spo2Samples.size());
            }

            if (!respiratoryRateEntries.isEmpty()) {
                final GenericRespiratoryRateSampleProvider rrProvider = new GenericRespiratoryRateSampleProvider(device, dbHandler.getDaoSession());
                final List<GenericRespiratoryRateSample> rrSamples = new ArrayList<>(respiratoryRateEntries.size());
                for (final long[] entry : respiratoryRateEntries) {
                    final GenericRespiratoryRateSample sample = new GenericRespiratoryRateSample();
                    sample.setTimestamp(entry[0]);
                    sample.setDeviceId(deviceId);
                    sample.setUserId(userId);
                    sample.setRespiratoryRate((float) entry[1]);
                    rrSamples.add(sample);
                }
                rrProvider.addSamples(rrSamples);
                logger.info("Stored {} Withings respiratory rate sample(s)", rrSamples.size());
            }
        } catch (Exception ex) {
            logger.warn("Error saving activity data: " + ex.getLocalizedMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends AbstractWithingsActivitySample> void writeToDB(
            AbstractSampleProvider<T> provider, List<AbstractWithingsActivitySample> activitySamples) {
        provider.addGBActivitySamples((List<T>) activitySamples);
    }

    private AbstractWithingsActivitySample convertToSample(
            AbstractSampleProvider<? extends AbstractWithingsActivitySample> provider,
            ActivityEntry activityEntry, long userId, long deviceId) {
        AbstractWithingsActivitySample sample = provider.createActivitySample();
        sample.setTimestamp(activityEntry.getTimestamp());
        sample.setDuration(activityEntry.getDuration());
        sample.setHeartRate(activityEntry.getHeartrate());
        sample.setSteps(activityEntry.getSteps());
        sample.setRawKind(activityEntry.getRawKind());
        sample.setCalories(activityEntry.getCalories());
        sample.setDistance(activityEntry.getDistance());
        sample.setRawIntensity(activityEntry.getRawIntensity());
        sample.setDeviceId(deviceId);
        sample.setUserId(userId);
        return sample;
    }

    private void mergeHeartrateSamplesIntoActivitySammples() {
        for (ActivityEntry heartrateEntry : heartrateEntries) {
            for (ActivityEntry activityEntry : activityEntries) {
                if (doActivitiesOverlap(heartrateEntry, activityEntry)) {
                    updateHeartrateEntry(heartrateEntry, activityEntry);
                }
            }
        }
    }

    private boolean doActivitiesOverlap(ActivityEntry heartrateEntry, ActivityEntry activityEntry) {
        return activityEntry.getTimestamp() <= heartrateEntry.getTimestamp()
                && (activityEntry.getTimestamp() + activityEntry.getDuration()) >= heartrateEntry.getTimestamp();
    }

    private void updateHeartrateEntry(ActivityEntry heartRateEntry, ActivityEntry activityEntry) {
        heartRateEntry.setRawKind(activityEntry.getRawKind());
        heartRateEntry.setRawIntensity(activityEntry.getRawIntensity());
        heartRateEntry.setDuration(activityEntry.getDuration() - (heartRateEntry.getTimestamp() - activityEntry.getTimestamp()));
        // If timestamps are exactly the same and only then, the heartrate entry would overwrite the activity entry in the DB, so we set more values.
        // If we would do so everytime, steps and so on would be multiplicated.
        if (heartRateEntry.getTimestamp() == activityEntry.getTimestamp()) {
            heartRateEntry.setSteps(activityEntry.getSteps());
            heartRateEntry.setDistance(activityEntry.getDistance());
            heartRateEntry.setCalories(activityEntry.getCalories());
        }
    }
}
