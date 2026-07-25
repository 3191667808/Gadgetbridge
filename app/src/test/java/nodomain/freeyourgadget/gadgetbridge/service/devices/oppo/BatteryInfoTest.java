package nodomain.freeyourgadget.gadgetbridge.service.devices.oppo;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Assert;
import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class BatteryInfoTest extends TestBase {
    private static final BatteryInfo batteryInfo = new BatteryInfo(GBApplication.getContext());

    @Test
    public void testHandleBatteryAll() {
        List<GBDeviceEvent> events = batteryInfo.decode(GB.hexStringToByteArray(
                "0003014602640346"));
        Assert.assertEquals(3, events.size());

        Set<Integer> uniqueIndices = events.stream()
                .map(event -> ((GBDeviceEventBatteryInfo) event).batteryIndex)
                .collect(Collectors.toSet());
        Assert.assertEquals(events.size(), uniqueIndices.size());

        for (GBDeviceEvent event : events) {
            final GBDeviceEventBatteryInfo battery = (GBDeviceEventBatteryInfo) event;
            switch (battery.batteryIndex) {
                case 0:
                    Assert.assertEquals(70, battery.level);
                    Assert.assertEquals(BatteryState.BATTERY_NORMAL, battery.state);
                    break;
                case 1:
                    Assert.assertEquals(100, battery.level);
                    Assert.assertEquals(BatteryState.BATTERY_NORMAL, battery.state);
                    break;
                case 2:
                    Assert.assertEquals(70, battery.level);
                    Assert.assertEquals(BatteryState.BATTERY_NORMAL, battery.state);
                    break;
            }
        }
    }

    @Test
    public void testHandleBatteryWithoutCase() {
        List<GBDeviceEvent> events = batteryInfo.decode(GB.hexStringToByteArray(
                "0003014602640300"));
        Assert.assertEquals(3, events.size());

        Set<Integer> uniqueIndices = events.stream()
                .map(event -> ((GBDeviceEventBatteryInfo) event).batteryIndex)
                .collect(Collectors.toSet());
        Assert.assertEquals(events.size(), uniqueIndices.size());

        for (GBDeviceEvent event : events) {
            final GBDeviceEventBatteryInfo battery = (GBDeviceEventBatteryInfo) event;
            switch (battery.batteryIndex) {
                case 0:
                    Assert.assertEquals(70, battery.level);
                    Assert.assertEquals(BatteryState.BATTERY_NORMAL, battery.state);
                    break;
                case 1:
                    Assert.assertEquals(100, battery.level);
                    Assert.assertEquals(BatteryState.BATTERY_NORMAL, battery.state);
                    break;
                case 2:
                    Assert.assertEquals(-1, battery.level);
                    Assert.assertEquals(BatteryState.UNKNOWN, battery.state);
                    break;
            }
        }
    }

    @Test
    public void testHandleBatteryRight() {
        List<GBDeviceEvent> events = batteryInfo.decode(GB.hexStringToByteArray(
                "000202640300"));
        Assert.assertEquals(3, events.size());

        Set<Integer> uniqueIndices = events.stream()
                .map(event -> ((GBDeviceEventBatteryInfo) event).batteryIndex)
                .collect(Collectors.toSet());
        Assert.assertEquals(events.size(), uniqueIndices.size());

        for (GBDeviceEvent event : events) {
            final GBDeviceEventBatteryInfo battery = (GBDeviceEventBatteryInfo) event;
            switch (battery.batteryIndex) {
                case 1:
                    Assert.assertEquals(100, battery.level);
                    Assert.assertEquals(BatteryState.BATTERY_NORMAL, battery.state);
                    break;
                case 0:
                case 2:
                    Assert.assertEquals(-1, battery.level);
                    Assert.assertEquals(BatteryState.UNKNOWN, battery.state);
                    break;
            }
        }
    }

    @Test
    public void testHandleBatteryLeft() {
        List<GBDeviceEvent> events = batteryInfo.decode(GB.hexStringToByteArray(
                "000301460300"));
        Assert.assertEquals(3, events.size());

        Set<Integer> uniqueIndices = events.stream()
                .map(event -> ((GBDeviceEventBatteryInfo) event).batteryIndex)
                .collect(Collectors.toSet());
        Assert.assertEquals(events.size(), uniqueIndices.size());

        for (GBDeviceEvent event : events) {
            final GBDeviceEventBatteryInfo battery = (GBDeviceEventBatteryInfo) event;
            switch (battery.batteryIndex) {
                case 0:
                    Assert.assertEquals(70, battery.level);
                    Assert.assertEquals(BatteryState.BATTERY_NORMAL, battery.state);
                    break;
                case 1:
                case 2:
                    Assert.assertEquals(-1, battery.level);
                    Assert.assertEquals(BatteryState.UNKNOWN, battery.state);
                    break;
            }
        }
    }

}
