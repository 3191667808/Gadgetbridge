package nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr;

import org.junit.Test;
import java.util.UUID;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.WithingsUUIDs;
import static org.junit.Assert.assertTrue;

public class UUIDTest {
    @Test
    public void testUUIDs() {
        UUID charUuid = UUID.fromString("10000058-5749-5448-0037-000000000000");
        System.out.println("charUuid: " + charUuid);
        System.out.println("SCANWATCH: " + WithingsUUIDs.SCANWATCH.CONTROL_POINT_CHARACTERISTIC_UUID);
        assertTrue(charUuid.equals(WithingsUUIDs.SCANWATCH.CONTROL_POINT_CHARACTERISTIC_UUID));
    }
}
