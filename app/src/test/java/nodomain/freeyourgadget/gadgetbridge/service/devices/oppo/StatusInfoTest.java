package nodomain.freeyourgadget.gadgetbridge.service.devices.oppo;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.StatusInfoSide;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oppo.commands.StatusInfoValue;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class StatusInfoTest extends TestBase {
    private static final StatusInfo statusInfo = new StatusInfo(GBApplication.getContext());

    @Test
    public void testStatusAllInCase() {
        final Map<StatusInfoSide, StatusInfoValue> result = statusInfo.decode(GB.hexStringToByteArray("0003010002000304"));
        final Map<StatusInfoSide, StatusInfoValue> expected = new HashMap<>(2);
        expected.put(StatusInfoSide.LEFT, StatusInfoValue.IN_CASE);
        expected.put(StatusInfoSide.RIGHT, StatusInfoValue.IN_CASE);
        Assert.assertEquals(expected,result);
    }

    @Test
    public void testStatusAllReady() {
        final Map<StatusInfoSide, StatusInfoValue> result = statusInfo.decode(GB.hexStringToByteArray("0003010302030304"));
        final Map<StatusInfoSide, StatusInfoValue> expected = new HashMap<>(2);
        expected.put(StatusInfoSide.LEFT, StatusInfoValue.READY);
        expected.put(StatusInfoSide.RIGHT, StatusInfoValue.READY);
        Assert.assertEquals(expected,result);
    }

    @Test
    public void testStatusAllOffEar() {
        final Map<StatusInfoSide, StatusInfoValue> result = statusInfo.decode(GB.hexStringToByteArray("0003010102010304"));
        final Map<StatusInfoSide, StatusInfoValue> expected = new HashMap<>(2);
        expected.put(StatusInfoSide.LEFT, StatusInfoValue.OFF_EAR);
        expected.put(StatusInfoSide.RIGHT, StatusInfoValue.OFF_EAR);
        Assert.assertEquals(expected,result);
    }
}
