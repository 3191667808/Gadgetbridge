package nodomain.freeyourgadget.gadgetbridge.externalevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.content.SharedPreferences;

import org.junit.Test;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

public class RealtimeHrBroadcastTest extends TestBase {
    private static final String MAC_ADDR = "00:11:22:33:44:55";

    private GBDevice device;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        device = createDummyGDevice(MAC_ADDR);

        final SharedPreferences devicePrefs = GBApplication.getDeviceSpecificSharedPrefs(device.getAddress());
        devicePrefs.edit().clear().commit();
    }

    private void setBroadcastEnabled(final boolean enabled) {
        GBApplication.getDeviceSpecificSharedPrefs(device.getAddress())
                .edit()
                .putBoolean(RealtimeHrBroadcast.PREF_THIRD_PARTY_APPS_REALTIME_HR, enabled)
                .commit();
    }

    private List<Intent> getBroadcasts() {
        return shadowOf(app).getBroadcastIntents();
    }

    @Test
    public void testDisabledByDefault() {
        RealtimeHrBroadcast.sendIfEnabled(app, device, 72);

        assertTrue("no broadcast should be sent when the preference is not enabled", getBroadcasts().isEmpty());
    }

    @Test
    public void testEnabledSendsBroadcast() {
        setBroadcastEnabled(true);

        final long before = System.currentTimeMillis();
        RealtimeHrBroadcast.sendIfEnabled(app, device, 72);
        final long after = System.currentTimeMillis();

        final List<Intent> broadcasts = getBroadcasts();
        assertEquals(1, broadcasts.size());

        final Intent intent = broadcasts.get(0);
        assertEquals(RealtimeHrBroadcast.ACTION_REALTIME_HR, intent.getAction());
        assertEquals(72, intent.getIntExtra(RealtimeHrBroadcast.EXTRA_HR, -1));
        assertEquals(MAC_ADDR, intent.getStringExtra(RealtimeHrBroadcast.EXTRA_MAC_ADDR));

        final long timestamp = intent.getLongExtra(RealtimeHrBroadcast.EXTRA_TIMESTAMP, -1);
        assertTrue("timestamp should be epoch millis of the measurement", timestamp >= before && timestamp <= after);
    }

    @Test
    public void testExplicitlyDisabled() {
        setBroadcastEnabled(false);

        RealtimeHrBroadcast.sendIfEnabled(app, device, 72);

        assertTrue(getBroadcasts().isEmpty());
    }

    @Test
    public void testInvalidHrNotBroadcast() {
        setBroadcastEnabled(true);

        RealtimeHrBroadcast.sendIfEnabled(app, device, 0);
        RealtimeHrBroadcast.sendIfEnabled(app, device, -1);
        RealtimeHrBroadcast.sendIfEnabled(app, device, 5);
        RealtimeHrBroadcast.sendIfEnabled(app, device, 255);

        assertTrue("invalid or sentinel heart rate values should not be broadcast", getBroadcasts().isEmpty());
    }
}
