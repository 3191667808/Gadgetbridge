/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.service;

import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowApplication;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.devices.SleepAsAndroidFeature;
import nodomain.freeyourgadget.gadgetbridge.externalevents.sleepasandroid.SleepAsAndroidAction;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs;

/**
 * The session states Sleep as Android can drive Gadgetbridge through, the gates that decide
 * whether a sample reaches it, and which actions a device is allowed to act on. A wrong feature
 * mapping here disables a feature with no error anywhere.
 */
public class SleepAsAndroidSenderSessionTest extends TestBase {

    private static final String ADDRESS = "00:11:22:33:44:55";
    private static final String SLEEP_AS_ANDROID_PACKAGE = "com.urbandroid.sleep";
    private static final String ACTION_MOVEMENT_DATA_UPDATE = "com.urbandroid.sleep.watch.DATA_UPDATE";
    private static final String ACTION_HEART_RATE_DATA_UPDATE = "com.urbandroid.sleep.watch.HR_DATA_UPDATE";

    private static final Set<SleepAsAndroidFeature> ALL_FEATURES = EnumSet.of(
            SleepAsAndroidFeature.HEART_RATE,
            SleepAsAndroidFeature.ACCELEROMETER,
            SleepAsAndroidFeature.ALARMS,
            SleepAsAndroidFeature.NOTIFICATIONS);

    private static final String[] FEATURE_PREF_KEYS = {
            "pref_key_sleepasandroid_feat_alarms",
            "pref_key_sleepasandroid_feat_notifications",
            "pref_key_sleepasandroid_feat_movement",
            "pref_key_sleepasandroid_feat_hr",
            "pref_key_sleepasandroid_feat_rr_intervals",
            "pref_key_sleepasandroid_feat_oximetry",
            "pref_key_sleepasandroid_feat_spo2",
    };

    private GBDevice device;
    private SleepAsAndroidSender sender;

    @Before
    public void setUpSender() {
        device = createDummyGDevice(ADDRESS);
        device.setState(GBDevice.State.INITIALIZED);

        // Preferences survive between tests in this class, so the per-feature toggles are cleared
        // back to "never written" to keep the default-value test meaningful.
        final SharedPreferences.Editor editor = GBApplication.getPrefs().getPreferences().edit()
                .putBoolean(GBPrefs.SLEEP_AS_ANDROID_ENABLED, true)
                .putString(GBPrefs.SLEEP_AS_ANDROID_DEVICE, ADDRESS)
                .remove(GBPrefs.SLEEP_AS_ANDROID_ALARM_SLOT);
        for (final String key : FEATURE_PREF_KEYS) {
            editor.remove(key);
        }
        editor.apply();

        sender = new SleepAsAndroidSender(device, ALL_FEATURES);
        clearBroadcasts();
    }

    @After
    public void tearDownSender() {
        if (sender != null) {
            sender.stopTracking();
        }
        GBApplication.getPrefs().getPreferences().edit()
                .remove(GBPrefs.SLEEP_AS_ANDROID_ALARM_SLOT)
                .apply();
    }

    // --- driving ------------------------------------------------------------------------------

    private SleepAsAndroidSender senderWith(final SleepAsAndroidFeature... features) {
        final Set<SleepAsAndroidFeature> set = features.length == 0
                ? EnumSet.noneOf(SleepAsAndroidFeature.class)
                : EnumSet.copyOf(Arrays.asList(features));
        return new SleepAsAndroidSender(device, set);
    }

    /** Sleep as Android marks the sensors it wants by adding the extra at all. */
    private static Bundle trackingExtras(final boolean heartRate, final boolean oximetry) {
        final Bundle extras = new Bundle();
        if (heartRate) {
            extras.putBoolean("DO_HR_MONITORING", true);
        }
        if (oximetry) {
            extras.putBoolean("DO_OXIMETER_MONITORING", true);
        }
        return extras;
    }

    /** One aggregation window worth of movement. */
    private void feedWindow(final float z) {
        feedWindow(sender, z);
    }

    private static void feedWindow(final SleepAsAndroidSender target, final float z) {
        target.onAccelChanged(0f, 0f, z);
        target.aggregateAndSendAccelData();
    }

    private static boolean allows(final SleepAsAndroidSender target, final String action) {
        try {
            target.validateAction(action);
            return true;
        } catch (final UnsupportedOperationException e) {
            return false;
        }
    }

    // --- observing ----------------------------------------------------------------------------

    private ShadowApplication shadowApp() {
        return Shadows.shadowOf((Application) GBApplication.getContext());
    }

    private void clearBroadcasts() {
        shadowApp().clearBroadcastIntents();
    }

    private List<Intent> broadcasts() {
        return shadowApp().getBroadcastIntents();
    }

    private Intent lastBroadcast(final String action) {
        Intent found = null;
        for (final Intent intent : broadcasts()) {
            if (action.equals(intent.getAction())) {
                found = intent;
            }
        }
        return found;
    }

    private int countBroadcasts(final String action) {
        int n = 0;
        for (final Intent intent : broadcasts()) {
            if (action.equals(intent.getAction())) {
                n++;
            }
        }
        return n;
    }

    private int batchLength() {
        final Intent intent = lastBroadcast(ACTION_MOVEMENT_DATA_UPDATE);
        Assert.assertNotNull(intent);
        return intent.getFloatArrayExtra("MAX_RAW_DATA").length;
    }

    // --- sensor request -------------------------------------------------------------------

    @Test
    public void sensorRequestFollowsExtraPresence() {
        sender.startTracking(trackingExtras(true, false));
        Assert.assertTrue(sender.isHeartRateRequested());
        Assert.assertFalse(sender.isOximetryRequested());

        sender.stopTracking();
        sender.startTracking(null);
        Assert.assertFalse(sender.isHeartRateRequested());
        Assert.assertFalse(sender.isOximetryRequested());
    }

    @Test
    public void theWatchdogRestartKeepsTheSensorRequest() {
        sender.startTracking(trackingExtras(true, true));

        // Sleep as Android repeats START_TRACKING as its own watchdog, and that repeat carries the
        // heart rate extra alone, which must not read as the user turning oximetry off.
        sender.startTracking(trackingExtras(true, false));

        Assert.assertTrue(sender.isHeartRateRequested());
        Assert.assertTrue(sender.isOximetryRequested());
    }

    @Test
    public void pauseAndResumeKeepTheSensorRequest() {
        sender.startTracking(trackingExtras(true, true));

        sender.pauseTracking(true);
        sender.pauseTracking(false);

        // resumeTracking() goes through the no-argument startTracking(), which must not reset the
        // request and silently re-enable a sensor the user turned off.
        Assert.assertTrue(sender.isHeartRateRequested());
        Assert.assertTrue(sender.isOximetryRequested());
    }

    // --- heart rate -----------------------------------------------------------------------

    @Test
    public void heartRateIsSentWhenRequested() {
        sender.startTracking(trackingExtras(true, false));
        clearBroadcasts();

        sender.onHrChanged(72f, 0);

        final Intent intent = lastBroadcast(ACTION_HEART_RATE_DATA_UPDATE);
        Assert.assertNotNull(intent);
        Assert.assertArrayEquals(new float[]{72f}, intent.getFloatArrayExtra("DATA"), 1e-4f);
        Assert.assertEquals(SLEEP_AS_ANDROID_PACKAGE, intent.getPackage());
    }

    @Test
    public void heartRateIsSuppressedWhenNotRequested() {
        sender.startTracking(trackingExtras(false, false));
        clearBroadcasts();

        sender.onHrChanged(72f, 0);

        Assert.assertEquals(0, countBroadcasts(ACTION_HEART_RATE_DATA_UPDATE));
    }

    @Test
    public void heartRateIsSuppressedWhenNotTracking() {
        sender.onHrChanged(72f, 0);
        Assert.assertEquals(0, countBroadcasts(ACTION_HEART_RATE_DATA_UPDATE));
    }

    @Test
    public void outOfRangeHeartRateIsDropped() {
        sender.startTracking(trackingExtras(true, false));
        clearBroadcasts();

        sender.onHrChanged(SleepAsAndroidSender.HR_MIN_VALID, 0);
        sender.onHrChanged(SleepAsAndroidSender.HR_MAX_VALID + 1f, 0);
        sender.onHrChanged(0f, 0);

        Assert.assertEquals(0, countBroadcasts(ACTION_HEART_RATE_DATA_UPDATE));
    }

    // --- accelerometer batching -----------------------------------------------------------

    @Test
    public void samplesAreOnlyAcceptedWhileTrackingRuns() {
        Assert.assertFalse(sender.acceptsAccelSamples());

        sender.startTracking(trackingExtras(false, false));
        Assert.assertTrue(sender.acceptsAccelSamples());

        sender.stopTracking();
        Assert.assertFalse(sender.acceptsAccelSamples());
    }

    @Test
    public void samplesAreNotAcceptedByADeviceWithoutTheSensor() {
        final SleepAsAndroidSender heartRateOnly = senderWith(SleepAsAndroidFeature.HEART_RATE);
        heartRateOnly.startTracking(trackingExtras(true, false));

        Assert.assertFalse(heartRateOnly.acceptsAccelSamples());
    }

    @Test
    public void accelBatchLengthMatchesRequestedBatchSize() {
        sender.setBatchSize(3);
        sender.startTracking(trackingExtras(false, false));
        clearBroadcasts();

        // Two windows must not produce a broadcast, the third must.
        feedWindow(1f);
        feedWindow(2f);
        Assert.assertEquals(0, countBroadcasts(ACTION_MOVEMENT_DATA_UPDATE));

        feedWindow(3f);

        final Intent intent = lastBroadcast(ACTION_MOVEMENT_DATA_UPDATE);
        Assert.assertNotNull(intent);
        Assert.assertEquals(3, intent.getFloatArrayExtra("MAX_RAW_DATA").length);
        Assert.assertEquals(3, intent.getFloatArrayExtra("MAX_DATA").length);
        Assert.assertEquals(3, intent.getFloatArrayExtra("MIN_DATA").length);
        Assert.assertEquals(3, intent.getFloatArrayExtra("SUM_DATA").length);
    }

    @Test
    public void batchBuffersAreClearedBetweenBatches() {
        sender.setBatchSize(2);
        sender.startTracking(trackingExtras(false, false));
        clearBroadcasts();

        feedWindow(1f);
        feedWindow(2f);
        feedWindow(3f);
        feedWindow(4f);

        Assert.assertEquals(2, countBroadcasts(ACTION_MOVEMENT_DATA_UPDATE));
        Assert.assertEquals(2, batchLength());
    }

    @Test
    public void batchSizeChangesMidSession() {
        sender.setBatchSize(4);
        sender.startTracking(trackingExtras(false, false));
        clearBroadcasts();

        for (int i = 0; i < 4; i++) {
            feedWindow(1f);
        }
        Assert.assertEquals(4, batchLength());

        // Sleep as Android drives the batch size down to 1 as an alarm approaches.
        sender.setBatchSize(1);
        feedWindow(2f);
        Assert.assertEquals(1, batchLength());

        feedWindow(3f);
        Assert.assertEquals(3, countBroadcasts(ACTION_MOVEMENT_DATA_UPDATE));
    }

    @Test
    public void emptyWindowProducesNothing() {
        sender.setBatchSize(1);
        sender.startTracking(trackingExtras(false, false));
        clearBroadcasts();

        sender.aggregateAndSendAccelData();

        Assert.assertEquals(0, countBroadcasts(ACTION_MOVEMENT_DATA_UPDATE));
    }

    @Test
    public void stoppingClearsPartialBatch() {
        sender.setBatchSize(3);
        sender.startTracking(trackingExtras(false, false));
        feedWindow(1f);
        sender.stopTracking();
        clearBroadcasts();

        // A second session must not inherit the first one's samples.
        sender.startTracking(trackingExtras(false, false));
        feedWindow(2f);
        feedWindow(3f);
        Assert.assertEquals(0, countBroadcasts(ACTION_MOVEMENT_DATA_UPDATE));

        feedWindow(4f);
        Assert.assertEquals(3, batchLength());
    }

    @Test
    public void aRepeatedStartTrackingDoesNotStackSessions() {
        // With no data for four minutes Sleep as Android re-sends START_TRACKING into a session it
        // already believes is running.
        sender.setBatchSize(2);
        sender.startTracking(trackingExtras(true, false));
        feedWindow(1f);

        sender.startTracking(trackingExtras(true, false));
        clearBroadcasts();

        feedWindow(2f);
        Assert.assertEquals(0, countBroadcasts(ACTION_MOVEMENT_DATA_UPDATE));

        feedWindow(3f);
        Assert.assertEquals(1, countBroadcasts(ACTION_MOVEMENT_DATA_UPDATE));
        Assert.assertEquals(2, batchLength());
    }

    // --- pause and suspend --------------------------------------------------------------------

    @Test
    public void suspendAndResumeMidSession() {
        sender.setBatchSize(1);
        sender.startTracking(trackingExtras(true, false));
        clearBroadcasts();

        sender.pauseTracking(true);
        feedWindow(1f);
        sender.onHrChanged(58f, 0);
        Assert.assertEquals(0, countBroadcasts(ACTION_MOVEMENT_DATA_UPDATE));
        Assert.assertEquals(0, countBroadcasts(ACTION_HEART_RATE_DATA_UPDATE));

        sender.pauseTracking(false);
        feedWindow(2f);
        sender.onHrChanged(58f, 0);
        Assert.assertEquals(1, countBroadcasts(ACTION_MOVEMENT_DATA_UPDATE));
        Assert.assertEquals(1, countBroadcasts(ACTION_HEART_RATE_DATA_UPDATE));
    }

    @Test
    public void pauseUntilAFutureTimestampSuppressesEmission() {
        sender.setBatchSize(1);
        sender.startTracking(trackingExtras(false, false));
        clearBroadcasts();

        sender.pauseTracking(60_000L);
        feedWindow(1f);

        Assert.assertEquals(0, countBroadcasts(ACTION_MOVEMENT_DATA_UPDATE));
    }

    @Test
    public void pauseUntilAPastTimestampResumesImmediately() {
        sender.setBatchSize(1);
        sender.startTracking(trackingExtras(false, false));
        clearBroadcasts();

        // Sleep as Android sends a delay of 0 or less to mean "resume now".
        sender.pauseTracking(0L);
        feedWindow(1f);

        Assert.assertEquals(1, countBroadcasts(ACTION_MOVEMENT_DATA_UPDATE));
    }

    // --- provider gating ------------------------------------------------------------------

    @Test
    public void confirmConnectedIsBroadcastToSleepAsAndroid() {
        sender.confirmConnected();

        final Intent intent = lastBroadcast(SleepAsAndroidAction.CONFIRM_CONNECTED);
        Assert.assertNotNull(intent);
        Assert.assertEquals(SLEEP_AS_ANDROID_PACKAGE, intent.getPackage());
    }

    @Test
    public void nothingIsSentWhenAnotherDeviceIsTheProvider() {
        GBApplication.getPrefs().getPreferences().edit()
                .putString(GBPrefs.SLEEP_AS_ANDROID_DEVICE, "AA:BB:CC:DD:EE:FF")
                .apply();
        clearBroadcasts();

        sender.confirmConnected();
        sender.startTracking(trackingExtras(true, false));
        sender.onHrChanged(72f, 0);

        Assert.assertEquals(0, broadcasts().size());
        Assert.assertFalse(allows(sender, SleepAsAndroidAction.CHECK_CONNECTED));
        Assert.assertFalse(allows(sender, SleepAsAndroidAction.START_TRACKING));
    }

    @Test
    public void nothingIsSentWhenTheIntegrationIsDisabled() {
        GBApplication.getPrefs().getPreferences().edit()
                .putBoolean(GBPrefs.SLEEP_AS_ANDROID_ENABLED, false)
                .apply();
        clearBroadcasts();

        sender.confirmConnected();

        Assert.assertEquals(0, broadcasts().size());
    }

    @Test
    public void nothingIsSentWhenTheDeviceIsNotInitialized() {
        device.setState(GBDevice.State.NOT_CONNECTED);
        clearBroadcasts();

        sender.confirmConnected();

        Assert.assertEquals(0, broadcasts().size());
    }

    // --- feature gating -----------------------------------------------------------------------

    @Test
    public void eachActionNeedsItsFeature() {
        for (final String action : new String[]{
                SleepAsAndroidAction.START_ALARM,
                SleepAsAndroidAction.STOP_ALARM,
                SleepAsAndroidAction.UPDATE_ALARM,
        }) {
            Assert.assertTrue(action, allows(senderWith(SleepAsAndroidFeature.ALARMS), action));
            Assert.assertFalse(action, allows(senderWith(SleepAsAndroidFeature.ACCELEROMETER), action));
        }

        Assert.assertTrue(allows(senderWith(SleepAsAndroidFeature.NOTIFICATIONS), SleepAsAndroidAction.HINT));
        Assert.assertFalse(allows(senderWith(SleepAsAndroidFeature.ALARMS), SleepAsAndroidAction.HINT));

        Assert.assertTrue(allows(senderWith(SleepAsAndroidFeature.ACCELEROMETER), SleepAsAndroidAction.SET_BATCH_SIZE));
        Assert.assertFalse(allows(senderWith(SleepAsAndroidFeature.HEART_RATE), SleepAsAndroidAction.SET_BATCH_SIZE));

        // Answering CHECK_CONNECTED is how Sleep as Android learns the wearable exists at all, so
        // it must not depend on any individual feature being available.
        Assert.assertTrue(allows(senderWith(), SleepAsAndroidAction.CHECK_CONNECTED));
    }

    @Test
    public void trackingNeedsEitherSensor() {
        // Heart rate alone is a usable session even with no accelerometer, and the reverse.
        for (final String action : new String[]{
                SleepAsAndroidAction.START_TRACKING,
                SleepAsAndroidAction.STOP_TRACKING,
                SleepAsAndroidAction.SET_PAUSE,
                SleepAsAndroidAction.SET_SUSPENDED,
        }) {
            Assert.assertTrue(action, allows(senderWith(SleepAsAndroidFeature.ACCELEROMETER), action));
            Assert.assertTrue(action, allows(senderWith(SleepAsAndroidFeature.HEART_RATE), action));
            Assert.assertFalse(action, allows(senderWith(SleepAsAndroidFeature.ALARMS), action));
        }
    }

    @Test
    public void featuresDefaultToEnabledBeforeTheSettingsScreenIsOpened() {
        // The per-feature prefs are only written once the settings screen has been shown, so the
        // code default has to match the one declared in sleepasandroid_preferences.xml.
        for (final SleepAsAndroidFeature feature : ALL_FEATURES) {
            Assert.assertTrue(feature + " should default to enabled", sender.isFeatureEnabled(feature));
        }
    }

    @Test
    public void disablingAFeaturePrefSuppressesIt() {
        GBApplication.getPrefs().getPreferences().edit()
                .putBoolean("pref_key_sleepasandroid_feat_hr", false)
                .putBoolean("pref_key_sleepasandroid_feat_alarms", false)
                .apply();
        sender.startTracking(trackingExtras(true, false));
        clearBroadcasts();

        sender.onHrChanged(72f, 0);

        Assert.assertEquals(0, countBroadcasts(ACTION_HEART_RATE_DATA_UPDATE));
        Assert.assertFalse(allows(senderWith(SleepAsAndroidFeature.ALARMS), SleepAsAndroidAction.START_ALARM));
    }

    // --- alarm slot -----------------------------------------------------------------------

    @Test
    public void alarmSlotIsRead() {
        Assert.assertEquals("an unset slot means the first one", 0, SleepAsAndroidSender.getAlarmSlot());

        GBApplication.getPrefs().getPreferences().edit()
                .putString(GBPrefs.SLEEP_AS_ANDROID_ALARM_SLOT, "3")
                .apply();

        Assert.assertEquals(3, SleepAsAndroidSender.getAlarmSlot());
    }
}
