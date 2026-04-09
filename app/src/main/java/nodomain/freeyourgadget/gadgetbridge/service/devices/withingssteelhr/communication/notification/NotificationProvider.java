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
package nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.WithingsBaseDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class NotificationProvider {

    private static final Logger logger = LoggerFactory.getLogger(NotificationProvider.class);
    private static final Pattern WITHINGS_SOURCE_APP_SUFFIX = Pattern.compile("-(msg|ringing|missed)$", Pattern.CASE_INSENSITIVE);
    private static final String WITHINGS_DIALER_APP_ID = "dialerApp";
    private static final String UNKNOWN_DEVICE_KEY = "unknown-withings-device";
    private static final Map<String, NotificationState> NOTIFICATION_STATES = new ConcurrentHashMap<>();
    private final WithingsBaseDeviceSupport support;
    private static final int RECENT_NOTIFICATION_CACHE_SIZE = 256;

    public NotificationProvider(WithingsBaseDeviceSupport support) {
        this.support = support;
    }

    public void notifyClient(NotificationSpec spec) {
        final NotificationState state = getState();
        if (spec.sourceAppId != null) {
            state.latestNotificationByApp.put(normalizeSourceAppId(spec.sourceAppId), spec);
            state.latestNotificationByApp.put(normalizeSourceAppId(getWithingsSourceAppId(spec)), spec);
        }
        NotificationSource notificationSource = new NotificationSource(spec.getId(),
                                                                        AncsConstants.EVENT_ID_NOTIFICATION_ADDED,
                                                                        AncsConstants.EVENT_FLAGS_IMPORTANT,
                                                                        mapNotificationType(spec.type),
                                                                        (byte)1);
        state.pendingNotifications.put(notificationSource.getNotificationUID(), spec);
        support.sendAncsNotificationSourceNotification(notificationSource);
    }

    public void onDeleteNotification(final int notificationUID) {
        final NotificationState state = getState();
        sendNotificationRemoved(notificationUID);
        state.pendingNotifications.remove(notificationUID);
        synchronized (state.recentlyCompletedNotifications) {
            state.recentlyCompletedNotifications.remove(notificationUID);
        }
    }

    public void handleNotificationAttributeRequest(GetNotificationAttributes request) {
        logger.debug("Request has ID: " + request.getNotificationUID());
        final NotificationState state = getState();
        NotificationSpec spec = state.pendingNotifications.get(request.getNotificationUID());
        if (spec == null) {
            synchronized (state.recentlyCompletedNotifications) {
                spec = state.recentlyCompletedNotifications.get(request.getNotificationUID());
            }
        }
        if (spec == null) {
            logger.info("No pending notification with notificationUID " + request.getNotificationUID());

            // Reply on the Data Source to unblock the watch's ANCS state machine
            GetNotificationAttributesResponse emptyResponse = new GetNotificationAttributesResponse(request.getNotificationUID());
            for (RequestedNotificationAttribute requestedAttribute : request.getAttributes()) {
                NotificationAttribute attr = new NotificationAttribute();
                attr.setAttributeID(requestedAttribute.getAttributeID());
                attr.setAttributeMaxLength(requestedAttribute.getAttributeMaxLength());
                attr.setValue("");
                emptyResponse.addAttribute(attr);
            }
            support.sendAncsDataSourceNotification(emptyResponse);

            sendNotificationRemoved(request.getNotificationUID());
            return;
        }

        GetNotificationAttributesResponse response = new GetNotificationAttributesResponse(request.getNotificationUID());
        List<RequestedNotificationAttribute> requestedAttributes = request.getAttributes();
        logger.debug(requestedAttributes.size() + " attributes requested.");

        boolean complete = false;

        for (RequestedNotificationAttribute requestedAttribute : requestedAttributes) {
            NotificationAttribute attribute = new NotificationAttribute();
            attribute.setAttributeID(requestedAttribute.getAttributeID());
            attribute.setAttributeMaxLength(requestedAttribute.getAttributeMaxLength());
            logger.debug("Handling attribute " + attribute.getAttributeID() + " with maxLength " + attribute.getAttributeLength());
            String value = "";
            if (requestedAttribute.getAttributeID() == 0) {
                value = getWithingsSourceAppId(spec);
            }
            if (requestedAttribute.getAttributeID() == 1) {
                complete = true;
                value = spec.sender != null? spec.sender : (spec.phoneNumber != null? spec.phoneNumber : (spec.sourceName != null? spec.sourceName : "Unknown"));
            }
            if (requestedAttribute.getAttributeID() == 2) {
                complete = true;
                value = spec.title != null? spec.title : (spec.subject != null? spec.subject : " ");
            }
            if (requestedAttribute.getAttributeID() == 3) {
                complete = true;
                value = (spec.body != null? spec.body : " ");
            }

            if (value != null) {
                // Remove linefeed and carriage returns as the watch cannot display this:
                value = value.replace("\n", " ");
                value = value.replace("\r", " ");
                if (requestedAttribute.getAttributeMaxLength() == 0 || requestedAttribute.getAttributeMaxLength() >= value.length()) {
                    attribute.setValue(value);
                } else {
                    attribute.setValue(value.substring(0, requestedAttribute.getAttributeMaxLength()));
                }
            }

            logger.debug("Sending attribute " + attribute.getAttributeID() + " with value " + attribute.getValue());
            response.addAttribute(attribute);
        }

        support.sendAncsDataSourceNotification(response);

        if (complete) {
            NotificationSpec completedSpec = state.pendingNotifications.remove(request.getNotificationUID());
            if (completedSpec != null) {
                cacheCompletedNotification(state, request.getNotificationUID(), completedSpec);
            }
        }
    }

    private void sendNotificationRemoved(final int notificationUID) {
        NotificationSource notificationSource = new NotificationSource(notificationUID,
                                                                        AncsConstants.EVENT_ID_NOTIFICATION_REMOVED,
                                                                        AncsConstants.EVENT_FLAGS_IMPORTANT,
                                                                        (byte)0,
                                                                        (byte)0);
        support.sendAncsNotificationSourceNotification(notificationSource);
    }

    private void cacheCompletedNotification(final NotificationState state, final int notificationUID, final NotificationSpec completedSpec) {
        synchronized (state.recentlyCompletedNotifications) {
            state.recentlyCompletedNotifications.put(notificationUID, completedSpec);
            while (state.recentlyCompletedNotifications.size() > RECENT_NOTIFICATION_CACHE_SIZE) {
                final Integer eldestKey = state.recentlyCompletedNotifications.keySet().iterator().next();
                state.recentlyCompletedNotifications.remove(eldestKey);
            }
        }
    }

    public NotificationSpec getNotificationSpecForSourceAppId(String sourceAppId) {
        final String normalizedSourceAppId = normalizeSourceAppId(sourceAppId);
        final NotificationState state = getState();
        
        // First try finding it in pending notifications (if still active)
        for (NotificationSpec notificationSpec : state.pendingNotifications.values()) {
            if (matchesSourceAppId(notificationSpec, normalizedSourceAppId)) {
                return notificationSpec;
            }
        }

        // Fallback to the latest known notification spec for this app
        return state.latestNotificationByApp.get(normalizedSourceAppId);
    }

    private NotificationState getState() {
        return NOTIFICATION_STATES.computeIfAbsent(getDeviceKey(), ignored -> new NotificationState());
    }

    private String getDeviceKey() {
        if (support.getDevice() != null && support.getDevice().getAddress() != null) {
            return support.getDevice().getAddress();
        }
        return UNKNOWN_DEVICE_KEY;
    }

    private String normalizeSourceAppId(final String sourceAppId) {
        if (sourceAppId == null) {
            return null;
        }
        return WITHINGS_SOURCE_APP_SUFFIX.matcher(sourceAppId).replaceFirst("");
    }

    static String getWithingsSourceAppId(final NotificationSpec spec) {
        if (spec == null || spec.sourceAppId == null) {
            return null;
        }

        if (WITHINGS_SOURCE_APP_SUFFIX.matcher(spec.sourceAppId).find()) {
            return spec.sourceAppId;
        }

        if (spec.type == NotificationType.GENERIC_PHONE) {
            return WITHINGS_DIALER_APP_ID + "-ringing";
        }

        return spec.sourceAppId;
    }

    private boolean matchesSourceAppId(final NotificationSpec notificationSpec, final String normalizedSourceAppId) {
        if (notificationSpec.sourceAppId != null && notificationSpec.sourceAppId.equalsIgnoreCase(normalizedSourceAppId)) {
            return true;
        }

        final String withingsSourceAppId = normalizeSourceAppId(getWithingsSourceAppId(notificationSpec));
        return withingsSourceAppId != null && withingsSourceAppId.equalsIgnoreCase(normalizedSourceAppId);
    }

    private byte mapNotificationType(NotificationType type) {
        switch (type) {
            case GENERIC_ALARM_CLOCK:
            case BUSINESS_CALENDAR:
            case GENERIC_CALENDAR:
                return AncsConstants.CATEGORY_ID_SCHEDULE;
            case GENERIC_EMAIL:
            case YAHOO_MAIL:
            case GOOGLE_INBOX:
            case GMAIL:
            case OUTLOOK:
                return AncsConstants.CATEGORY_ID_EMAIL;
            case GENERIC_NAVIGATION:
                return AncsConstants.CATEGORY_ID_LOCATION;
            case GENERIC_PHONE:
                return AncsConstants.CATEGORY_ID_INCOMING_CALL;
            case MAILBOX:
                return AncsConstants.CATEGORY_ID_MISSED_CALL;
            case LINE:
            case SIGNAL:
            case WIRE:
            case SKYPE:
            case SLACK:
            case SNAPCHAT:
            case TELEGRAM:
            case THREEMA:
            case KONTALK:
            case ANTOX:
            case DISCORD:
            case TRANSIT:
            case TWITTER:
            case VIBER:
            case WECHAT:
            case WHATSAPP:
            case FACEBOOK:
            case FACEBOOK_MESSENGER:
            case LINKEDIN:
            case HIPCHAT:
            case INSTAGRAM:
            case KAKAO_TALK:
            case GENERIC_SMS:
            case GOOGLE_MESSENGER:
            case GOOGLE_HANGOUTS:
                return AncsConstants.CATEGORY_ID_SOCIAL;
            default:
                return AncsConstants.CATEGORY_ID_OTHER;
        }
    }

    private static final class NotificationState {
        private final Map<Integer, NotificationSpec> pendingNotifications = new ConcurrentHashMap<>();
        private final Map<String, NotificationSpec> latestNotificationByApp = new ConcurrentHashMap<>();
        private final LinkedHashMap<Integer, NotificationSpec> recentlyCompletedNotifications = new LinkedHashMap<>(RECENT_NOTIFICATION_CACHE_SIZE + 1, 0.75f, true);
    }
    
}
