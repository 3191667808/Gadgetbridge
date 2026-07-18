/*  Copyright (C) 2026 Frank Ertl

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.notification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.model.AppNotificationType;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.IconHelper;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

public class NotificationProviderTest extends TestBase {

    @Test
    public void testRegularMessageAppsStayRaw() {
        NotificationSpec spec = new NotificationSpec();
        spec.sourceAppId = "org.forkgram.messenger";
        spec.type = NotificationType.TELEGRAM;

        assertEquals("org.forkgram.messenger", NotificationProvider.getWithingsSourceAppId(spec));
    }

    @Test
    public void testForkgramIsClassifiedAsTelegram() {
        assertEquals(NotificationType.TELEGRAM, AppNotificationType.getInstance().get("org.forkgram.messenger"));
    }

    @Test
    public void testWhatsappIsClassifiedAsWhatsapp() {
        assertEquals(NotificationType.WHATSAPP, AppNotificationType.getInstance().get("com.whatsapp"));
    }

    @Test
    public void testExplicitMsgSuffixIsPreserved() {
        NotificationSpec spec = new NotificationSpec();
        spec.sourceAppId = "org.fossify.messages-msg";
        spec.type = NotificationType.GENERIC_SMS;

        assertEquals("org.fossify.messages-msg", NotificationProvider.getWithingsSourceAppId(spec));
    }

    @Test
    public void testIncomingCallsUseDialerAppId() {
        NotificationSpec spec = new NotificationSpec();
        spec.sourceAppId = "com.android.dialer";
        spec.type = NotificationType.GENERIC_PHONE;

        assertEquals("dialerApp-ringing", NotificationProvider.getWithingsSourceAppId(spec));
    }

    @Test
    public void testNotificationSpecCarriesDedicatedIconPackage() {
        NotificationSpec spec = new NotificationSpec();
        spec.sourceAppId = "com.whatsapp";
        spec.iconPackageId = "com.whatsapp";

        assertEquals("com.whatsapp", spec.iconPackageId);
    }

    @Test
    public void testIconRenderingCentersTallDrawable() {
        final Bitmap bitmap = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 80; y++) {
            for (int x = 10; x < 30; x++) {
                bitmap.setPixel(x, y, Color.BLACK);
            }
        }

        final byte[] iconBytes = IconHelper.getIconBytesFromBitmap(bitmap, 28, 28);

        final int firstFilledColumn = firstFilledColumn(iconBytes, 28, 28);
        final int lastFilledColumn = lastFilledColumn(iconBytes, 28, 28);

        assertTrue(firstFilledColumn > 0);
        assertTrue(lastFilledColumn < 27);
        assertTrue(firstFilledColumn >= 8);
        assertTrue(lastFilledColumn <= 19);
    }

    @Test
    public void testIconRenderingCropsTransparentPadding() {
        final Bitmap bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888);
        for (int y = 20; y < 60; y++) {
            for (int x = 20; x < 60; x++) {
                bitmap.setPixel(x, y, Color.BLACK);
            }
        }

        final byte[] iconBytes = IconHelper.getIconBytesFromBitmap(bitmap, 28, 28);

        assertTrue(firstFilledColumn(iconBytes, 28, 28) <= 1);
        assertTrue(lastFilledColumn(iconBytes, 28, 28) >= 26);
    }

    @Test
    public void testIconRenderingKeepsPaddingWhenCroppingDisabled() {
        final Bitmap bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888);
        for (int y = 20; y < 60; y++) {
            for (int x = 20; x < 60; x++) {
                bitmap.setPixel(x, y, Color.BLACK);
            }
        }

        final byte[] iconBytes = IconHelper.getIconBytesFromBitmap(bitmap, 28, 28, false);

        assertTrue(firstFilledColumn(iconBytes, 28, 28) > 1);
        assertTrue(lastFilledColumn(iconBytes, 28, 28) < 26);
    }

    private boolean columnHasPixels(final byte[] iconBytes, final int column, final int width, final int height) {
        final int bytesPerColumn = (height + 7) / 8;
        for (int i = 0; i < bytesPerColumn; i++) {
            if (iconBytes[column * bytesPerColumn + i] != 0) {
                return true;
            }
        }
        return false;
    }

    private int firstFilledColumn(final byte[] iconBytes, final int width, final int height) {
        for (int column = 0; column < width; column++) {
            if (columnHasPixels(iconBytes, column, width, height)) {
                return column;
            }
        }

        return -1;
    }

    private int lastFilledColumn(final byte[] iconBytes, final int width, final int height) {
        for (int column = width - 1; column >= 0; column--) {
            if (columnHasPixels(iconBytes, column, width, height)) {
                return column;
            }
        }

        return -1;
    }
}
