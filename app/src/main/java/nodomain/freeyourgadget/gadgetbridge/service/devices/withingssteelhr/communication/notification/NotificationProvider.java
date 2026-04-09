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

import android.os.Handler;
import android.os.Looper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
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

    /**
     * Timeout in ms for a notification to complete the full ANCS attribute fetch cycle.
     * If the watch doesn't make any progress within this time, we give up and send the
     * next queued notification.
     *
     * The timeout is reset each time the watch makes progress (e.g. requests attr 0),
     * so it only fires if the watch goes completely silent for this duration.
     *
     * After a BLE reconnect the watch can take 5+ seconds to respond, so 15 seconds
     * gives plenty of margin while still recovering from truly stuck notifications.
     */
    private static final long NOTIFICATION_TIMEOUT_MS = 15000;

    /**
     * Delay after a notification completes before sending NOTIFICATION_REMOVED.
     * This gives the watch time to display the notification to the user.
     * Too short = notification disappears before user can read it.
     * Too long = watch buffer fills up during rapid notification bursts.
     * 1 second is a reasonable compromise - the watch vibrates and shows the
     * notification, and we free the slot before the buffer fills.
     */
    private static final long DISPLAY_DELAY_MS = 3000;

    /**
     * Delay after sending NOTIFICATION_REMOVED before sending the next notification.
     * The watch needs time to process the removal and free its internal buffer slot.
     */
    private static final long REMOVED_SETTLE_MS = 300;

    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());

    /** Token for timeout callbacks (separate from display/settle delay callbacks). */
    private static final Object TIMEOUT_TOKEN = new Object();
    /** Token for display delay and settle delay callbacks. */
    private static final Object DELAY_TOKEN = new Object();

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

        synchronized (state.sendQueue) {
            if (state.inFlightNotificationUID != null) {
                // A notification is currently being processed by the watch.
                // Queue this one to be sent after the current one completes.
                logger.info("Withings notifyClient QUEUED id={}, source={}, type={}, queueSize={}, inFlightUID={}",
                        spec.getId(),
                        spec.sourceAppId,
                        spec.type,
                        state.sendQueue.size() + 1,
                        state.inFlightNotificationUID);
                state.sendQueue.addLast(notificationSource);
            } else {
                // No notification in flight, send immediately.
                logger.info("Withings notifyClient SENDING id={}, source={}, type={}, pendingCount={}",
                        spec.getId(),
                        spec.sourceAppId,
                        spec.type,
                        state.pendingNotifications.size());
                // If there's a deferred NOTIFICATION_REMOVED, send it first with settle delay
                if (state.lastCompletedNotificationUID != null) {
                    final int removedUID = state.lastCompletedNotificationUID;
                    state.lastCompletedNotificationUID = null;
                    logger.info("Sending deferred NOTIFICATION_REMOVED for UID={} before new notification", removedUID);
                    sendNotificationRemoved(removedUID);
                    // Queue the new notification and send after settle delay
                    state.sendQueue.addLast(notificationSource);
                    timeoutHandler.postDelayed(() -> {
                        synchronized (state.sendQueue) {
                            if (state.inFlightNotificationUID == null) {
                                sendNextQueued(state);
                            }
                        }
                    }, REMOVED_SETTLE_MS);
                } else {
                    sendNotificationSourceNow(state, notificationSource);
                }
            }
        }
    }

    /**
     * Actually send a NotificationSource to the watch and mark it as in-flight.
     * Must be called while holding the sendQueue lock.
     */
    private void sendNotificationSourceNow(NotificationState state, NotificationSource notificationSource) {
        state.inFlightNotificationUID = notificationSource.getNotificationUID();
        support.sendAncsNotificationSourceNotification(notificationSource);
        scheduleTimeout(state, notificationSource.getNotificationUID());
    }

    /**
     * Schedule a timeout for the in-flight notification. If the watch doesn't
     * complete the attribute fetch cycle within NOTIFICATION_TIMEOUT_MS, we
     * give up and send the next queued notification.
     */
    private void scheduleTimeout(final NotificationState state, final int notificationUID) {
        // Cancel any previous timeout
        timeoutHandler.removeCallbacksAndMessages(TIMEOUT_TOKEN);

        timeoutHandler.postAtTime(() -> {
            synchronized (state.sendQueue) {
                if (state.inFlightNotificationUID != null && state.inFlightNotificationUID == notificationUID) {
                    logger.warn("Withings notification timeout for UID={}, watch did not complete attribute fetch in {}ms. Sending REMOVED and moving on.",
                            notificationUID, NOTIFICATION_TIMEOUT_MS);
                    state.inFlightNotificationUID = null;
                    // Clean up the timed-out notification from pending map to prevent unbounded growth
                    state.pendingNotifications.remove(notificationUID);
                    // Send NOTIFICATION_REMOVED for the timed-out notification to free
                    // the watch's buffer slot
                    sendNotificationRemoved(notificationUID);
                    sendNextQueued(state);
                }
            }
        }, TIMEOUT_TOKEN, android.os.SystemClock.uptimeMillis() + NOTIFICATION_TIMEOUT_MS);
    }

    /**
     * Send the next notification from the queue, if any.
     * Must be called while holding the sendQueue lock.
     */
    private void sendNextQueued(NotificationState state) {
        while (!state.sendQueue.isEmpty()) {
            NotificationSource next = state.sendQueue.pollFirst();
            // Make sure the notification is still pending (not deleted in the meantime)
            if (state.pendingNotifications.containsKey(next.getNotificationUID())) {
                logger.info("Withings sending next queued notification UID={}, remainingQueue={}",
                        next.getNotificationUID(), state.sendQueue.size());
                sendNotificationSourceNow(state, next);
                return;
            } else {
                logger.info("Withings skipping queued notification UID={} (already removed), remainingQueue={}",
                        next.getNotificationUID(), state.sendQueue.size());
            }
        }
        logger.debug("Withings notification queue empty, nothing more to send.");
    }

    public void onDeleteNotification(final int notificationUID) {
        final NotificationState state = getState();
        sendNotificationRemoved(notificationUID);
        state.pendingNotifications.remove(notificationUID);
        synchronized (state.recentlyCompletedNotifications) {
            state.recentlyCompletedNotifications.remove(notificationUID);
        }

        // If we already sent NOTIFICATION_REMOVED via the line above, clear the
        // deferred removal so we don't send a duplicate later.
        synchronized (state.sendQueue) {
            if (state.lastCompletedNotificationUID != null && state.lastCompletedNotificationUID == notificationUID) {
                state.lastCompletedNotificationUID = null;
            }

            // If the deleted notification was in-flight, release the queue
            if (state.inFlightNotificationUID != null && state.inFlightNotificationUID == notificationUID) {
                logger.info("Withings in-flight notification UID={} was deleted, releasing queue", notificationUID);
                state.inFlightNotificationUID = null;
                timeoutHandler.removeCallbacksAndMessages(TIMEOUT_TOKEN);
                timeoutHandler.removeCallbacksAndMessages(DELAY_TOKEN);
                sendNextQueued(state);
            }
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

        logger.info("Handling notification attribute request id={}, attrs={}, pendingNow={}",
                request.getNotificationUID(),
                request.getAttributes().size(),
                state.pendingNotifications.size());

        // The watch is making progress on this notification. Reset the timeout so it
        // doesn't fire between the attr-0 request and the attrs-1/2/3 request.
        // After a BLE reconnect the watch can take 5+ seconds between attr-0 and attrs-1/2/3.
        synchronized (state.sendQueue) {
            if (state.inFlightNotificationUID != null && state.inFlightNotificationUID == request.getNotificationUID()) {
                scheduleTimeout(state, request.getNotificationUID());
            }
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
                logger.info("Completed notification id={}, moving to recent cache, pendingAfter={}",
                        request.getNotificationUID(),
                        state.pendingNotifications.size());
                cacheCompletedNotification(state, request.getNotificationUID(), completedSpec);
            }

            // This notification's attribute fetch is complete. Release the queue
            // so the next notification can be sent to the watch.
            //
            // The watch has a limited notification buffer (~7-8 slots). We must send
            // NOTIFICATION_REMOVED to free the slot, but not instantly (that kills
            // the notification before the user can read it). Strategy:
            //   1. Wait DISPLAY_DELAY_MS for the user to see the notification
            //   2. Send NOTIFICATION_REMOVED to free the buffer slot
            //   3. Wait REMOVED_SETTLE_MS for the watch to process the removal
            //   4. Send the next queued notification
            final int completedUID = request.getNotificationUID();
            synchronized (state.sendQueue) {
                if (state.inFlightNotificationUID != null && state.inFlightNotificationUID == completedUID) {
                    logger.info("Withings in-flight notification UID={} completed, releasing queue (queueSize={})",
                            completedUID, state.sendQueue.size());
                    state.inFlightNotificationUID = null;
                    timeoutHandler.removeCallbacksAndMessages(TIMEOUT_TOKEN);
                    if (!state.sendQueue.isEmpty()) {
                        // Cancel any pending delay callbacks from previous cycle
                        timeoutHandler.removeCallbacksAndMessages(DELAY_TOKEN);
                        // Step 1: Wait for the notification to be displayed
                        timeoutHandler.postAtTime(() -> {
                            // Step 2: Send NOTIFICATION_REMOVED to free the buffer slot
                            logger.info("Sending NOTIFICATION_REMOVED for completed UID={} (after display delay)", completedUID);
                            sendNotificationRemoved(completedUID);

                            // Step 3: Wait for the watch to process the removal, then send next
                            timeoutHandler.postAtTime(() -> {
                                synchronized (state.sendQueue) {
                                    if (state.inFlightNotificationUID == null) {
                                        sendNextQueued(state);
                                    }
                                }
                            }, DELAY_TOKEN, android.os.SystemClock.uptimeMillis() + REMOVED_SETTLE_MS);
                        }, DELAY_TOKEN, android.os.SystemClock.uptimeMillis() + DISPLAY_DELAY_MS);
                    } else {
                        // No queue - defer REMOVED until next notification arrives or user dismisses
                        state.lastCompletedNotificationUID = completedUID;
                    }
                }
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

        /**
         * Queue of NotificationSource objects waiting to be sent to the watch.
         * The watch's ANCS state machine can only handle one notification at a time:
         * it must complete the full attribute fetch cycle (request attr 0, then attrs 1-3)
         * before it can accept the next NotificationSource. Sending a new NotificationSource
         * while the watch is mid-cycle causes its state machine to stall completely.
         */
        private final Deque<NotificationSource> sendQueue = new ArrayDeque<>();

        /**
         * The notification UID currently being processed by the watch, or null if
         * the watch is idle and ready for the next notification.
         */
        private Integer inFlightNotificationUID = null;

        /**
         * The last notification UID that completed the full attribute fetch cycle.
         * We defer sending NOTIFICATION_REMOVED until just before the next notification
         * is sent, so the watch has time to display the completed notification.
         */
        private Integer lastCompletedNotificationUID = null;
    }
    
}
