package nodomain.freeyourgadget.gadgetbridge.service.devices.magene;

import static nodomain.freeyourgadget.gadgetbridge.util.ArrayUtils.arrayToString;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.config.NotificationsConfig;
import nodomain.freeyourgadget.gadgetbridge.devices.magene.srap.config.UserInfo;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class MageneConfigFileTest {

    @Test
    public void testUserInfoFile() throws IOException {
        byte[] expectedOutput = GB.hexStringToByteArray("4A6F6E6820446F650000000000000000000000000000000000000000000000004336303600000000000000000000000000000000000000000000000000000000B900B900EF00C607060101C0B603000000000000000000000000000000000000000000000000000001000000A55A55AA");
        UserInfo config = new UserInfo("Jonh Doe", "C606", 185, 185, 239, 1990, 6, 1, 1, 192, 950);
        byte[] output = config.serializeToByteArray();
        System.out.println(arrayToString(output));
        System.out.println(config);
        Assert.assertArrayEquals(expectedOutput, output);
    }

    @Test
    public void testParseConfigtoUserInfo() throws IOException {
        String expectedOutput ="UserInfo{userName='Jonh Doe', deviceName='C606', mhrBpm=185, lthrBpm=185, ftp=239, year=1990, month=6, day=1, gender=1, height=192, weight=950 (95.0 kg)}";
        byte[] input = GB.hexStringToByteArray("4A6F6E6820446F650000000000000000000000000000000000000000000000004336303600000000000000000000000000000000000000000000000000000000B900B900EF00C607060101C0B603000000000000000000000000000000000000000000000000000001000000A55A55AA");
        UserInfo config = UserInfo.fromBytes(input);
        System.out.println(config);
        Assert.assertEquals(expectedOutput, config.toString());
    }

    @Test
    public void testNotificationsConfig() throws IOException {
        // notifications enabled, only skype enabled
        byte[] expectedOutput = GB.hexStringToByteArray("0100000000000100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001000000A55A55AA");
        NotificationsConfig config = new NotificationsConfig(
                true,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false
        );
        System.out.println(config);
        byte[] output = config.serializeToByteArray();
        System.out.println(arrayToString(output));
        Assert.assertArrayEquals(expectedOutput, output);
    }


}
