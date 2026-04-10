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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.WithingsBaseDeviceSupport;

/**
 * ANCS notification provider for Withings ScanWatch / Steel HR.
 *
 * Matches the official Withings app's ANCS behaviour (determined via HCI snoop
 * capture analysis):
 *
 * <ul>
 *   <li><b>Fire-and-forget delivery:</b> ADDED events are sent to the watch
 *       immediately without serialization or rate-limiting. The watch manages
 *       its own internal notification queue, silently dropping some during
 *       bursts (~14% skipped PHASE2 in official app captures). DataSource
 *       responses are chunked to 20 bytes (BLE 4.0 default ATT MTU), matching
 *       the official app's behaviour -- this is critical for preventing ANCS
 *       state machine stalls in the watch firmware.</li>
 *   <li><b>No automatic REMOVED events:</b> The official app only sends
 *       NOTIFICATION_REMOVED when the user explicitly dismisses/clears a
 *       notification on the phone.</li>
 *   <li><b>BLE Notifications (not indications):</b> Both NotificationSource and
 *       DataSource use BLE Notifications (ATT opcode 0x1b, confirm=false),
 *       matching the official app. The watch subscribes for notifications
 *       (CCC=0x0001) and expects this format.</li>
 *   <li><b>Attribute format:</b> attr0=app bundle ID, attr1=sender/title,
 *       attr2=empty string (always), attr3=message body. The watch displays
 *       as "{attr1} - {attr3}".</li>
 *   <li><b>Firmware ANCS stall recovery:</b> The watch sometimes stops
 *       responding to ANCS events for unknown reasons (likely a firmware
 *       limitation). This provider detects when the watch has missed 5
 *       consecutive notifications (no Control Point writes) and automatically
 *       toggles SET_ANCS_STATUS off/on to reset the ANCS state machine.
 *       Notifications resume automatically after a 3-second recovery delay.
 *       Stale pending entries are also evicted after 60 seconds.</li>
 * </ul>
 */
public class NotificationProvider {

    private static final Logger logger = LoggerFactory.getLogger(NotificationProvider.class);
    private static final Pattern WITHINGS_SOURCE_APP_SUFFIX = Pattern.compile("-(msg|ringing|missed)$", Pattern.CASE_INSENSITIVE);
    private static final String WITHINGS_DIALER_APP_ID = "dialerApp";
    private static final String UNKNOWN_DEVICE_KEY = "unknown-withings-device";
    private static final Map<String, NotificationState> NOTIFICATION_STATES = new ConcurrentHashMap<>();
    private final WithingsBaseDeviceSupport support;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int RECENT_NOTIFICATION_CACHE_SIZE = 256;

    /**
     * How long a pending notification can sit without the watch requesting its
     * attributes before we consider it stale and remove it. The watch normally
     * fetches PHASE1 within ~200ms and PHASE2 within ~500ms, but may skip
     * PHASE2 entirely (~14% of the time based on official app captures).
     * 60 seconds is generous enough to avoid false positives.
     */
    private static final long STALE_PENDING_TIMEOUT_MS = 60_000;

    /**
     * After this many consecutive ADDED events with no watch response (no
     * Control Point writes), we toggle SET_ANCS_STATUS off/on to recover.
     * In official app captures, the watch sometimes stops responding to ANCS
     * for unknown reasons; this is a safety net.
     */
    private static final int ANCS_RECOVERY_THRESHOLD = 5;

    /**
     * Delay after ANCS reset before resuming notification delivery, giving
     * the watch time to re-establish its ANCS subscription.
     */
    private static final long ANCS_RECOVERY_DELAY_MS = 3_000;

    private static final Object ANCS_RECOVERY_TOKEN = new Object();

    public NotificationProvider(WithingsBaseDeviceSupport support) {
        this.support = support;
    }

    /**
     * Send an ANCS ADDED event for the given notification spec.
     *
     * The notification is sent immediately (fire-and-forget), matching the official
     * Withings app behaviour. The watch manages its own internal queue and fetches
     * attributes at its own pace. During bursts, the watch silently drops some
     * notifications (~14% skip PHASE2 in official captures with gaps as small as 16ms).
     *
     * To detect ANCS stalls (firmware limitation where the watch stops responding to
     * all ANCS events), we track unacknowledged notifications: every ADDED event
     * increments the counter, and any Control Point write (watch requesting attributes)
     * resets it to 0. If 5 consecutive notifications get no response, we initiate
     * auto-recovery via ANCS state machine reset.
     */
    public void notifyClient(NotificationSpec spec) {
        final NotificationState state = getState();

        // If ANCS recovery is in progress, drop this notification
        if (state.ancsRecoveryInProgress) {
            logger.info("Withings notifyClient dropping id={} -- ANCS recovery in progress", spec.getId());
            return;
        }

        if (spec.sourceAppId != null) {
            state.latestNotificationByApp.put(normalizeSourceAppId(spec.sourceAppId), spec);
            state.latestNotificationByApp.put(normalizeSourceAppId(getWithingsSourceAppId(spec)), spec);
        }

        // Clean up stale pending notifications (watch skipped PHASE2)
        evictStalePending(state);

        NotificationSource notificationSource = new NotificationSource(spec.getId(),
                                                                        AncsConstants.EVENT_ID_NOTIFICATION_ADDED,
                                                                        AncsConstants.EVENT_FLAGS_IMPORTANT,
                                                                        mapNotificationType(spec.type),
                                                                        (byte)1);

        state.pendingNotifications.put(notificationSource.getNotificationUID(),
                new PendingNotification(spec, System.currentTimeMillis()));
        state.unacknowledgedCount++;

        logger.info("Withings notifyClient SENDING id={}, source={}, type={}, pendingCount={}, unacked={}",
                spec.getId(),
                spec.sourceAppId,
                spec.type,
                state.pendingNotifications.size(),
                state.unacknowledgedCount);

        // Check if watch has stopped responding -- too many unacknowledged ADDEDs
        if (state.unacknowledgedCount >= ANCS_RECOVERY_THRESHOLD) {
            logger.warn("Withings ANCS: {} consecutive unacknowledged notifications -- initiating ANCS recovery",
                    state.unacknowledgedCount);
            initiateAncsRecovery(state);
            return;
        }

        support.sendAncsNotificationSourceNotification(notificationSource);
    }

    /**
     * Called when the user dismisses a notification on the phone.
     *
     * Sends a REMOVED event to the watch immediately, matching the official app
     * behaviour (no throttling or rate-limiting).
     */
    public void onDeleteNotification(final int notificationUID) {
        final NotificationState state = getState();
        state.pendingNotifications.remove(notificationUID);
        synchronized (state.recentlyCompletedNotifications) {
            state.recentlyCompletedNotifications.remove(notificationUID);
        }

        logger.info("Withings sending REMOVED for UID={}", notificationUID);
        sendNotificationRemoved(notificationUID);
    }

    /**
     * Handle an ANCS Control Point write from the watch requesting notification
     * attributes. The watch sends two requests per notification:
     * <ol>
     *   <li>PHASE1: requests attr0 (app bundle ID)</li>
     *   <li>PHASE2: requests attr1 (title), attr2 (subtitle), attr3 (message)</li>
     * </ol>
     *
     * The watch may interleave requests for different notification UIDs.
     * We respond immediately for each request.
     *
     * Important: Any Control Point write proves the watch is responsive, so we
     * reset the unacknowledged counter to 0 here (see {@link #notifyClient}).
     * This is the heartbeat mechanism that detects ANCS stalls.
     */
    public void handleNotificationAttributeRequest(GetNotificationAttributes request) {
        logger.debug("Request has ID: " + request.getNotificationUID());
        final NotificationState state = getState();

        // Any CP write from the watch means it's alive -- reset unacknowledged counter
        state.unacknowledgedCount = 0;

        NotificationSpec spec = null;
        PendingNotification pending = state.pendingNotifications.get(request.getNotificationUID());
        if (pending != null) {
            spec = pending.spec;
        }
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
            return;
        }

        logger.info("Handling notification attribute request id={}, attrs={}, pendingNow={}",
                request.getNotificationUID(),
                request.getAttributes().size(),
                state.pendingNotifications.size());

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
                // attr0 = app bundle ID (e.g. "com.whatsapp", "im.molly.app-msg")
                value = getWithingsSourceAppId(spec);
            }
            if (requestedAttribute.getAttributeID() == 1) {
                // attr1 = sender/title line (displayed before the dash on the watch)
                // For messaging apps: sender name (e.g., "~ Bogo", "~ Carlos Mora")
                // For other apps (Gotify, etc.): title if available, otherwise app name
                complete = true;
                if (spec.sender != null) {
                    value = spec.sender;
                } else if (spec.phoneNumber != null) {
                    value = spec.phoneNumber;
                } else if (spec.title != null) {
                    value = spec.title;
                } else if (spec.sourceName != null) {
                    value = spec.sourceName;
                } else {
                    value = "Unknown";
                }
            }
            if (requestedAttribute.getAttributeID() == 2) {
                // attr2 = subtitle -- official app always sends empty string here.
                // The watch displays "{attr1} - {attr3}", so attr2 is unused.
                complete = true;
                value = "";
            }
            if (requestedAttribute.getAttributeID() == 3) {
                // attr3 = message body (displayed after the dash on the watch)
                // For messaging apps: message content
                // For other apps: body text, or title if body is absent
                complete = true;
                if (spec.body != null) {
                    value = spec.body;
                } else if (spec.title != null && spec.sender != null) {
                    // If we used sender in attr1 and have a title but no body, show title
                    value = spec.title;
                } else {
                    value = " ";
                }
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
            // PHASE2 complete -- move notification from pending to recently-completed cache
            PendingNotification completedPending = state.pendingNotifications.remove(request.getNotificationUID());
            if (completedPending != null) {
                logger.info("Completed notification id={}, moving to recent cache, pendingAfter={}",
                        request.getNotificationUID(),
                        state.pendingNotifications.size());
                cacheCompletedNotification(state, request.getNotificationUID(), completedPending.spec);
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

    /**
     * Remove pending notifications that the watch never fetched attributes for.
     *
     * The watch normally fetches PHASE1 (attr0) within ~200ms and PHASE2 (attrs 1+2+3)
     * within ~500ms total. However, it skips PHASE2 entirely in ~14% of cases (normal
     * behaviour based on official app captures). This method evicts notifications older
     * than 60 seconds to prevent unbounded growth of the pending map while still being
     * generous enough to avoid false positives for slow watches.
     */
    private void evictStalePending(final NotificationState state) {
        final long now = System.currentTimeMillis();
        final Iterator<Map.Entry<Integer, PendingNotification>> it = state.pendingNotifications.entrySet().iterator();
        int evicted = 0;
        while (it.hasNext()) {
            final Map.Entry<Integer, PendingNotification> entry = it.next();
            if (now - entry.getValue().timestampMs > STALE_PENDING_TIMEOUT_MS) {
                it.remove();
                evicted++;
            }
        }
        if (evicted > 0) {
            logger.info("Withings evicted {} stale pending notifications, remaining={}", evicted, state.pendingNotifications.size());
        }
    }

    /**
     * Auto-recovery for firmware ANCS stall.
     *
     * The Withings ScanWatch / Steel HR firmware sometimes stops responding to ANCS
     * events mid-session (exact cause unknown, likely an internal buffer or state
     * machine issue). This method implements automatic recovery:
     *
     * <ol>
     *   <li>When the watch fails to respond to 5 consecutive ADDED events (no
     *       Control Point writes after we've sent 5 notifications), assume ANCS
     *       has stalled.</li>
     *   <li>Clear all pending notifications (they won't be fetched anyway).</li>
     *   <li>Call {@link WithingsBaseDeviceSupport#resetAncsState()} which sends
     *       SET_ANCS_STATUS(false) then SET_ANCS_STATUS(true) over the protocol
     *       channel.</li>
     *   <li>Drop incoming notifications for 3 seconds to let the watch
     *       re-establish its subscription.</li>
     *   <li>Resume normal delivery after the delay.</li>
     * </ol>
     *
     * The official Withings app doesn't have recovery logic and just lets
     * notifications silently fail until the user unlocks their phone or
     * restarts something, restarting ANCS. This automatic approach is less
     * noticeable to the user (worst case: ~5 missing notifications, 3-second
     * delay, then automatic recovery).
     */
    private void initiateAncsRecovery(final NotificationState state) {
        if (state.ancsRecoveryInProgress) {
            return;
        }
        state.ancsRecoveryInProgress = true;
        state.unacknowledgedCount = 0;

        // Save the most recent pending notification to re-send after recovery.
        // This ensures the watch has fresh content to process after re-subscribing.
        PendingNotification lastPending = null;
        for (PendingNotification p : state.pendingNotifications.values()) {
            if (lastPending == null || p.timestampMs > lastPending.timestampMs) {
                lastPending = p;
            }
        }
        final NotificationSpec resendSpec = lastPending != null ? lastPending.spec : null;

        // Clear all pending notifications -- the watch won't fetch them anyway
        state.pendingNotifications.clear();

        logger.info("Withings ANCS recovery: full enableNotifications() + re-send, will resume in {}ms", ANCS_RECOVERY_DELAY_MS);
        support.resetAncsState();

        handler.postDelayed(() -> {
            state.ancsRecoveryInProgress = false;
            logger.info("Withings ANCS recovery complete -- notification pipeline resumed");

            // Re-send the last notification so the watch has something to process
            if (resendSpec != null) {
                logger.info("Withings ANCS recovery: re-sending last notification id={}", resendSpec.getId());
                notifyClient(resendSpec);
            }
        }, ANCS_RECOVERY_DELAY_MS);
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
        for (PendingNotification pending : state.pendingNotifications.values()) {
            if (matchesSourceAppId(pending.spec, normalizedSourceAppId)) {
                return pending.spec;
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

    /**
     * Per-device notification state. Keyed by device address so multiple
     * watches can each have independent notification tracking.
     */
    private static final class NotificationState {
        /** Notifications that have been sent as ADDED but haven't completed PHASE2 yet. */
        private final Map<Integer, PendingNotification> pendingNotifications = new ConcurrentHashMap<>();
        /** Latest notification per app, for icon-tap lookup on the watch. */
        private final Map<String, NotificationSpec> latestNotificationByApp = new ConcurrentHashMap<>();
        /** Recently completed notifications, kept for re-fetch requests from the watch. */
        private final LinkedHashMap<Integer, NotificationSpec> recentlyCompletedNotifications = new LinkedHashMap<>(RECENT_NOTIFICATION_CACHE_SIZE + 1, 0.75f, true);
        /** How many consecutive ADDED events the watch has not responded to. Reset on any CP write. */
        private int unacknowledgedCount = 0;
        /** True while ANCS recovery (SET_ANCS_STATUS toggle) is in progress. */
        private volatile boolean ancsRecoveryInProgress = false;
    }

    /**
     * Wraps a {@link NotificationSpec} with a timestamp for stale eviction.
     */
    private static final class PendingNotification {
        final NotificationSpec spec;
        final long timestampMs;

        PendingNotification(NotificationSpec spec, long timestampMs) {
            this.spec = spec;
            this.timestampMs = timestampMs;
        }
    }
    
}
