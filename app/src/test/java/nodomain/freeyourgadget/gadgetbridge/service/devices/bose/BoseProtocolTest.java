/*  Copyright (C) 2026 Dominic Monroe

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.bose;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class BoseProtocolTest {

    private static byte[] hex(final String hex) {
        final String cleaned = hex.replaceAll("[\\s:]", "");
        final byte[] bytes = new byte[cleaned.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(cleaned.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static void assertHexEquals(final byte[] expected, final byte[] actual) {
        Assert.assertArrayEquals(expected, actual);
    }

    @Test
    public void testConnectHandshake() {
        assertHexEquals(hex("00 01 01 00"), BoseProtocol.connectHandshake());
    }

    @Test
    public void testGetBattery() {
        assertHexEquals(hex("02 02 01 00"), BoseProtocol.getBattery());
    }

    @Test
    public void testAnrLevelMapping() {
        assertHexEquals(hex("01 06 02 01 00"), BoseProtocol.setAnr(0));
        assertHexEquals(hex("01 06 02 01 03"), BoseProtocol.setAnr(1));
        assertHexEquals(hex("01 06 02 01 01"), BoseProtocol.setAnr(2));
    }

    @Test
    public void testDecodeBatteryLevel() {
        Assert.assertEquals(80, BoseProtocol.decodeBatteryLevel(hex("50 ff ff 00")));
        Assert.assertEquals(50, BoseProtocol.decodeBatteryLevel(hex("32")));
        Assert.assertEquals(-1, BoseProtocol.decodeBatteryLevel(new byte[0]));
    }

    @Test
    public void testParserSingleFrame() {
        final BoseProtocol.BoseFrameParser parser = new BoseProtocol.BoseFrameParser();
        final List<byte[]> frames = parser.feed(hex("02 02 03 01 50"));
        Assert.assertEquals(1, frames.size());
        assertHexEquals(hex("02 02 03 01 50"), frames.get(0));
    }

    @Test
    public void testParserMultipleFramesInOneChunk() {
        final BoseProtocol.BoseFrameParser parser = new BoseProtocol.BoseFrameParser();
        final List<byte[]> frames = parser.feed(hex(
                "02 02 03 01 50  01 05 03 03 0b 05 01  00 01 03 00"));
        Assert.assertEquals(3, frames.size());
        assertHexEquals(hex("02 02 03 01 50"), frames.get(0));
        assertHexEquals(hex("01 05 03 03 0b 05 01"), frames.get(1));
        assertHexEquals(hex("00 01 03 00"), frames.get(2));
    }

    @Test
    public void testParserFrameSplitAcrossFeeds() {
        final BoseProtocol.BoseFrameParser parser = new BoseProtocol.BoseFrameParser();
        Assert.assertTrue(parser.feed(hex("02 02 03")).isEmpty());
        Assert.assertTrue(parser.feed(hex("01")).isEmpty());
        final List<byte[]> frames = parser.feed(hex("50 02 02 03 01 42"));
        Assert.assertEquals(2, frames.size());
        assertHexEquals(hex("02 02 03 01 50"), frames.get(0));
        assertHexEquals(hex("02 02 03 01 42"), frames.get(1));
    }

    @Test
    public void testParserEmptyFeed() {
        final BoseProtocol.BoseFrameParser parser = new BoseProtocol.BoseFrameParser();
        Assert.assertTrue(parser.feed(new byte[0]).isEmpty());
        Assert.assertTrue(parser.feed(new byte[]{0x02, 0x02}).isEmpty());
    }

    @Test
    public void testParserUnsignedLength() {
        final BoseProtocol.BoseFrameParser parser = new BoseProtocol.BoseFrameParser();
        final byte[] frame = BoseProtocol.frame(0x04, 0x04, 0x03,
                new byte[BoseProtocol.MAX_FRAME_LENGTH]);
        final List<byte[]> frames = parser.feed(frame);
        Assert.assertEquals(1, frames.size());
        Assert.assertArrayEquals(frame, frames.get(0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFrameRejectsPayloadsLongerThanOneByteLength() {
        BoseProtocol.frame(0x04, 0x04, 0x03, new byte[BoseProtocol.MAX_FRAME_LENGTH + 1]);
    }
}
