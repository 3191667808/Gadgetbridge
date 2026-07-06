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
package nodomain.freeyourgadget.gadgetbridge.devices.moyoungring;

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

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericBloodPressureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHrvValueSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericStressSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericTemperatureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.ComputedHrvSummarySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBloodPressureSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHrvValueSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepStageSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2SampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericStressSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericTemperatureSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.MoyoungActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.BloodPressureSample;
import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.model.HrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.model.HrvSummarySample;
import nodomain.freeyourgadget.gadgetbridge.model.Spo2Sample;
import nodomain.freeyourgadget.gadgetbridge.model.StressSample;
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.moyoungring.MoyoungRingDeviceSupport;

/**
 * Coordinator for the MoYoung / CRRepa "Da Ring" optical smart ring
 * (advertised name "VRing", hardware model "R26", firmware "MOY-R263-2.2.2").
 *
 * <p>Reuses Gadgetbridge's generic sample providers so all data exports to
 * Health Connect and renders full graphs. Read-only-ring policy: the driver never deletes on-ring history
 * (see {@link MoyoungRingDeviceSupport}).
 */
public class MoyoungRingCoordinator extends AbstractBLEDeviceCoordinator {

    @Override
    protected Pattern getSupportedDeviceName() {
        // Advertised BLE name is "VRing". The R26 model id is matched via the
        // vendor service-UUID fallback in supports() to avoid name collisions.
        return Pattern.compile("^VRing([ _-].*)?$", Pattern.CASE_INSENSITIVE);
    }

    @Override
    public boolean supports(@NonNull GBDeviceCandidate candidate) {
        if (super.supports(candidate)) {
            return true;
        }
        return candidate.supportsService(MoyoungRingConstants.UUID_SERVICE);
    }

    @NonNull
    @Override
    public Collection<? extends ScanFilter> createBLEScanFilters() {
        ParcelUuid service = new ParcelUuid(MoyoungRingConstants.UUID_SERVICE);
        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(service).build();
        return Collections.singletonList(filter);
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(GBDevice device) {
        return MoyoungRingDeviceSupport.class;
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_NONE;
    }

    @Override
    public String getManufacturer() {
        return MoyoungRingConstants.MANUFACTURER;
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_moyoung_ring;
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
        Map<AbstractDao<?, ?>, Property> map = new HashMap<>(9);
        map.put(session.getBaseActivitySummaryDao(),  BaseActivitySummaryDao.Properties.DeviceId);
        map.put(session.getGenericHeartRateSampleDao(), GenericHeartRateSampleDao.Properties.DeviceId);
        map.put(session.getGenericSpo2SampleDao(),      GenericSpo2SampleDao.Properties.DeviceId);
        map.put(session.getGenericBloodPressureSampleDao(), GenericBloodPressureSampleDao.Properties.DeviceId);
        map.put(session.getGenericSleepStageSampleDao(), GenericSleepStageSampleDao.Properties.DeviceId);
        map.put(session.getGenericHrvValueSampleDao(),  GenericHrvValueSampleDao.Properties.DeviceId);
        map.put(session.getGenericStressSampleDao(),    GenericStressSampleDao.Properties.DeviceId);
        map.put(session.getGenericTemperatureSampleDao(), GenericTemperatureSampleDao.Properties.DeviceId);
        // Per-slot step samples are persisted in the reused MoyoungActivitySample table.
        map.put(session.getMoyoungActivitySampleDao(),  MoyoungActivitySampleDao.Properties.DeviceId);
        return map;
    }

    @Override
    public SampleProvider<? extends ActivitySample> getSampleProvider(GBDevice device, DaoSession session) {
        // The ring streams discrete HR records rather than per-minute ActivitySamples;
        // synthesize ActivitySample rows from the HR DAO and overlay the
        // per-slot step counts persisted into the reused MoyoungActivitySample table
        // so the dashboard renders both the HR graph and a real step chart.
        return new MoyoungRingActivitySampleProvider(device, session);
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

    @Override
    public TimeSampleProvider<? extends HrvValueSample> getHrvValueSampleProvider(GBDevice device, DaoSession session) {
        return new GenericHrvValueSampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends HrvSummarySample> getHrvSummarySampleProvider(GBDevice device, DaoSession session) {
        // Compute the HRV Status/summary (baseline + status) from the raw HRV value
        // stream, so the HRV Status tab renders instead of warning "not implemented".
        return new ComputedHrvSummarySampleProvider(getHrvValueSampleProvider(device, session), device, session);
    }

    @Override
    public TimeSampleProvider<? extends StressSample> getStressSampleProvider(GBDevice device, DaoSession session) {
        return new GenericStressSampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends TemperatureSample> getTemperatureSampleProvider(GBDevice device, DaoSession session) {
        return new GenericTemperatureSampleProvider(device, session,
                TemperatureSample.TYPE_SKIN, TemperatureSample.LOCATION_FINGER);
    }

    // -------- Capabilities --------

    // Steps render as a chart: the ring exposes a per-slot step HISTOGRAM (cmd
    // 2/18) which MoyoungRingDeviceSupport parses and persists as ActivitySample
    // rows (via the reused MoyoungActivitySample table), so charting is correct
    // per-interval rather than a mischarted cumulative daily total. The daily
    // total (2/13) is still handled for the device-card current-steps number.
    @Override public boolean supportsStepCounter(@NonNull GBDevice device)      { return true; }
    @Override public boolean supportsDataFetching(@NonNull GBDevice device)     { return true; }
    @Override public boolean supportsRealtimeData(@NonNull GBDevice device)     { return true; }
    @Override public boolean supportsActiveCalories(@NonNull GBDevice device)   { return true; }
    @Override public boolean supportsActivityDistance(@NonNull GBDevice device) { return true; }
    @Override public boolean supportsHeartRateMeasurement(@NonNull GBDevice device) { return true; }
    @Override public boolean supportsManualHeartRateMeasurement(@NonNull GBDevice device) { return true; }
    @Override public boolean supportsSpo2(@NonNull GBDevice device)             { return true; }
    /** Blood pressure is reported by the ring but is a non-medical wellness estimate. */
    @Override public boolean supportsBloodPressureMeasurement(@NonNull GBDevice device) { return true; }
    @Override public boolean supportsSleepMeasurement(@NonNull GBDevice device) { return true; }
    @Override public boolean supportsHrvMeasurement(@NonNull GBDevice device)   { return true; }
    @Override public boolean supportsStressMeasurement(@NonNull GBDevice device) { return true; }
    /** The ring has a working skin-temperature sensor (exports as SkinTemperatureRecord). */
    @Override public boolean supportsTemperatureMeasurement(@NonNull GBDevice device) { return true; }


    @Override public boolean isExperimental() { return true; }
}
