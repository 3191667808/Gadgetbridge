package nodomain.freeyourgadget.gadgetbridge.util.healthconnect;

import android.annotation.SuppressLint;
import android.content.Context;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.NonNull;
import androidx.health.connect.client.HealthConnectClient;
import androidx.health.connect.client.PermissionController;
import androidx.health.connect.client.permission.HealthPermission;
import androidx.health.connect.client.records.HeartRateRecord;
import androidx.health.connect.client.records.StepsRecord;
import androidx.health.connect.client.records.metadata.DataOrigin;
import androidx.health.connect.client.records.metadata.Device;
import androidx.health.connect.client.records.metadata.Metadata;
import androidx.health.connect.client.response.InsertRecordsResponse;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.JvmClassMappingKt;
import kotlin.reflect.KClass;
import kotlinx.coroutines.Dispatchers;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.AbstractActivitySample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class HealthConnectUtils {
    private static final Logger LOG = LoggerFactory.getLogger(HealthConnectUtils.class);
    private final PreferenceFragmentCompat preferenceFragmentCompat;
    public final ActivityResultLauncher<Set<String>> activityResultLauncher;
    private final Class<StepsRecord> stepsRecordJavaClass = StepsRecord.class;
    private final Class<HeartRateRecord> heartrateRecordJavaClass = HeartRateRecord.class;
    private final KClass<StepsRecord> stepsRecordKClass = JvmClassMappingKt.getKotlinClass(stepsRecordJavaClass);
    private final KClass<HeartRateRecord> heartrateRecordKClass = JvmClassMappingKt.getKotlinClass(heartrateRecordJavaClass);
    public final Set<String> requiredHealthConnectPermissions = Set.of(
            HealthPermission.getReadPermission(stepsRecordKClass),
            HealthPermission.getWritePermission(stepsRecordKClass),
            HealthPermission.getReadPermission(heartrateRecordKClass),
            HealthPermission.getWritePermission(heartrateRecordKClass)
    );

    public ActivityResultContract<Set<String>, Set<String>> requestPermissionResultContract = PermissionController.createRequestPermissionResultContract();

    public HealthConnectUtils(PreferenceFragmentCompat fragmentCompat) {
        preferenceFragmentCompat = fragmentCompat;
        activityResultLauncher = preferenceFragmentCompat.registerForActivityResult(
                requestPermissionResultContract,
                this::permissionCallback
        );
    }

    @SuppressLint("RestrictedApi")
    public void permissionCallback(Set<String> granted) {
        Context context = preferenceFragmentCompat.getContext();
        Preference pref = preferenceFragmentCompat.findPreference(GBPrefs.HEALTH_CONNECT_ENABLED);
        assert pref != null;
        if(granted.isEmpty()) {
            // All permissions denied
            // At the point when the callback function runs, the PreferenceChangeListener has already returned
            pref.performClick();
            GB.toast(context, "All Health Connect Permissions denied", Toast.LENGTH_LONG, GB.ERROR);
        } else {
            pref.setEnabled(false);
            HealthConnectClient healthConnectClient = healthConnectInit(context);
            healthConnectDataSync(context, healthConnectClient);
            preferenceFragmentCompat.findPreference(GBPrefs.HEALTH_CONNECT_MANUAL_SYNC).setVisible(true);
            preferenceFragmentCompat.findPreference(GBPrefs.HEALTH_CONNECT_DISABLE_NOTICE).setVisible(true);
        }
    }

    public HealthConnectClient healthConnectInit(Context context) {
        // First check if we can even use Health Connect
        int availabilityStatus = HealthConnectClient.getSdkStatus(context);
        if (availabilityStatus == HealthConnectClient.SDK_UNAVAILABLE) {
            GB.toast(context, "Health Connect not supported on this Android Version", Toast.LENGTH_LONG, GB.ERROR);
            return null;
        }
        // Initialize Health Connect Client
        return HealthConnectClient.getOrCreate(context);
    }

    @SuppressLint("NewApi")
    public void healthConnectDataSync(Context context, HealthConnectClient healthConnectClient) {
        // Data insertion
        Calendar day = Calendar.getInstance();
        int endTs = (int) (day.getTimeInMillis() / 1000) + 24 * 60 * 60 - 1;
        List<StepsRecord> stepsRecordList = new ArrayList<>();
        List<HeartRateRecord> heartRateRecordList = new ArrayList<>();
        List<? extends ActivitySample> deviceSamples = Collections.emptyList();
        ZoneOffset offset = ZonedDateTime.now(TimeZone.getDefault().toZoneId()).getOffset();
        Prefs prefs = GBApplication.getPrefs();
        Set<String> selectedDevices = prefs.getStringSet("health_connect_devices_multiselect", new HashSet<>());
        if(selectedDevices == null || selectedDevices.isEmpty()) {
            GB.toast(context, "No devices selected", Toast.LENGTH_LONG, GB.ERROR);
            return;
        }
        List<GBDevice> devices = GBApplication.app().getDeviceManager().getDevices();
        if(devices.isEmpty()) {
            GB.toast(context, "No devices connected", Toast.LENGTH_LONG, GB.ERROR);
            return;
        }
        for(GBDevice device: devices) {
            DeviceCoordinator deviceCoordinator = device.getDeviceCoordinator();
            // If device is not selected or does not support Activity Tracking, skip
            if(!selectedDevices.contains(device.getAddress()) || !deviceCoordinator.supportsActivityTracking()) {
                continue;
            }
            try (DBHandler db = GBApplication.acquireDB()) {
                // Get first entries to check for timestamp (helps with the DB Query performance)
                SampleProvider<? extends ActivitySample> provider = deviceCoordinator.getSampleProvider(device, db.getDaoSession());
                ActivitySample firstSample = provider.getFirstActivitySample();
                if (firstSample == null) {
                    GB.toast(context, "No Health Connect Data found for Device " + device.getName(), Toast.LENGTH_LONG, GB.INFO);
                    continue;
                }
                Instant firstSampleTimestamp = Instant.ofEpochSecond(firstSample.getTimestamp());
                Instant oneYearAgo = LocalDateTime.now().minusYears(1).toInstant(offset);
                Instant startTs;
                if (firstSampleTimestamp.isBefore(oneYearAgo)) {
                    startTs = oneYearAgo;
                } else {
                    startTs = firstSampleTimestamp;
                }
                // Get all entries since first entry (but max 1 year, longer causes App crashes)
                deviceSamples = getActivitySamples(db, device, (int) startTs.getEpochSecond(), endTs);
            } catch (Exception e) {
                LOG.error("Error during DBAccess for Health Connect", e);
            }
            // Device Metadata
            Metadata metadata = new Metadata(
                    "",
                    new DataOrigin(context.getPackageName()),
                    Instant.now(),
                    "",
                    0,
                    new Device(device.getType().name(), device.getModel(), Device.TYPE_UNKNOWN),
                    Metadata.RECORDING_METHOD_UNKNOWN
            );

            // Clean entries to have at least either 1 Step or HR over 0
            List<ActivitySample> cleanedStepSamples = new ArrayList<>();
            List<ActivitySample> cleanedHeartrateSamples = new ArrayList<>();
            for (ActivitySample sample : deviceSamples) {
                if (sample.getSteps() > 0) {
                    cleanedStepSamples.add(sample);
                }
                if(sample.getHeartRate() > 0) {
                    cleanedHeartrateSamples.add(sample);
                }
            }

            for (ActivitySample sample : cleanedStepSamples) {
                StepsRecord stepsRecord = new StepsRecord(
                        Instant.ofEpochSecond(sample.getTimestamp()),
                        offset,
                        // Add 59 min cause we measure in 60min intervals
                        Instant.ofEpochSecond(sample.getTimestamp() + 60 * 59),
                        offset,
                        sample.getSteps(),
                        metadata
                );
                stepsRecordList.add(stepsRecord);
            }

            for (ActivitySample sample : cleanedHeartrateSamples) {
                HeartRateRecord.Sample heartRateRecordSample = new HeartRateRecord.Sample(Instant.ofEpochSecond(sample.getTimestamp()),sample.getHeartRate());
                HeartRateRecord heartRateRecord = new HeartRateRecord(
                        Instant.ofEpochSecond(sample.getTimestamp()),
                        offset,
                        Instant.ofEpochSecond(sample.getTimestamp()),
                        offset,
                        List.of(heartRateRecordSample),
                        metadata
                );
                heartRateRecordList.add(heartRateRecord);
            }
        }

        Continuation<InsertRecordsResponse> continuationRecord =  new Continuation<InsertRecordsResponse>() {
            @NotNull
            @Override
            public CoroutineContext getContext() {
                return (CoroutineContext) Dispatchers.getDefault();
            }

            public void resumeWith(@NonNull Object e) {

            }
        };
        healthConnectClient.insertRecords(stepsRecordList, continuationRecord);
        healthConnectClient.insertRecords(heartRateRecordList, continuationRecord);
        GB.toast(context, "Health Connect Data Synced", Toast.LENGTH_LONG, GB.INFO);
    }

    protected List<? extends AbstractActivitySample> getActivitySamples(DBHandler db, GBDevice device, int tsFrom, int tsTo) {
        SampleProvider<? extends ActivitySample> provider = device.getDeviceCoordinator().getSampleProvider(device, db.getDaoSession());
        return provider.getAllActivitySamples(tsFrom, tsTo);
    }
}
