/*  Copyright (C) 2026 Freeyourgadget

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
package nodomain.freeyourgadget.gadgetbridge.model.sleep;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import de.greenrobot.dao.query.QueryBuilder;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.entities.UserSleepSession;
import nodomain.freeyourgadget.gadgetbridge.entities.UserSleepSessionDao;
import nodomain.freeyourgadget.gadgetbridge.entities.UserSleepStage;
import nodomain.freeyourgadget.gadgetbridge.entities.UserSleepStageDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;

public final class SleepSessionService {
    public static final int MIN_STAGE_SECONDS = 60;
    public static final int DEFAULT_SAMPLE_INTERVAL_SECONDS = 60;

    private SleepSessionService() {
    }

    public interface CorrectionChangeListener {
        void onCorrectionChanged(@NonNull SleepCorrectionChange change);
    }

    @NonNull
    public static SleepTimeline getTimeline(@NonNull final DaoSession daoSession,
                                            @NonNull final GBDevice gbDevice,
                                            @NonNull final List<? extends ActivitySample> rawSamples,
                                            final int rangeStartTs,
                                            final int rangeEndTs) {
        final List<CorrectedSleepSession> rawSessions = deriveRawSessions(daoSession, gbDevice, rawSamples);
        final List<CorrectedSleepSession> corrections = getCorrections(daoSession, gbDevice, rangeStartTs, rangeEndTs);
        final List<CorrectedSleepSession> sessions = selectVisibleSessions(rawSessions, corrections, rangeStartTs, rangeEndTs);
        return createTimeline(sessions, corrections, rawSamples, rangeStartTs, rangeEndTs);
    }

    @NonNull
    public static List<CorrectedSleepSession> getSessions(@NonNull final DaoSession daoSession,
                                                          @NonNull final GBDevice gbDevice,
                                                          @NonNull final List<? extends ActivitySample> rawSamples,
                                                          final int rangeStartTs,
                                                          final int rangeEndTs) {
        final List<CorrectedSleepSession> rawSessions = deriveRawSessions(daoSession, gbDevice, rawSamples);
        final List<CorrectedSleepSession> corrections = getCorrections(daoSession, gbDevice, rangeStartTs, rangeEndTs);
        return selectVisibleSessions(rawSessions, corrections, rangeStartTs, rangeEndTs);
    }

    @NonNull
    static List<ActivitySample> mergeEditedSessionsIntoSamples(@NonNull final List<? extends ActivitySample> rawSamples,
                                                               @NonNull final List<CorrectedSleepSession> sessions,
                                                               @NonNull final List<CorrectedSleepSample> correctedSleepSamples) {
        final List<ActivitySample> result = removeEditedSessionRangesFromSamples(rawSamples, sessions);

        for (CorrectedSleepSample correctedSleepSample : correctedSleepSamples) {
            if (isInEditedSessionRange(correctedSleepSample, sessions)) {
                result.add(correctedSleepSample);
            }
        }

        result.sort(Comparator.comparingInt(ActivitySample::getTimestamp));
        return result;
    }

    @NonNull
    static List<ActivitySample> removeEditedSessionRangesFromSamples(@NonNull final List<? extends ActivitySample> rawSamples,
                                                                    @NonNull final List<CorrectedSleepSession> sessions) {
        final List<ActivitySample> result = new ArrayList<>();
        for (ActivitySample rawSample : rawSamples) {
            if (!isInEditedSourceOrSessionRange(rawSample, sessions)) {
                result.add(rawSample);
            }
        }
        result.sort(Comparator.comparingInt(ActivitySample::getTimestamp));
        return result;
    }

    @NonNull
    public static SleepTotals calculateTotals(@NonNull final List<CorrectedSleepSession> sessions,
                                              final int rangeStartTs,
                                              final int rangeEndTs) {
        long lightSleepSeconds = 0;
        long deepSleepSeconds = 0;
        long remSleepSeconds = 0;
        long awakeSleepSeconds = 0;
        for (CorrectedSleepSession session : sessions) {
            for (SleepStage stage : session.getStages()) {
                final long clippedStart = Math.max(stage.getStartTs(), rangeStartTs);
                final long clippedEnd = Math.min(stage.getEndTs(), rangeEndTs);
                final long seconds = Math.max(0, clippedEnd - clippedStart);
                if (seconds == 0) {
                    continue;
                }

                final ActivityKind kind = normalizeStageKind(stage.getKind());
                if (kind == ActivityKind.LIGHT_SLEEP) {
                    lightSleepSeconds += seconds;
                } else if (kind == ActivityKind.DEEP_SLEEP) {
                    deepSleepSeconds += seconds;
                } else if (kind == ActivityKind.REM_SLEEP) {
                    remSleepSeconds += seconds;
                } else if (kind == ActivityKind.AWAKE_SLEEP) {
                    awakeSleepSeconds += seconds;
                }
            }
        }
        return new SleepTotals(lightSleepSeconds, deepSleepSeconds, remSleepSeconds, awakeSleepSeconds);
    }

    public static boolean hasCorrections(@NonNull final DaoSession daoSession,
                                         @NonNull final GBDevice gbDevice,
                                         final int rangeStartTs,
                                         final int rangeEndTs) {
        return !getCorrections(daoSession, gbDevice, rangeStartTs, rangeEndTs).isEmpty();
    }

    @NonNull
    static List<CorrectedSleepSample> toSamples(@NonNull final List<CorrectedSleepSession> sessions,
                                                @NonNull final List<? extends ActivitySample> rawSamples,
                                                final int rangeStartTs,
                                                final int rangeEndTs) {
        final List<ActivitySample> sortedRawSamples = new ArrayList<>(rawSamples);
        sortedRawSamples.sort(Comparator.comparingInt(ActivitySample::getTimestamp));

        final List<CorrectedSleepSample> samples = new ArrayList<>();
        for (CorrectedSleepSession session : sessions) {
            for (SleepStage stage : session.getStages()) {
                final long clippedStart = Math.max(stage.getStartTs(), rangeStartTs);
                final long clippedEnd = Math.min(stage.getEndTs(), rangeEndTs);
                if (clippedEnd <= clippedStart) {
                    continue;
                }

                long timestamp = floorToMinute(clippedStart);
                if (timestamp < clippedStart) {
                    timestamp += DEFAULT_SAMPLE_INTERVAL_SECONDS;
                }
                while (timestamp < clippedEnd) {
                    final int sampleTimestamp = (int) timestamp;
                    final ActivitySample overlayMetricsSource = findRawSampleForOverlayMetrics(sortedRawSamples, sampleTimestamp);
                    samples.add(new CorrectedSleepSample(
                            sampleTimestamp,
                            session.getDeviceId(),
                            session.getUserId(),
                            stage.getKind(),
                            overlayMetricsSource != null ? overlayMetricsSource.getIntensity() : ActivitySample.NOT_MEASURED,
                            overlayMetricsSource != null ? overlayMetricsSource.getHeartRate() : ActivitySample.NOT_MEASURED
                    ));
                    timestamp += DEFAULT_SAMPLE_INTERVAL_SECONDS;
                }
            }
        }
        samples.sort(Comparator.comparingInt(CorrectedSleepSample::getTimestamp));
        return samples;
    }

    @NonNull
    static SleepTimeline createTimeline(@NonNull final List<CorrectedSleepSession> sessions,
                                        @NonNull final List<CorrectedSleepSession> correctionRanges,
                                        @NonNull final List<? extends ActivitySample> rawSamples,
                                        final int rangeStartTs,
                                        final int rangeEndTs) {
        final List<CorrectedSleepSample> sleepSamples = toSamples(sessions, rawSamples, rangeStartTs, rangeEndTs);
        return new SleepTimeline(
                sessions,
                sleepSamples,
                mergeEditedSessionsIntoSamples(rawSamples, correctionRanges, sleepSamples),
                removeEditedSessionRangesFromSamples(rawSamples, correctionRanges),
                calculateTotals(sessions, rangeStartTs, rangeEndTs),
                correctionRanges,
                !correctionRanges.isEmpty()
        );
    }

    @NonNull
    private static List<CorrectedSleepSession> selectVisibleSessions(@NonNull final List<CorrectedSleepSession> rawSessions,
                                                                    @NonNull final List<CorrectedSleepSession> corrections,
                                                                    final int rangeStartTs,
                                                                    final int rangeEndTs) {
        final List<CorrectedSleepSession> result = new ArrayList<>();
        for (CorrectedSleepSession rawSession : rawSessions) {
            if (!overlaps(rawSession.getStartTs(), rawSession.getEndTs(), rangeStartTs, rangeEndTs)) {
                continue;
            }
            if (isReplacedByCorrection(rawSession, corrections)) {
                continue;
            }
            result.add(rawSession);
        }

        for (CorrectedSleepSession correction : corrections) {
            if (overlaps(correction.getStartTs(), correction.getEndTs(), rangeStartTs, rangeEndTs)) {
                result.add(correction);
            }
        }

        result.sort(Comparator.comparingLong(CorrectedSleepSession::getStartTs));
        return result;
    }

    @Nullable
    private static ActivitySample findRawSampleForOverlayMetrics(@NonNull final List<ActivitySample> sortedRawSamples,
                                                                 final int timestamp) {
        int low = 0;
        int high = sortedRawSamples.size() - 1;
        while (low <= high) {
            final int mid = (low + high) >>> 1;
            final int sampleTimestamp = sortedRawSamples.get(mid).getTimestamp();
            if (sampleTimestamp < timestamp) {
                low = mid + 1;
            } else if (sampleTimestamp > timestamp) {
                high = mid - 1;
            } else {
                return sortedRawSamples.get(mid);
            }
        }

        ActivitySample nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        if (low < sortedRawSamples.size()) {
            nearest = sortedRawSamples.get(low);
            nearestDistance = Math.abs(nearest.getTimestamp() - timestamp);
        }
        if (low > 0) {
            final ActivitySample previous = sortedRawSamples.get(low - 1);
            final int previousDistance = Math.abs(previous.getTimestamp() - timestamp);
            if (previousDistance < nearestDistance) {
                nearest = previous;
                nearestDistance = previousDistance;
            }
        }

        return nearestDistance <= DEFAULT_SAMPLE_INTERVAL_SECONDS / 2 ? nearest : null;
    }

    @NonNull
    public static SleepCorrectionChange saveCorrection(@NonNull final DaoSession daoSession,
                                                       @NonNull final GBDevice gbDevice,
                                                       @NonNull final CorrectedSleepSession requestedSession) {
        return saveCorrection(daoSession, gbDevice, SleepCorrectionRequest.fromSession(requestedSession), null);
    }

    @NonNull
    public static SleepCorrectionChange saveCorrection(@NonNull final DaoSession daoSession,
                                                       @NonNull final GBDevice gbDevice,
                                                       @NonNull final SleepCorrectionRequest requestedSession,
                                                       @Nullable final CorrectionChangeListener changeListener) {
        final Device dbDevice = DBHelper.findDevice(gbDevice, daoSession);
        if (dbDevice == null) {
            throw new IllegalStateException("Device is not in database");
        }
        final User user = DBHelper.getUser(daoSession);
        final long now = System.currentTimeMillis() / 1000L;

        final SleepCorrectionRequest normalized = normalizeForSave(requestedSession);
        final SleepCorrectionChange[] change = {SleepCorrectionChange.none()};
        daoSession.runInTx(() -> {
            validateNoOverlappingCorrection(daoSession, dbDevice.getId(), user.getId(), normalized);
            final UserSleepSession previous = normalized.getId() != null
                    ? daoSession.getUserSleepSessionDao().load(normalized.getId())
                    : null;

            final UserSleepSession entity = new UserSleepSession(
                    normalized.getId(),
                    dbDevice.getId(),
                    user.getId(),
                    normalized.getSourceStartTs(),
                    normalized.getSourceEndTs(),
                    normalized.getStartTs(),
                    normalized.getEndTs(),
                    normalized.getCreatedAt() > 0 ? normalized.getCreatedAt() : now,
                    now
            );

            daoSession.getUserSleepSessionDao().insertOrReplace(entity);
            final Long sessionId = entity.getId();
            if (sessionId == null) {
                throw new IllegalStateException("Saved sleep session has no id");
            }

            final QueryBuilder<UserSleepStage> stageQuery = daoSession.getUserSleepStageDao().queryBuilder();
            stageQuery.where(UserSleepStageDao.Properties.SessionId.eq(sessionId));
            daoSession.getUserSleepStageDao().deleteInTx(stageQuery.build().list());

            final List<UserSleepStage> stageEntities = new ArrayList<>();
            for (SleepStage stage : normalized.getStages()) {
                stageEntities.add(new UserSleepStage(
                        null,
                        sessionId,
                        stage.getStartTs(),
                        stage.getEndTs(),
                        stage.getKind().getCode()
                ));
            }
            daoSession.getUserSleepStageDao().insertInTx(stageEntities);

            final CorrectedSleepSession savedSession = new CorrectedSleepSession(
                    sessionId,
                    dbDevice.getId(),
                    user.getId(),
                    normalized.getSourceStartTs(),
                    normalized.getSourceEndTs(),
                    normalized.getStartTs(),
                    normalized.getEndTs(),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt(),
                    true,
                    normalized.getStages()
            );
            change[0] = SleepCorrectionChange.saved(savedSession, getRewindStart(savedSession, previous));
            if (changeListener != null) {
                changeListener.onCorrectionChanged(change[0]);
            }
        });
        return change[0];
    }

    @NonNull
    public static SleepCorrectionChange deleteCorrection(@NonNull final DaoSession daoSession, @Nullable final Long sessionId) {
        return deleteCorrection(daoSession, sessionId, null);
    }

    @NonNull
    public static SleepCorrectionChange deleteCorrection(@NonNull final DaoSession daoSession,
                                                        @Nullable final Long sessionId,
                                                        @Nullable final CorrectionChangeListener changeListener) {
        if (sessionId == null) {
            return SleepCorrectionChange.none();
        }

        final SleepCorrectionChange[] change = {SleepCorrectionChange.none()};
        daoSession.runInTx(() -> {
            final UserSleepSession entity = daoSession.getUserSleepSessionDao().load(sessionId);
            final long rewindStartTs = entity != null ? getRewindStart(entity) : 0;

            final QueryBuilder<UserSleepStage> stageQuery = daoSession.getUserSleepStageDao().queryBuilder();
            stageQuery.where(UserSleepStageDao.Properties.SessionId.eq(sessionId));
            daoSession.getUserSleepStageDao().deleteInTx(stageQuery.build().list());

            if (entity != null) {
                daoSession.getUserSleepSessionDao().delete(entity);
                change[0] = SleepCorrectionChange.deleted(entity.getDeviceId(), rewindStartTs);
                if (changeListener != null) {
                    changeListener.onCorrectionChanged(change[0]);
                }
            }
        });
        return change[0];
    }

    @NonNull
    public static SleepCorrectionRequest normalizeForSave(@NonNull final CorrectedSleepSession session) {
        return normalizeForSave(SleepCorrectionRequest.fromSession(session));
    }

    @NonNull
    public static SleepCorrectionRequest normalizeForSave(@NonNull final SleepCorrectionRequest session) {
        if (session.getEndTs() <= session.getStartTs()) {
            throw new IllegalArgumentException("Sleep end must be after sleep start");
        }
        final List<SleepStage> stages = new ArrayList<>();
        for (SleepStage stage : session.getStages()) {
            final long start = Math.max(stage.getStartTs(), session.getStartTs());
            final long end = Math.min(stage.getEndTs(), session.getEndTs());
            final ActivityKind kind = normalizeStageKind(stage.getKind());
            if (end - start >= MIN_STAGE_SECONDS) {
                stages.add(new SleepStage(start, end, kind));
            }
        }
        stages.sort(Comparator.comparingLong(SleepStage::getStartTs));

        final List<SleepStage> tiledStages = new ArrayList<>();
        long cursor = session.getStartTs();
        for (SleepStage stage : stages) {
            if (stage.getStartTs() > cursor) {
                tiledStages.add(new SleepStage(cursor, stage.getStartTs(), ActivityKind.LIGHT_SLEEP));
            } else if (stage.getStartTs() < cursor) {
                stage = new SleepStage(cursor, stage.getEndTs(), stage.getKind());
            }
            if (stage.getEndTs() > cursor) {
                tiledStages.add(stage);
                cursor = stage.getEndTs();
            }
        }
        if (cursor < session.getEndTs()) {
            tiledStages.add(new SleepStage(cursor, session.getEndTs(), ActivityKind.LIGHT_SLEEP));
        }

        final List<SleepStage> mergedStages = mergeAdjacent(tiledStages);
        for (SleepStage stage : mergedStages) {
            if (stage.getDurationSeconds() < MIN_STAGE_SECONDS) {
                throw new IllegalArgumentException("Sleep stages must be at least one minute");
            }
        }

        return new SleepCorrectionRequest(
                session.getId(),
                session.getSourceStartTs() > 0 ? session.getSourceStartTs() : session.getStartTs(),
                session.getSourceEndTs() > 0 ? session.getSourceEndTs() : session.getEndTs(),
                session.getStartTs(),
                session.getEndTs(),
                session.getCreatedAt(),
                mergedStages
        );
    }

    @NonNull
    public static List<CorrectedSleepSession> deriveRawSessions(@NonNull final DaoSession daoSession,
                                                                @NonNull final GBDevice gbDevice,
                                                                @NonNull final List<? extends ActivitySample> rawSamples) {
        final Device dbDevice = DBHelper.findDevice(gbDevice, daoSession);
        final User user = DBHelper.getUser(daoSession);
        final long deviceId = dbDevice != null ? dbDevice.getId() : 0;
        final long userId = user.getId() != null ? user.getId() : 0;

        final List<ActivitySample> sortedSamples = new ArrayList<>(rawSamples);
        sortedSamples.sort(Comparator.comparingInt(ActivitySample::getTimestamp));

        final SleepAnalysis sleepAnalysis = new SleepAnalysis();
        final List<SleepAnalysis.SleepSession> detectedSessions = sleepAnalysis.calculateSleepSessions(sortedSamples);
        final List<CorrectedSleepSession> result = new ArrayList<>();
        for (SleepAnalysis.SleepSession detectedSession : detectedSessions) {
            final long startTs = detectedSession.getSleepStart().getTime() / 1000L;
            long endTs = detectedSession.getSleepEnd().getTime() / 1000L;
            if (endTs <= startTs) {
                endTs = startTs + DEFAULT_SAMPLE_INTERVAL_SECONDS;
            } else {
                endTs += DEFAULT_SAMPLE_INTERVAL_SECONDS;
            }

            final List<SleepStage> stages = buildStagesFromSamples(sortedSamples, startTs, endTs);
            if (!stages.isEmpty()) {
                result.add(new CorrectedSleepSession(
                        null,
                        deviceId,
                        userId,
                        startTs,
                        endTs,
                        startTs,
                        endTs,
                        0,
                        0,
                        false,
                        stages
                ));
            }
        }
        return result;
    }

    @NonNull
    private static List<CorrectedSleepSession> getCorrections(@NonNull final DaoSession daoSession,
                                                              @NonNull final GBDevice gbDevice,
                                                              final int rangeStartTs,
                                                              final int rangeEndTs) {
        final Device dbDevice = DBHelper.findDevice(gbDevice, daoSession);
        if (dbDevice == null) {
            return Collections.emptyList();
        }
        final User user = DBHelper.getUser(daoSession);
        final QueryBuilder<UserSleepSession> query = daoSession.getUserSleepSessionDao().queryBuilder();
        query.where(
                UserSleepSessionDao.Properties.DeviceId.eq(dbDevice.getId()),
                UserSleepSessionDao.Properties.UserId.eq(user.getId())
        );
        query.orderAsc(UserSleepSessionDao.Properties.StartTs);

        final List<CorrectedSleepSession> corrections = new ArrayList<>();
        for (UserSleepSession entity : query.build().list()) {
            if (!overlaps(entity.getStartTs(), entity.getEndTs(), rangeStartTs, rangeEndTs)
                    && !overlaps(entity.getSourceStartTs(), entity.getSourceEndTs(), rangeStartTs, rangeEndTs)) {
                continue;
            }
            final List<SleepStage> stages = getStages(daoSession, entity.getId());
            corrections.add(new CorrectedSleepSession(
                    entity.getId(),
                    entity.getDeviceId(),
                    entity.getUserId(),
                    entity.getSourceStartTs(),
                    entity.getSourceEndTs(),
                    entity.getStartTs(),
                    entity.getEndTs(),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt(),
                    true,
                    stages
            ));
        }
        return corrections;
    }

    @NonNull
    private static List<SleepStage> getStages(@NonNull final DaoSession daoSession, @NonNull final Long sessionId) {
        final QueryBuilder<UserSleepStage> query = daoSession.getUserSleepStageDao().queryBuilder();
        query.where(UserSleepStageDao.Properties.SessionId.eq(sessionId));
        query.orderAsc(UserSleepStageDao.Properties.StartTs);

        final List<SleepStage> stages = new ArrayList<>();
        for (UserSleepStage stage : query.build().list()) {
            stages.add(new SleepStage(
                    stage.getStartTs(),
                    stage.getEndTs(),
                    normalizeStageKind(ActivityKind.fromCode(stage.getActivityKindCode()))
            ));
        }
        return stages;
    }

    @NonNull
    private static List<SleepStage> buildStagesFromSamples(@NonNull final List<? extends ActivitySample> sortedSamples,
                                                           final long startTs,
                                                           final long endTs) {
        final List<ActivitySample> sessionSamples = new ArrayList<>();
        for (ActivitySample sample : sortedSamples) {
            if (sample.getTimestamp() >= startTs && sample.getTimestamp() < endTs) {
                sessionSamples.add(sample);
            }
        }
        if (sessionSamples.isEmpty()) {
            return Collections.singletonList(new SleepStage(startTs, endTs, ActivityKind.LIGHT_SLEEP));
        }

        final List<SleepStage> stages = new ArrayList<>();
        ActivityKind currentKind = rawSampleToStageKind(sessionSamples.get(0));
        long currentStart = Math.max(startTs, sessionSamples.get(0).getTimestamp());
        for (int i = 1; i < sessionSamples.size(); i++) {
            final ActivitySample sample = sessionSamples.get(i);
            final ActivityKind kind = rawSampleToStageKind(sample);
            if (kind != currentKind) {
                final long end = Math.max(currentStart + MIN_STAGE_SECONDS, sample.getTimestamp());
                stages.add(new SleepStage(currentStart, Math.min(end, endTs), currentKind));
                currentStart = sample.getTimestamp();
                currentKind = kind;
            }
        }
        stages.add(new SleepStage(currentStart, endTs, currentKind));
        return mergeAdjacent(stages);
    }

    @NonNull
    private static ActivityKind rawSampleToStageKind(@NonNull final ActivitySample sample) {
        final ActivityKind kind = sample.getKind();
        if (kind == ActivityKind.LIGHT_SLEEP || kind == ActivityKind.DEEP_SLEEP
                || kind == ActivityKind.REM_SLEEP || kind == ActivityKind.AWAKE_SLEEP) {
            return kind;
        }
        return ActivityKind.AWAKE_SLEEP;
    }

    @NonNull
    private static ActivityKind normalizeStageKind(@Nullable final ActivityKind kind) {
        if (kind == ActivityKind.DEEP_SLEEP || kind == ActivityKind.REM_SLEEP || kind == ActivityKind.AWAKE_SLEEP) {
            return kind;
        }
        return ActivityKind.LIGHT_SLEEP;
    }

    @NonNull
    private static List<SleepStage> mergeAdjacent(@NonNull final List<SleepStage> stages) {
        final List<SleepStage> result = new ArrayList<>();
        for (SleepStage stage : stages) {
            if (stage.getEndTs() <= stage.getStartTs()) {
                continue;
            }
            if (!result.isEmpty()) {
                final SleepStage previous = result.get(result.size() - 1);
                if (previous.getKind() == stage.getKind() && previous.getEndTs() == stage.getStartTs()) {
                    previous.setEndTs(stage.getEndTs());
                    continue;
                }
            }
            result.add(new SleepStage(stage));
        }
        return result;
    }

    private static void validateNoOverlappingCorrection(@NonNull final DaoSession daoSession,
                                                        final long deviceId,
                                                        final long userId,
                                                        @NonNull final SleepCorrectionRequest session) {
        final QueryBuilder<UserSleepSession> query = daoSession.getUserSleepSessionDao().queryBuilder();
        query.where(
                UserSleepSessionDao.Properties.DeviceId.eq(deviceId),
                UserSleepSessionDao.Properties.UserId.eq(userId)
        );
        for (UserSleepSession other : query.build().list()) {
            if (session.getId() != null && session.getId().equals(other.getId())) {
                continue;
            }
            if (overlaps(session.getStartTs(), session.getEndTs(), other.getStartTs(), other.getEndTs())) {
                throw new IllegalArgumentException("Corrected sleep sessions cannot overlap");
            }
        }
    }

    static boolean isReplacedByCorrection(@NonNull final CorrectedSleepSession rawSession,
                                          @NonNull final List<CorrectedSleepSession> corrections) {
        for (CorrectedSleepSession correction : corrections) {
            if (overlapsEditedSourceOrSession(rawSession.getStartTs(), rawSession.getEndTs(), correction)) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlapsEditedSourceOrSession(final long startTs,
                                                         final long endTs,
                                                         @NonNull final CorrectedSleepSession session) {
        return overlaps(startTs, endTs, session.getSourceStartTs(), session.getSourceEndTs())
                || overlaps(startTs, endTs, session.getStartTs(), session.getEndTs());
    }

    private static boolean isInEditedSourceOrSessionRange(@NonNull final ActivitySample sample,
                                                          @NonNull final List<CorrectedSleepSession> sessions) {
        for (CorrectedSleepSession session : sessions) {
            if (!session.isEdited()) {
                continue;
            }
            if (overlapsEditedSourceOrSession(sample.getTimestamp(), sample.getTimestamp() + DEFAULT_SAMPLE_INTERVAL_SECONDS, session)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInEditedSessionRange(@NonNull final ActivitySample sample,
                                                  @NonNull final List<CorrectedSleepSession> sessions) {
        for (CorrectedSleepSession session : sessions) {
            if (session.isEdited() && overlaps(sample.getTimestamp(), sample.getTimestamp() + DEFAULT_SAMPLE_INTERVAL_SECONDS, session.getStartTs(), session.getEndTs())) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlaps(final long startA, final long endA, final long startB, final long endB) {
        return startA < endB && endA > startB;
    }

    private static long getRewindStart(@NonNull final CorrectedSleepSession session,
                                       @Nullable final UserSleepSession previous) {
        long rewindStart = getCorrectionStart(session);
        if (previous != null) {
            rewindStart = Math.min(rewindStart, getCorrectionStart(previous));
        }
        return Math.max(0, rewindStart - DEFAULT_SAMPLE_INTERVAL_SECONDS);
    }

    private static long getRewindStart(@NonNull final UserSleepSession session) {
        return Math.max(0, getCorrectionStart(session) - DEFAULT_SAMPLE_INTERVAL_SECONDS);
    }

    private static long getCorrectionStart(@NonNull final CorrectedSleepSession session) {
        return Math.min(session.getStartTs(), session.getSourceStartTs());
    }

    private static long getCorrectionStart(@NonNull final UserSleepSession session) {
        return Math.min(session.getStartTs(), session.getSourceStartTs());
    }

    private static long floorToMinute(final long timestamp) {
        return timestamp - (timestamp % DEFAULT_SAMPLE_INTERVAL_SECONDS);
    }

    public static final class SleepTotals {
        private final long lightSleepSeconds;
        private final long deepSleepSeconds;
        private final long remSleepSeconds;
        private final long awakeSleepSeconds;

        public SleepTotals(final long lightSleepSeconds,
                           final long deepSleepSeconds,
                           final long remSleepSeconds,
                           final long awakeSleepSeconds) {
            this.lightSleepSeconds = lightSleepSeconds;
            this.deepSleepSeconds = deepSleepSeconds;
            this.remSleepSeconds = remSleepSeconds;
            this.awakeSleepSeconds = awakeSleepSeconds;
        }

        public long getLightSleepSeconds() {
            return lightSleepSeconds;
        }

        public long getDeepSleepSeconds() {
            return deepSleepSeconds;
        }

        public long getRemSleepSeconds() {
            return remSleepSeconds;
        }

        public long getAwakeSleepSeconds() {
            return awakeSleepSeconds;
        }

        public long getTotalSleepSeconds() {
            return lightSleepSeconds + deepSleepSeconds + remSleepSeconds;
        }

        public long getTotalSeconds() {
            return getTotalSleepSeconds() + awakeSleepSeconds;
        }
    }
}
