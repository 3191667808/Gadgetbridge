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
package nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.incoming;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.IconHelper;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.WithingsBaseDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ImageData;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ImageMetaData;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.SourceAppId;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.Message;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.WithingsMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.WithingsMessageType;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class NotificationRequestHandler implements IncomingMessageHandler {
    private static final Logger logger = LoggerFactory.getLogger(NotificationRequestHandler.class);

    private final WithingsBaseDeviceSupport support;
    private Map<String, byte[]> appIconCache = new HashMap<>();

    public NotificationRequestHandler(WithingsBaseDeviceSupport support) {
        this.support = support;
    }

    @Override
    public void handleMessage(Message message) {
        try {
            SourceAppId appId = message.getStructureByType(SourceAppId.class);
            ImageMetaData imageMetaData = message.getStructureByType(ImageMetaData.class);
            normalizeImageMetaData(imageMetaData);
            Message reply = new WithingsMessage((short) (WithingsMessageType.GET_NOTIFICATION | 0x4000));
            reply.addDataStructure(appId);
            reply.addDataStructure(imageMetaData);

            byte[] imageData = getImageData(appId.getAppId(), imageMetaData);

            List<byte[]> imageChunks = IconHelper.splitImageData(imageData, 64);
            for (int i = 0; i < imageChunks.size(); i++) {
                ImageData imageDataStructure = new ImageData();
                imageDataStructure.setImageData(imageChunks.get(i));
                if (i == imageChunks.size() - 1) {
                    imageDataStructure.setEndOfMessage(true);
                }
                reply.addDataStructure(imageDataStructure);
            }

            logger.info("Sending reply to notification request: " + reply);
            support.sendToDevice(reply);
        } catch (Exception e) {
            logger.error("Failed to respond to notification request.", e);
            GB.toast("Failed to respond to notification request:" + e.getLocalizedMessage(), Toast.LENGTH_LONG, GB.WARN);
        }
    }

    private byte[] getImageData(String sourceAppId, ImageMetaData imageMetaData) {
        int width = imageMetaData.getWidth() & 0xFF;
        int height = imageMetaData.getHeight() & 0xFF;
        logger.info("Icon size is width='{}', height='{}'", width, height);

        String cacheKey = sourceAppId + "_" + width + "x" + height;
        byte[] imageData = appIconCache.get(cacheKey);
        if (imageData == null) {
            String packageName = sourceAppId;
            if (packageName != null) {
                packageName = packageName.replace("-msg", "").replace("-ringing", "").replace("-missed", "");
            }

            NotificationSpec notificationSpec = support.getNotificationProvider().getNotificationSpecForSourceAppId(sourceAppId);
            if (notificationSpec != null && notificationSpec.getSourceAppId() != null) {
                packageName = notificationSpec.getSourceAppId();
            }

            String iconPackageName = packageName;
            if (notificationSpec != null && notificationSpec.getIconPackageId() != null) {
                iconPackageName = notificationSpec.getIconPackageId();
            }

            logger.info("Resolving icon for sourceAppId='{}', packageName='{}', iconPackageName='{}'", sourceAppId, packageName, iconPackageName);

            try {
                Drawable icon = null;
                if (notificationSpec != null && notificationSpec.getIconId() != 0) {
                    try {
                        Context sourcePackageContext = support.getContext().createPackageContext(iconPackageName, 0);
                        icon = ResourcesCompat.getDrawable(sourcePackageContext.getResources(), notificationSpec.getIconId(), null);
                        logger.info("Loaded specific iconId={} from package {}", notificationSpec.getIconId(), iconPackageName);
                    } catch (Exception ex) {
                        logger.warn("Failed to load specific iconId={} from package {}, falling back to app icon", notificationSpec.getIconId(), iconPackageName);
                    }
                }
                if (icon == null) {
                    logger.info("Loading default application icon for package {}", packageName);
                    PackageManager pm = support.getContext().getPackageManager();
                    icon = pm.getApplicationIcon(packageName);
                }

                imageData = IconHelper.getIconBytesFromDrawable(icon, width, height);
                appIconCache.put(cacheKey, imageData);
                logger.info("Successfully rendered icon for package {} (size {}x{})", packageName, width, height);
            } catch (PackageManager.NameNotFoundException e) {
                logger.error("Error while updating notification icons for package " + packageName, e);
                try {
                    // Fallback to Gadgetbridge icon if package not found
                    logger.info("Falling back to Gadgetbridge icon");
                    PackageManager pm = support.getContext().getPackageManager();
                    Drawable icon = pm.getApplicationIcon(support.getContext().getPackageName());
                    imageData = IconHelper.getIconBytesFromDrawable(icon, width, height);
                    appIconCache.put(cacheKey, imageData);
                } catch (Exception ex) {
                    imageData = new byte[0];
                }
            } catch (Exception e) {
                logger.error("Unexpected error getting icon for " + packageName, e);
                try {
                    logger.info("Unexpected error, falling back to Gadgetbridge icon for {}", packageName);
                    PackageManager pm = support.getContext().getPackageManager();
                    Drawable icon = pm.getApplicationIcon(support.getContext().getPackageName());
                    imageData = IconHelper.getIconBytesFromDrawable(icon, width, height);
                    appIconCache.put(cacheKey, imageData);
                } catch (Exception ex) {
                    imageData = new byte[0];
                }
            }
        }
        return imageData;
    }

    static void normalizeImageMetaData(final ImageMetaData imageMetaData) {
        final int width = imageMetaData.getWidth() & 0xFF;
        final int height = imageMetaData.getHeight() & 0xFF;
        // The scanwatch only has a very small screen, and can't display large images.
        // If you send it an icon above a certain size, the watch will crash, and the
        // connection to the watch will be lost.
        // So let's shrink the icon down to a more appropriate size.
        // Or, if the size isn't defined, set it to a sensible value.
        if (width == 0 || height == 0 || width > 22 || height > 24) {
            imageMetaData.setWidth((byte) 22);
            imageMetaData.setHeight((byte) 24);
        }
    }
}
