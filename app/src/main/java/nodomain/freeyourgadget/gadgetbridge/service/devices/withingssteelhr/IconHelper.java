/*  Copyright (C) 2023-2024 Frank Ertl

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;

import nodomain.freeyourgadget.gadgetbridge.util.BitmapUtil;

public class IconHelper {

    public static byte[] getIconBytesFromDrawable(Drawable drawable) {
        return getIconBytesFromDrawable(drawable, 22, 24);
    }

    public static byte[] getIconBytesFromDrawable(Drawable drawable, int width, int height) {
        Bitmap bitmap = BitmapUtil.toBitmap(drawable);
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
        return toByteArray(scaledBitmap);
    }

    public static byte[] toByteArray(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int bytesPerColumn = getBytesPerColumn(height);
        byte[] rawData = new byte[bytesPerColumn * width];
        for (int col = 0; col < width; col++) {
            for (int row = 0; row < height; row++) {
                int pixel = bitmap.getPixel(col, row);
                if (shouldPixelbeAdded(pixel)) {
                    int bitIndex = bytesPerColumn * col + row / 8;
                    rawData[bitIndex] = setBit(rawData[bitIndex], row);
                }
            }
        }

        return rawData;
    }

    private static boolean shouldPixelbeAdded(int pixel) {
        // Workout/source icons are often black on transparent. Luma-based thresholding
        // drops black pixels and produces empty images. For Withings monochrome bitmaps,
        // any visible (non-transparent) pixel should be set.
        return Color.alpha(pixel) > 0;
    }

    private static byte setBit(byte bits, int position) {
        bits |= 1 << (position % 8);
        return bits;
    }

    private static int getBytesPerColumn(int rowCount) {
        int result = (int) rowCount / 8;
        if (result * 8 < rowCount) {
            result++;
        }

        return result;
    }
}
