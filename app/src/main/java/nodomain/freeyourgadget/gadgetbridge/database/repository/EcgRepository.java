/*  Copyright (C) 2026 d3vv3

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
package nodomain.freeyourgadget.gadgetbridge.database.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.greenrobot.dao.query.DeleteQuery;
import de.greenrobot.dao.query.QueryBuilder;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiEcgDataSample;
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiEcgDataSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiEcgSummarySample;
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiEcgSummarySampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.EcgRecord;
import nodomain.freeyourgadget.gadgetbridge.model.EcgSample;

public class EcgRepository {
    private static final Logger LOG = LoggerFactory.getLogger(EcgRepository.class);

    @NonNull
    public static List<EcgRecord> getSessions(final DBHandler db,
                                              final GBDevice gbDevice,
                                              final long startTimestamp,
                                              final long endTimestamp) {
        final DaoSession daoSession = db.getDaoSession();
        final Device dbDevice = DBHelper.findDevice(gbDevice, daoSession);
        if (dbDevice == null) {
            return Collections.emptyList();
        }

        final List<HuaweiEcgSummarySample> summaries = daoSession.getHuaweiEcgSummarySampleDao()
                .queryBuilder()
                .where(
                        HuaweiEcgSummarySampleDao.Properties.DeviceId.eq(dbDevice.getId()),
                        HuaweiEcgSummarySampleDao.Properties.StartTimestamp.ge(startTimestamp),
                        HuaweiEcgSummarySampleDao.Properties.StartTimestamp.le(endTimestamp)
                )
                .orderAsc(HuaweiEcgSummarySampleDao.Properties.StartTimestamp)
                .build()
                .list();

        final List<EcgRecord> sessions = new ArrayList<>(summaries.size());
        for (final HuaweiEcgSummarySample summary : summaries) {
            sessions.add(fromEntity(summary));
        }
        return sessions;
    }

    @NonNull
    public static List<EcgSample> getSamples(final DBHandler db, final EcgRecord record) {
        if (record == null || record.getSessionId() == null) {
            return Collections.emptyList();
        }

        final List<HuaweiEcgDataSample> samples = db.getDaoSession().getHuaweiEcgDataSampleDao()
                .queryBuilder()
                .where(HuaweiEcgDataSampleDao.Properties.EcgId.eq(record.getSessionId()))
                .orderAsc(HuaweiEcgDataSampleDao.Properties.TimeDelta)
                .build()
                .list();

        final List<EcgSample> waveform = new ArrayList<>(samples.size());
        for (final HuaweiEcgDataSample sample : samples) {
            waveform.add(new EcgSample(sample.getTimeDelta(), sample.getValue()));
        }
        return waveform;
    }

    public static long getLatestEndTimestamp(@NonNull final GBDevice gbDevice) {
        try (DBHandler db = GBApplication.acquireDB()) {
            final Device dbDevice = DBHelper.findDevice(gbDevice, db.getDaoSession());
            if (dbDevice == null) {
                return 0;
            }

            final List<HuaweiEcgSummarySample> samples = db.getDaoSession().getHuaweiEcgSummarySampleDao()
                    .queryBuilder()
                    .where(HuaweiEcgSummarySampleDao.Properties.DeviceId.eq(dbDevice.getId()))
                    .orderDesc(HuaweiEcgSummarySampleDao.Properties.EndTimestamp)
                    .limit(1)
                    .build()
                    .list();
            if (samples.isEmpty()) {
                return 0;
            }
            return samples.get(0).getEndTimestamp();
        } catch (final Exception e) {
            LOG.error("Error getting latest ECG end timestamp", e);
            return 0;
        }
    }

    public static boolean hasSession(final DaoSession daoSession,
                                     final long userId,
                                     final long deviceId,
                                     final long startTimestamp) {
        return daoSession.getHuaweiEcgSummarySampleDao().queryBuilder()
                .where(
                        HuaweiEcgSummarySampleDao.Properties.UserId.eq(userId),
                        HuaweiEcgSummarySampleDao.Properties.DeviceId.eq(deviceId),
                        HuaweiEcgSummarySampleDao.Properties.StartTimestamp.eq(startTimestamp)
                )
                .count() > 0;
    }

    public static boolean hasCompleteSession(final DaoSession daoSession,
                                             final long userId,
                                             final long deviceId,
                                             final long startTimestamp) {
        final Long sessionId = findSessionId(daoSession, userId, deviceId, startTimestamp);
        return sessionId != null && daoSession.getHuaweiEcgDataSampleDao().queryBuilder()
                .where(HuaweiEcgDataSampleDao.Properties.EcgId.eq(sessionId))
                .count() > 0;
    }

    public static void upsertSession(final DaoSession daoSession,
                                     final EcgRecord record,
                                     final List<EcgSample> samples) {
        daoSession.runInTx(() -> {
            Long sessionId = record.getSessionId();
            if (sessionId == null) {
                sessionId = findSessionId(
                        daoSession,
                        record.getUserId(),
                        record.getDeviceId(),
                        record.getStartTimestamp()
                );
            }

            final HuaweiEcgSummarySample summary = new HuaweiEcgSummarySample(
                    sessionId,
                    record.getDeviceId(),
                    record.getUserId(),
                    record.getStartTimestamp(),
                    record.getEndTimestamp(),
                    record.getSourceApp(),
                    record.getAverageHeartRate(),
                    record.getDeviceHintCode(),
                    record.getUserSymptoms()
            );
            daoSession.getHuaweiEcgSummarySampleDao().insertOrReplace(summary);

            final DeleteQuery<HuaweiEcgDataSample> deleteQuery = daoSession.getHuaweiEcgDataSampleDao().queryBuilder()
                    .where(HuaweiEcgDataSampleDao.Properties.EcgId.eq(summary.getEcgId()))
                    .buildDelete();
            deleteQuery.executeDeleteWithoutDetachingEntities();

            final List<HuaweiEcgDataSample> waveformSamples = new ArrayList<>(samples.size());
            for (final EcgSample sample : samples) {
                waveformSamples.add(new HuaweiEcgDataSample(summary.getEcgId(), sample.getTimeDeltaMs(), sample.getValue()));
            }
            daoSession.getHuaweiEcgDataSampleDao().insertInTx(waveformSamples);
        });
    }

    public static void deleteForDevice(final DaoSession session, final long deviceId) {
        final List<HuaweiEcgSummarySample> ecgSummary = session.getHuaweiEcgSummarySampleDao().queryBuilder()
                .where(HuaweiEcgSummarySampleDao.Properties.DeviceId.eq(deviceId))
                .build()
                .list();
        for (final HuaweiEcgSummarySample sample : ecgSummary) {
            session.getHuaweiEcgDataSampleDao().queryBuilder()
                    .where(HuaweiEcgDataSampleDao.Properties.EcgId.eq(sample.getEcgId()))
                    .buildDelete()
                    .executeDeleteWithoutDetachingEntities();
        }
        session.getHuaweiEcgSummarySampleDao().queryBuilder()
                .where(HuaweiEcgSummarySampleDao.Properties.DeviceId.eq(deviceId))
                .buildDelete()
                .executeDeleteWithoutDetachingEntities();
    }

    @NonNull
    public static EcgRecord fromEntity(final HuaweiEcgSummarySample summary) {
        return new EcgRecord(
                summary.getEcgId(),
                summary.getDeviceId(),
                summary.getUserId(),
                summary.getStartTimestamp(),
                summary.getEndTimestamp(),
                summary.getAppVersion(),
                summary.getAverageHeartRate(),
                summary.getArrhythmiaType(),
                summary.getUserSymptoms()
        );
    }

    @Nullable
    public static Long findSessionId(final DaoSession daoSession,
                                     final long userId,
                                     final long deviceId,
                                     final long startTimestamp) {
        final List<HuaweiEcgSummarySample> existing = daoSession.getHuaweiEcgSummarySampleDao().queryBuilder()
                .where(
                        HuaweiEcgSummarySampleDao.Properties.UserId.eq(userId),
                        HuaweiEcgSummarySampleDao.Properties.DeviceId.eq(deviceId),
                        HuaweiEcgSummarySampleDao.Properties.StartTimestamp.eq(startTimestamp)
                )
                .build()
                .list();
        if (existing.isEmpty()) {
            return null;
        }
        return existing.get(0).getEcgId();
    }
}
