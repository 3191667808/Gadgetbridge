/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiAgpsFetcher;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiPreferences;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiSupport;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

/**
 * GNSS command channel (command type 16), used by bands with an onboard GPS to update their
 * assisted-GPS (AGPS) data.
 *
 * An assistance push sends the phone's approximate position (subtype 2), then uploads a u-blox
 * UBX-MGA-ANO bundle via {@link XiaomiDataUploadService} (upload type
 * {@link XiaomiDataUploadService#TYPE_AGPS}). The bundle comes from a public source via
 * {@link XiaomiAgpsFetcher}, or from a {@code .brm} the user installs manually. Pushing is gated on
 * {@code XiaomiCoordinator#supportsAgpsUpdates}; with the "auto AGPS" toggle on, stale data is
 * refreshed on connect ({@link #initialize()}).
 */
public class XiaomiGnssService extends AbstractXiaomiService implements XiaomiDataUploadService.Callback {
    private static final Logger LOG = LoggerFactory.getLogger(XiaomiGnssService.class);

    public static final int COMMAND_TYPE = 16;

    // Auto-refresh when the stored assistance is older than this. The bundle covers 7 days, so
    // refreshing past 3 days keeps a comfortable validity margin.
    private static final long STALE_MS = 3L * 24 * 60 * 60 * 1000;

    //   subtype 1 -> GNSS/chip info, carries the chip vendor string
    //   subtype 2 -> phone to watch approximate position, sent before the assistance upload
    //   subtype 3 -> empty body
    private static final int CMD_GNSS_INFO = 1;
    private static final int CMD_GNSS_LOCATION = 2;
    private static final int CMD_GNSS_UNKNOWN_3 = 3;

    public XiaomiGnssService(final XiaomiSupport support) {
        super(support);
    }

    @Override
    public void handleCommand(final XiaomiProto.Command cmd) {
        switch (cmd.getSubtype()) {
            case CMD_GNSS_INFO:
                LOG.debug("Got GNSS chip info: {}", cmd.getGnss().getChipInfo().getChip());
                return;
            case CMD_GNSS_UNKNOWN_3:
                LOG.debug("Got GNSS command subtype {}", cmd.getSubtype());
                return;
        }

        LOG.warn("Unknown GNSS command {}", cmd.getSubtype());
    }

    /**
     * Send the phone's approximate position to the watch (type 16, subtype 2), which lets the
     * receiver narrow its search. Sent just before an assistance upload. Skipped when there is no
     * recent location or no location permission.
     */
    private void sendPosition() {
        final Location loc = getLastKnownLocation();
        if (loc == null) {
            LOG.debug("No known location, skipping GNSS position");
            return;
        }
        final XiaomiProto.GnssLocation location = XiaomiProto.GnssLocation.newBuilder()
                .setTimestamp((int) (System.currentTimeMillis() / 1000L))
                .setLongitude(loc.getLongitude())
                .setLatitude(loc.getLatitude())
                .setAltitude(loc.hasAltitude() ? loc.getAltitude() : 0)
                .build();
        getSupport().sendCommand(
                "gnss position",
                XiaomiProto.Command.newBuilder()
                        .setType(COMMAND_TYPE)
                        .setSubtype(CMD_GNSS_LOCATION)
                        .setGnss(XiaomiProto.Gnss.newBuilder().setLocation(location))
                        .build()
        );
    }

    private Location getLastKnownLocation() {
        try {
            final LocationManager lm = (LocationManager) getSupport().getContext()
                    .getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                return null;
            }
            for (final String provider : new String[]{
                    LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                final Location loc = lm.getLastKnownLocation(provider);
                if (loc != null) {
                    return loc;
                }
            }
        } catch (final SecurityException e) {
            LOG.debug("No location permission for GNSS position");
        } catch (final Exception e) {
            LOG.warn("Failed to get last known location", e);
        }
        return null;
    }

    /**
     * On connect, refresh the AGPS data automatically if the user enabled auto-update and the
     * stored data is stale (or never fetched). Best-effort and silent on failure.
     */
    @Override
    public void initialize() {
        if (!getCoordinator().supportsAgpsUpdates(getSupport().getDevice())) {
            return;
        }
        final Prefs prefs = getDevicePrefs();
        if (!prefs.getBoolean(XiaomiPreferences.PREF_AGPS_AUTO, false)) {
            return;
        }
        if (!GBApplication.hasInternetAccess()) {
            LOG.debug("AGPS auto-update enabled but no internet access");
            return;
        }
        final long last = prefs.getLong(XiaomiPreferences.PREF_AGPS_UPDATE_TIME, 0L);
        if (last != 0L && System.currentTimeMillis() - last < STALE_MS) {
            LOG.debug("AGPS data still fresh, skipping auto-update");
            return;
        }
        LOG.info("AGPS data stale (last update {}), auto-refreshing", last);
        triggerFetch(false);
    }

    @Override
    public boolean onSendConfiguration(final String config, final Prefs prefs) {
        if (XiaomiPreferences.PREF_AGPS_UPDATE.equals(config)) {
            if (getCoordinator().supportsAgpsUpdates(getSupport().getDevice())) {
                triggerFetch(true);
            }
            return true;
        }
        return false;
    }

    /**
     * Fetch a fresh AGPS bundle from the public source (off the connection thread) and push it.
     * @param userInitiated when true, surface errors to the user; auto-refresh stays silent.
     */
    private void triggerFetch(final boolean userInitiated) {
        final Context context = getSupport().getContext();
        new Thread(() -> XiaomiAgpsFetcher.fetch(context, new XiaomiAgpsFetcher.Callback() {
            @Override
            public void onFetched(final byte[] bytes) {
                LOG.info("Fetched AGPS bundle ({} bytes), pushing to device", bytes.length);
                installAgps(bytes);
            }

            @Override
            public void onError(final String message) {
                LOG.warn("AGPS update failed: {}", message);
                if (userInitiated) {
                    GB.toast(context, message, Toast.LENGTH_LONG, GB.WARN);
                }
            }
        }), "xiaomi-agps-fetch").start();
    }

    /**
     * Push a GNSS assistance (AGPS) bundle to the watch: the phone's approximate position
     * (type 16, subtype 2) first, then the bundle over the standard chunked DataUpload transport as
     * {@link XiaomiDataUploadService#TYPE_AGPS}. Gated upstream by {@code supportsAgpsUpdates}.
     */
    public void installAgps(final byte[] bytes) {
        LOG.info("Starting AGPS assist upload, {} bytes", bytes.length);
        sendPosition();
        getSupport().getDataUploadService().uploadAgps(bytes, this);
    }

    @Override
    public void onUploadFinish(final boolean success) {
        LOG.info("AGPS upload finished: {}", success);
        getSupport().getDataUploadService().setCallback(null);
        if (success) {
            getSupport().evaluateGBDeviceEvent(new GBDeviceEventUpdatePreferences()
                    .withPreference(XiaomiPreferences.PREF_AGPS_UPDATE_TIME, System.currentTimeMillis()));
        }
    }

    @Override
    public void onUploadProgress(final int progress) {
        LOG.debug("AGPS upload progress: {}%", progress);
    }
}
