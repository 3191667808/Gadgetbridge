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

import android.bluetooth.le.ScanFilter;
import android.os.ParcelUuid;

import androidx.annotation.NonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;

import nodomain.freeyourgadget.gadgetbridge.GBException;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHrvValueSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericStressSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericBloodPressureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBloodPressureSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHrvValueSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepStageSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2SampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericStressSampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.BloodPressureSample;
import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.model.HrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.model.Spo2Sample;
import nodomain.freeyourgadget.gadgetbridge.model.StressSample;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.r20.R20DeviceSupport;

/**
 * Coordinator for the R20 smart ring (Yucheng YCBT family).
 *
 * <p>Status (R20 fw 2.32):
 * <ul>
 *   <li>Device info, name, step count, battery, SpO2 query — working via magic-prefix queries.</li>
 *   <li>Standard 0x180D HR Measurement notify — works.</li>
 *   <li>History sync (Health_HistoryAll group 5) — protocol decoded, parser TBD.</li>
 *   <li>Live HR/SpO2 push (Real_Upload* group 6) — vendor accepts AppControlReal start
 *       but R20 firmware doesn't push frames yet; needs further investigation.</li>
 * </ul>
 */
public class R20Coordinator extends AbstractBLEDeviceCoordinator {

    @Override
    protected Pattern getSupportedDeviceName() {
        // R20 advertises as "R20 <hex>"; firmware internal name is "R11M".
        return Pattern.compile("^(R20([ _-].*)?|R11M)$", Pattern.CASE_INSENSITIVE);
    }

    @Override
    public boolean supports(@NonNull GBDeviceCandidate candidate) {
        if (super.supports(candidate)) {
            return true;
        }
        return candidate.supportsService(R20Constants.UUID_SERVICE);
    }

    @NonNull
    @Override
    public Collection<? extends ScanFilter> createBLEScanFilters() {
        ParcelUuid service = new ParcelUuid(R20Constants.UUID_SERVICE);
        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(service).build();
        return Collections.singletonList(filter);
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(GBDevice device) {
        return R20DeviceSupport.class;
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_NONE;
    }

    @Override
    public String getManufacturer() {
        return R20Constants.MANUFACTURER;
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_r20_ycbt;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_smartring;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return DeviceKind.RING;
    }

    // -------- Sample providers --------

    @NonNull
    @Override
    public Map<AbstractDao<?, ?>, Property> getAllDeviceDao(@NonNull final DaoSession session) {
        Map<AbstractDao<?, ?>, Property> map = new HashMap<>(6);
        map.put(session.getGenericHeartRateSampleDao(),      GenericHeartRateSampleDao.Properties.DeviceId);
        map.put(session.getGenericSpo2SampleDao(),           GenericSpo2SampleDao.Properties.DeviceId);
        map.put(session.getGenericBloodPressureSampleDao(),  GenericBloodPressureSampleDao.Properties.DeviceId);
        map.put(session.getGenericSleepStageSampleDao(),     GenericSleepStageSampleDao.Properties.DeviceId);
        map.put(session.getGenericHrvValueSampleDao(),       GenericHrvValueSampleDao.Properties.DeviceId);
        map.put(session.getGenericStressSampleDao(),         GenericStressSampleDao.Properties.DeviceId);
        return map;
    }

    @Override
    public SampleProvider<? extends ActivitySample> getSampleProvider(GBDevice device, DaoSession session) {
        return null; // R20 firmware streams discrete metrics, not aggregate ActivitySamples
    }

    @Override
    public TimeSampleProvider<? extends HeartRateSample> getHeartRateMaxSampleProvider(GBDevice device, DaoSession session) {
        return new GenericHeartRateSampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends HeartRateSample> getHeartRateManualSampleProvider(GBDevice device, DaoSession session) {
        return new GenericHeartRateSampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends Spo2Sample> getSpo2SampleProvider(GBDevice device, DaoSession session) {
        return new GenericSpo2SampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends BloodPressureSample> getBloodPressureSampleProvider(GBDevice device, DaoSession session) {
        return new GenericBloodPressureSampleProvider(device, session);
    }

    /** HRV is not measured directly by the R20 — it is computed by
     *  {@link nodomain.freeyourgadget.gadgetbridge.service.devices.r20.R20DerivedMetrics#hrvProxyRmssd}
     *  from successive HR samples and persisted to the generic HRV DAO. */
    @Override
    public TimeSampleProvider<? extends HrvValueSample> getHrvValueSampleProvider(GBDevice device, DaoSession session) {
        return new GenericHrvValueSampleProvider(device, session);
    }

    /** Daytime stress is derived from HR vs the 7-day baseline (z-score). */
    @Override
    public TimeSampleProvider<? extends StressSample> getStressSampleProvider(GBDevice device, DaoSession session) {
        return new GenericStressSampleProvider(device, session);
    }

    // -------- Capabilities --------

    @Override public boolean supportsStepCounter(@NonNull GBDevice device)      { return true; }
    @Override public boolean supportsDataFetching(@NonNull GBDevice device)     { return true; }
    @Override public boolean supportsHeartRateMeasurement(@NonNull GBDevice device) { return true; }
    @Override public boolean supportsManualHeartRateMeasurement(@NonNull GBDevice device) { return true; }
    @Override public boolean supportsSpo2(@NonNull GBDevice device)             { return true; }
    @Override public boolean supportsBloodPressureMeasurement(@NonNull GBDevice device) { return true; }
    @Override public boolean supportsSleepMeasurement(@NonNull GBDevice device) { return true; }
    /** R20 doesn't measure HRV in hardware — supplied as a software-derived proxy. */
    @Override public boolean supportsHrvMeasurement(@NonNull GBDevice device) { return true; }
    @Override public boolean supportsStressMeasurement(@NonNull GBDevice device) { return true; }

    @Override public boolean isExperimental() { return true; }
}
