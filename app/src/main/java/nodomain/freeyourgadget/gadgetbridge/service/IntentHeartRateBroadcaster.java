package nodomain.freeyourgadget.gadgetbridge.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;

import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;

public class IntentHeartRateBroadcaster implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final Logger LOG = LoggerFactory.getLogger(IntentHeartRateBroadcaster.class);

    private final Context context;
    private final GBDevice gbDevice;

    private boolean isRunning = false;
    private boolean isHrSubscribed = false;
    private long lastLocusPingTime = 0;

    public static final String ACTION_LOCUS_EVENT = "com.asamm.locus.map.EVENT_RECEIVER";
    public static final String ACTION_LOCUS_UPDATE = "locus.api.android.ACTION_PERIODIC_UPDATE";
    
    public static final String PREF_LOCUS_HR_ENABLED = "locus_map_hr_enabled";
    public static final String PREF_LOCUS_IS_RECORDING = "locus_map_is_recording";
    public static final String PREF_WATCHDOG_TIMEOUT = "locus_map_watchdog_timeout";

    public IntentHeartRateBroadcaster(Context context, GBDevice gbDevice) {
        this.context = context;
        this.gbDevice = gbDevice;
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        
        SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress());
        prefs.registerOnSharedPreferenceChangeListener(this);
        
        applySettings(prefs);
    }

    public synchronized void stop() {
        if (!isRunning) return;

        SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress());
        prefs.unregisterOnSharedPreferenceChangeListener(this);

        disableLocusIntegration();
        isRunning = false;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (PREF_LOCUS_HR_ENABLED.equals(key)) {
            LOG.info("Locus Map HR enabled setting changed, applying...");
            applySettings(sharedPreferences);
        }
    }

    private void applySettings(SharedPreferences prefs) {
        boolean hrEnabled = prefs.getBoolean(PREF_LOCUS_HR_ENABLED, false);
        if (hrEnabled) {
            enableLocusIntegration(prefs);
        } else {
            disableLocusIntegration();
        }
    }

    private void enableLocusIntegration(SharedPreferences prefs) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_LOCUS_EVENT);
        filter.addAction(ACTION_LOCUS_UPDATE);
        ContextCompat.registerReceiver(context, locusInternalEventReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
        
        IntentFilter internalFilter = new IntentFilter(nodomain.freeyourgadget.gadgetbridge.externalevents.LocusPeriodicUpdateReceiver.ACTION_LOCUS_STATE_UPDATE);
        LocalBroadcastManager.getInstance(context).registerReceiver(locusInternalEventReceiver, internalFilter);

        LOG.info("Intent Heart Rate Broadcaster connected. Tracking Locus Map events.");

        boolean wasRecording = prefs.getBoolean(PREF_LOCUS_IS_RECORDING, false);
        if (wasRecording) {
            LOG.info("Recovered active Locus Map state from preferences, restoring HR stream...");
            lastLocusPingTime = System.currentTimeMillis();
            setLiveHeartRate(true);
        }
    }

    private void disableLocusIntegration() {
        try {
            context.unregisterReceiver(locusInternalEventReceiver);
            LocalBroadcastManager.getInstance(context).unregisterReceiver(locusInternalEventReceiver);
        } catch (IllegalArgumentException e) {}
        setLiveHeartRate(false);
        LOG.info("Intent Heart Rate Broadcaster disconnected.");
    }

    private void setLiveHeartRate(boolean enable) {
        if (isHrSubscribed == enable) return;
        isHrSubscribed = enable;

        if (enable) {
            LocalBroadcastManager.getInstance(context).registerReceiver(hrReceiver, new IntentFilter(DeviceService.ACTION_REALTIME_SAMPLES));
            if (gbDevice != null) {
                GBApplication.deviceService(gbDevice).onEnableRealtimeHeartRateMeasurement(true);
            }
            LOG.info("HR tracking turned ON for Locus");
        } else {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(hrReceiver);
            if (gbDevice != null) {
                GBApplication.deviceService(gbDevice).onEnableRealtimeHeartRateMeasurement(false);
            }
            LOG.info("HR tracking turned OFF for Locus");
        }
    }

    private final BroadcastReceiver locusInternalEventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            
            String actionPattern = intent.getAction();
            if (ACTION_LOCUS_UPDATE.equals(actionPattern) || nodomain.freeyourgadget.gadgetbridge.externalevents.LocusPeriodicUpdateReceiver.ACTION_LOCUS_STATE_UPDATE.equals(actionPattern)) {

                if (intent.hasExtra("1200")) {
                    boolean isRecording = intent.getBooleanExtra("1200", false);
                    boolean isPaused = intent.getBooleanExtra("1201", false);
                    
                    boolean targetHrState = isRecording && !isPaused;

                    if (targetHrState && !isHrSubscribed) {
                        LOG.info("Locus Map state: Recording. Starting HR stream.");
                        SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress());
                        prefs.edit().putBoolean(PREF_LOCUS_IS_RECORDING, true).apply();
                        lastLocusPingTime = System.currentTimeMillis();
                        setLiveHeartRate(true);
                    } else if (!targetHrState && isHrSubscribed) {
                        LOG.info("Locus Map state: Stopped or Paused. Stopping HR stream.");
                        SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress());
                        prefs.edit().putBoolean(PREF_LOCUS_IS_RECORDING, false).apply();
                        setLiveHeartRate(false);
                    }
                }

                if (isHrSubscribed) {
                    lastLocusPingTime = System.currentTimeMillis();
                }
                return;
            }

            if (!ACTION_LOCUS_EVENT.equals(actionPattern)) return;
            
            String data = intent.getStringExtra("data");
            if (data == null || data.isEmpty()) return;
            
            LOG.info("Received Locus internal event: " + data);
            
            try {
                JSONObject json = new JSONObject(data);
                String type = json.optString("type");
                
                if ("track_record".equals(type)) {
                    String trackAction = json.optString("action");
                    
                    SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress());
                    
                    if ("start".equals(trackAction)) {
                        LOG.info("Locus track recording started, enabling HR stream");
                        prefs.edit().putBoolean(PREF_LOCUS_IS_RECORDING, true).apply();
                        lastLocusPingTime = System.currentTimeMillis();
                        setLiveHeartRate(true);
                    } else if ("stop".equals(trackAction) || "pause".equals(trackAction)) {
                        LOG.info("Locus track recording stopped/paused, disabling HR stream");
                        prefs.edit().putBoolean(PREF_LOCUS_IS_RECORDING, false).apply();
                        setLiveHeartRate(false);
                    }
                }
            } catch (JSONException e) {
                LOG.error("Failed to parse Locus Map EVENT_RECEIVER data", e);
            }
        }
    };

    private final BroadcastReceiver hrReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !DeviceService.ACTION_REALTIME_SAMPLES.equals(intent.getAction())) return;

            if (isHrSubscribed) {
                SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(gbDevice.getAddress());
                String timeoutStr = prefs.getString(PREF_WATCHDOG_TIMEOUT, "0");
                int timeoutSec;
                try {
                    timeoutSec = Integer.parseInt(timeoutStr);
                } catch (NumberFormatException e) {
                    timeoutSec = 0;
                }
                
                if (timeoutSec > 0 && lastLocusPingTime > 0) {
                    long elapsed = System.currentTimeMillis() - lastLocusPingTime;
                    if (elapsed > timeoutSec * 1000L) {
                        LOG.warn("Locus Map watchdog timeout ({} ms > {} ms limit), disabling HR stream as Locus seems dead.", elapsed, timeoutSec * 1000L);
                        prefs.edit().putBoolean(PREF_LOCUS_IS_RECORDING, false).apply();
                        setLiveHeartRate(false);
                        return;
                    }
                }
            }

            ActivitySample sample = (ActivitySample) intent.getSerializableExtra(DeviceService.EXTRA_REALTIME_SAMPLE);
            if (sample != null && sample.getHeartRate() > 0) {
                int hr = sample.getHeartRate();

                Intent locusIntentFree = new Intent("com.asamm.locus.DATA_TASK");
                locusIntentFree.putExtra("tasks", "{heart_rate:{data:" + hr + "}}");
                locusIntentFree.setPackage("menion.android.locus");
                IntentHeartRateBroadcaster.this.context.sendBroadcast(locusIntentFree);
                
                Intent locusIntentPro = new Intent("com.asamm.locus.DATA_TASK");
                locusIntentPro.putExtra("tasks", "{heart_rate:{data:" + hr + "}}");
                locusIntentPro.setPackage("menion.android.locus.pro");
                IntentHeartRateBroadcaster.this.context.sendBroadcast(locusIntentPro);
            }
        }
    };
}
