package nodomain.freeyourgadget.gadgetbridge.service.devices.oppo;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class FirmwareInfoTest extends TestBase {
    @Test
    public void testHandleFirmware() {
        List<GBDeviceEvent> oppoEvents = new FirmwareInfo(GB.hexStringToByteArray(
                "000a312c312c312c312c322c3136302c312c332c3838382c312c342c302c322c312c312c322c322c3136302c322c332c3838382c322c342c302c332c312c312c332c322c38323700"))
                .decode();
        Assert.assertEquals(1, oppoEvents.size());
        GBDeviceEventVersionInfo oppoEvent = (GBDeviceEventVersionInfo) oppoEvents.get(0);
        Assert.assertEquals("160.160.827", oppoEvent.fwVersion);

        List<GBDeviceEvent> air2Events = new FirmwareInfo(GB.hexStringToByteArray(
                "0009312C312C302C312C322C3134322C312C342C302C322C312C382C322C322C3134322C322C342C302C332C312C33332C332C322C3132392C332C342C30"))
                .decode();
        Assert.assertEquals(1, air2Events.size());
        GBDeviceEventVersionInfo air2event = (GBDeviceEventVersionInfo) air2Events.get(0);
        Assert.assertEquals("142.142.129", air2event.fwVersion);

        List<GBDeviceEvent> realme = new FirmwareInfo(GB.hexStringToByteArray(
                "0003312c322c312e312e302e37352c322c322c312e312e302e37352c332c322c303031")).decode();
        Assert.assertEquals(1, realme.size());
        GBDeviceEventVersionInfo realmeEvent = (GBDeviceEventVersionInfo) realme.get(0);
        Assert.assertEquals("1.1.0.75", realmeEvent.fwVersion);
    }
}
