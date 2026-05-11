package nodomain.freeyourgadget.gadgetbridge.externalevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.lang.reflect.Field;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceManager;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.test.TestDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.entities.Alarm;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceCommunicationService;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;
import nodomain.freeyourgadget.gadgetbridge.util.AlarmUtils;

public class DeviceAlarmReceiverTest extends TestBase {
    private static final String SENDER_PACKAGE = "com.example.sender";

    @Rule
    public final TestName testName = new TestName();

    private final DeviceAlarmReceiver receiver = new DeviceAlarmReceiver();

    private GBDevice device;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        device = createThreeSlotDevice();
        device.setState(GBDevice.State.INITIALIZED);
        DBHelper.getDevice(device, daoSession);
        registerDeviceWithManager(device);

        final SharedPreferences devicePrefs = GBApplication.getDeviceSpecificSharedPrefs(device.getAddress());
        assertNotNull(devicePrefs);
        devicePrefs.edit()
                .clear()
                .putBoolean("third_party_apps_set_alarms", true)
                .commit();

        GBApplication.getPrefs().getPreferences().edit()
                .putString("notification_list_is_blacklist", "true")
                .commit();
        GBApplication.setAppsNotifBlackList(Collections.emptySet());

        drainStartedServices();
    }

    @Test
    public void setAlarm_rejectsMissingDeviceAddress() {
        receiver.onReceive(getContext(), new Intent(DeviceAlarmReceiver.COMMAND_SET_ALARM));

        assertNull(getNextStartedService());
        assertEquals(0, DBHelper.getAlarms(device).size());
    }

    @Test
    public void setAlarm_rejectsInvalidMacAddress() {
        final Intent intent = baseIntent(DeviceAlarmReceiver.COMMAND_SET_ALARM);
        intent.putExtra(DeviceAlarmReceiver.EXTRA_MAC_ADDR, "invalid");

        receiver.onReceive(getContext(), intent);

        assertNull(getNextStartedService());
        assertEquals(0, DBHelper.getAlarms(device).size());
    }

    @Test
    public void setAlarm_rejectsUnknownDevice() {
        final Intent intent = baseIntent(DeviceAlarmReceiver.COMMAND_SET_ALARM);
        intent.putExtra(DeviceAlarmReceiver.EXTRA_MAC_ADDR, "00:00:00:00:00:00");

        receiver.onReceive(getContext(), intent);

        assertNull(getNextStartedService());
        assertEquals(0, DBHelper.getAlarms(device).size());
    }

    @Test
    public void setAlarm_rejectsWhenThirdPartyAlarmsAreDisabled() {
        GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()).edit()
                .putBoolean("third_party_apps_set_alarms", false)
                .commit();

        final Intent intent = setAlarmIntent(0, 7, 30, "Morning run");

        receiver.onReceive(getContext(), intent);

        assertNull(getNextStartedService());
        assertEquals(0, DBHelper.getAlarms(device).size());
    }

    @Test
    public void setAlarm_rejectsBlacklistedPackage() {
        GBApplication.setAppsNotifBlackList(new HashSet<>(Collections.singleton(SENDER_PACKAGE)));

        final Intent intent = setAlarmIntent(0, 7, 30, "Morning run");

        receiver.onReceive(getContext(), intent);

        assertNull(getNextStartedService());
        assertEquals(0, DBHelper.getAlarms(device).size());
    }

    @Test
    public void setAlarm_rejectsNonWhitelistedPackage() {
        GBApplication.getPrefs().getPreferences().edit()
                .putString("notification_list_is_blacklist", "false")
                .commit();

        final Intent intent = setAlarmIntent(0, 7, 30, "Morning run");

        receiver.onReceive(getContext(), intent);

        assertNull(getNextStartedService());
        assertEquals(0, DBHelper.getAlarms(device).size());
    }

    @Test
    public void setAlarm_updatesIndexedAlarmWithRepetition() {
        final Intent intent = setAlarmIntent(1, 7, 30, "Morning run");
        intent.putIntegerArrayListExtra(
                DeviceAlarmReceiver.EXTRA_DAYS,
                new ArrayList<>(Arrays.asList(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.SUNDAY))
        );

        receiver.onReceive(getContext(), intent);

        final Alarm alarm = getAlarm(1);
        assertTrue(alarm.getEnabled());
        assertEquals(7, alarm.getHour());
        assertEquals(30, alarm.getMinute());
        assertEquals("Morning run", alarm.getTitle());
        assertEquals(
                Alarm.ALARM_MON + Alarm.ALARM_WED + Alarm.ALARM_SUN,
                alarm.getRepetition()
        );

        final Intent forwardedIntent = getNextStartedService();
        assertForwardedAlarmUpdate(forwardedIntent, 3);
        assertNull(getNextStartedService());
    }

    @Test
    public void setAlarm_updatesAutoIndexedAlarm() {
        storeAlarm(0, true, 6, 30, 0, "already enabled");
        storeAlarm(1, false, 6, 30, 0, "first free");
        storeAlarm(2, false, 6, 30, 0, "second free");

        final Intent intent = setAlarmIntent(-1, 9, 45, "Auto slot");

        receiver.onReceive(getContext(), intent);

        final Alarm reused = getAlarm(1);
        assertTrue(reused.getEnabled());
        assertEquals(9, reused.getHour());
        assertEquals(45, reused.getMinute());
        assertEquals("Auto slot", reused.getTitle());

        final Alarm untouched1 = getAlarm(0);
        assertTrue(untouched1.getEnabled());
        assertEquals("already enabled", untouched1.getTitle());

        final Alarm untouched2 = getAlarm(2);
        assertFalse(untouched2.getEnabled());
        assertEquals("second free", untouched2.getTitle());

        assertForwardedAlarmUpdate(getNextStartedService(), 3);
    }

    @Test
    public void setAlarm_rejectsWhenNoFreeSlotIsAvailable() {
        storeAlarm(0, true, 6, 30, 0, "slot 1");
        storeAlarm(1, true, 7, 30, 0, "slot 2");
        storeAlarm(2, true, 8, 30, 0, "slot 3");

        final Intent intent = setAlarmIntent(-1, 9, 45, "No space");

        receiver.onReceive(getContext(), intent);

        assertNull(getNextStartedService());
        assertEquals(3, DBHelper.getAlarms(device).size());
        assertEquals("slot 1", getAlarm(0).getTitle());
        assertEquals("slot 2", getAlarm(1).getTitle());
        assertEquals("slot 3", getAlarm(2).getTitle());
    }

    @Test
    public void dismissAlarm_rejectsUnknownMode() {
        storeAlarm(0, true, 6, 30, 0, "slot 0");

        final Intent intent = baseIntent(DeviceAlarmReceiver.COMMAND_DISMISS_ALARM)
                .putExtra(DeviceAlarmReceiver.EXTRA_ALARM_SEARCH_MODE, "bogus");

        receiver.onReceive(getContext(), intent);

        assertTrue(getAlarm(0).getEnabled());
        assertNull(getNextStartedService());
    }

    @Test
    public void dismissAlarm_rejectsLabelModeWithoutMessage() {
        storeAlarm(0, true, 6, 30, 0, "Wake up");

        final Intent intent = baseIntent(DeviceAlarmReceiver.COMMAND_DISMISS_ALARM)
                .putExtra(DeviceAlarmReceiver.EXTRA_ALARM_SEARCH_MODE, DeviceAlarmReceiver.ALARM_SEARCH_MODE_LABEL);

        receiver.onReceive(getContext(), intent);

        assertTrue(getAlarm(0).getEnabled());
        assertNull(getNextStartedService());
    }

    @Test
    public void dismissAlarmByIndex_disablesOneAlarmAndPreservesExistingTitle() {
        storeAlarm(0, true, 6, 30, Alarm.ALARM_MON, "Wake up");

        final Intent intent = baseIntent(DeviceAlarmReceiver.COMMAND_DISMISS_ALARM)
                .putExtra(DeviceAlarmReceiver.EXTRA_ALARM_SEARCH_MODE, DeviceAlarmReceiver.ALARM_SEARCH_MODE_INDEX)
                .putExtra(DeviceAlarmReceiver.EXTRA_INDEX, 0);

        receiver.onReceive(getContext(), intent);

        final Alarm alarm = getAlarm(0);
        assertFalse(alarm.getEnabled());
        assertEquals("Wake up", alarm.getTitle());
        assertForwardedAlarmUpdate(getNextStartedService(), 3);
    }

    @Test
    public void dismissAlarmByAll_disablesEveryStoredAlarm() {
        storeAlarm(0, true, 6, 30, 0, "Wake up");
        storeAlarm(1, true, 7, 45, 0, "Standup");
        storeAlarm(2, true, 8, 15, 0, "School run");

        final Intent intent = baseIntent(DeviceAlarmReceiver.COMMAND_DISMISS_ALARM)
                .putExtra(DeviceAlarmReceiver.EXTRA_ALARM_SEARCH_MODE, DeviceAlarmReceiver.ALARM_SEARCH_MODE_ALL);

        receiver.onReceive(getContext(), intent);

        assertFalse(getAlarm(0).getEnabled());
        assertFalse(getAlarm(1).getEnabled());
        assertFalse(getAlarm(2).getEnabled());
        assertForwardedAlarmUpdate(getNextStartedService(), 3);
    }

    @Test
    public void dismissAlarmByTime_rejectsWhenNoTimeIsProvided() {
        storeAlarm(0, true, 6, 30, 0, "Wake up");

        final Intent intent = baseIntent(DeviceAlarmReceiver.COMMAND_DISMISS_ALARM)
                .putExtra(DeviceAlarmReceiver.EXTRA_ALARM_SEARCH_MODE, DeviceAlarmReceiver.ALARM_SEARCH_MODE_TIME);

        receiver.onReceive(getContext(), intent);

        assertTrue(getAlarm(0).getEnabled());
        assertNull(getNextStartedService());
    }

    @Test
    public void dismissAlarmByTime_matchesProvidedHour() {
        storeAlarm(0, true, 6, 30, 0, "Wake up");
        storeAlarm(1, true, 6, 45, 0, "Standup");
        storeAlarm(2, true, 8, 15, 0, "School run");

        final Intent intent = baseIntent(DeviceAlarmReceiver.COMMAND_DISMISS_ALARM)
                .putExtra(DeviceAlarmReceiver.EXTRA_ALARM_SEARCH_MODE, DeviceAlarmReceiver.ALARM_SEARCH_MODE_TIME)
                .putExtra(DeviceAlarmReceiver.EXTRA_HOUR, 6);

        receiver.onReceive(getContext(), intent);

        assertFalse(getAlarm(0).getEnabled());
        assertFalse(getAlarm(1).getEnabled());
        assertTrue(getAlarm(2).getEnabled());
        assertForwardedAlarmUpdate(getNextStartedService(), 3);
    }

    @Test
    public void dismissAlarmByLabel_matchesContainedTitle() {
        storeAlarm(0, true, 6, 30, 0, "Morning workout");
        storeAlarm(1, true, 7, 45, 0, "Workout cooldown");
        storeAlarm(2, true, 8, 15, 0, "School run");

        final Intent intent = baseIntent(DeviceAlarmReceiver.COMMAND_DISMISS_ALARM)
                .putExtra(DeviceAlarmReceiver.EXTRA_ALARM_SEARCH_MODE, DeviceAlarmReceiver.ALARM_SEARCH_MODE_LABEL)
                .putExtra(DeviceAlarmReceiver.EXTRA_MESSAGE, "workout");

        receiver.onReceive(getContext(), intent);

        assertFalse(getAlarm(0).getEnabled());
        assertTrue(getAlarm(1).getEnabled());
        assertTrue(getAlarm(2).getEnabled());
        assertForwardedAlarmUpdate(getNextStartedService(), 3);
    }

    @Test
    public void dismissAlarmByLabel_isCaseSensitive() {
        storeAlarm(0, true, 6, 30, 0, "Morning workout");

        final Intent intent = baseIntent(DeviceAlarmReceiver.COMMAND_DISMISS_ALARM)
                .putExtra(DeviceAlarmReceiver.EXTRA_ALARM_SEARCH_MODE, DeviceAlarmReceiver.ALARM_SEARCH_MODE_LABEL)
                .putExtra(DeviceAlarmReceiver.EXTRA_MESSAGE, "Workout");

        receiver.onReceive(getContext(), intent);

        assertTrue(getAlarm(0).getEnabled());
        assertNull(getNextStartedService());
    }

    private GBDevice createThreeSlotDevice() {
        final int suffix = Math.abs(testName.getMethodName().hashCode()) % 256;
        final String address = String.format("AA:BB:CC:DD:EE:%02X", suffix);
        return new ThreeSlotTestDevice(address);
    }

    private void registerDeviceWithManager(final GBDevice device) {
        try {
            final DeviceManager deviceManager = GBApplication.app().getDeviceManager();
            final Field field = DeviceManager.class.getDeclaredField("deviceList");
            field.setAccessible(true);
            final List<GBDevice> deviceList = (List<GBDevice>) field.get(deviceManager);
            deviceList.clear();
            deviceList.add(device);
        } catch (final Exception e) {
            throw new AssertionError("Failed to register test device", e);
        }
    }

    private Intent setAlarmIntent(final int index, final int hour, final int minutes, final String message) {
        return baseIntent(DeviceAlarmReceiver.COMMAND_SET_ALARM)
                .putExtra(DeviceAlarmReceiver.EXTRA_INDEX, index)
                .putExtra(DeviceAlarmReceiver.EXTRA_HOUR, hour)
                .putExtra(DeviceAlarmReceiver.EXTRA_MINUTES, minutes)
                .putExtra(DeviceAlarmReceiver.EXTRA_MESSAGE, message);
    }

    private Intent baseIntent(final String action) {
        return new Intent(action)
                .setPackage(SENDER_PACKAGE)
                .putExtra(DeviceAlarmReceiver.EXTRA_MAC_ADDR, device.getAddress());
    }

    private void storeAlarm(final int position, final boolean enabled, final int hour, final int minute, final int repetition, final String title) {
        final Alarm alarm = AlarmUtils.createDefaultAlarm(daoSession, device, position);
        assertNotNull(alarm);
        alarm.setEnabled(enabled);
        alarm.setHour(hour);
        alarm.setMinute(minute);
        alarm.setRepetition(repetition);
        alarm.setTitle(title);
        DBHelper.store(alarm);
    }

    private Alarm getAlarm(final int position) {
        final List<Alarm> alarms = DBHelper.getAlarmsWithDefaults(device);
        for (final Alarm alarm : alarms) {
            if (alarm.getPosition() == position) {
                return alarm;
            }
        }
        throw new AssertionError("Alarm at position " + position + " not found");
    }

    private void assertForwardedAlarmUpdate(final Intent intent, final int expectedAlarmCount) {
        assertNotNull(intent);
        assertEquals(DeviceCommunicationService.class.getName(), intent.getComponent().getClassName());
        assertEquals(DeviceService.ACTION_SET_ALARMS, intent.getAction());

        final ArrayList<? extends Alarm> alarms = (ArrayList<? extends Alarm>) intent.getSerializableExtra(DeviceService.EXTRA_ALARMS);
        assertNotNull(alarms);
        assertEquals(expectedAlarmCount, alarms.size());
    }

    private void drainStartedServices() {
        while (getNextStartedService() != null) {
            // Drain app startup noise so each test can assert only its own service invocation.
        }
    }

    private Intent getNextStartedService() {
        return shadowOf((Application) app).getNextStartedService();
    }

    private static final class ThreeSlotTestDevice extends GBDevice {
        private static final DeviceCoordinator COORDINATOR = new ThreeSlotTestCoordinator();

        private ThreeSlotTestDevice(final String address) {
            super(address, "Testie", "Test Alias", "Test Folder", DeviceType.TEST);
        }

        @Override
        public DeviceCoordinator getDeviceCoordinator() {
            return COORDINATOR;
        }
    }

    private static final class ThreeSlotTestCoordinator extends TestDeviceCoordinator {
        @Override
        public int getAlarmSlotCount(final GBDevice device) {
            return 3;
        }
    }
}
