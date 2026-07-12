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
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi;

import android.content.Context;
import android.net.Uri;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Locale;

import kotlin.Unit;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.util.CheckSums;
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.InternetUtils;

/**
 * Fetches a GNSS assistance bundle for bands whose onboard GPS accepts a raw u-blox UBX-MGA-ANO
 * stream (records start {@code B5 62 13 20}; see {@link XiaomiAgpsFile}).
 *
 * The source is the public Broadcom LTO server, which needs no account and serves a 7-day
 * multi-GNSS predictive-orbit file alongside a sibling {@code .md5}. The host is HTTP-only, so
 * integrity rests on two checks rather than on the transport: the download must match the published
 * md5, and every UBX record and checksum must validate ({@link XiaomiAgpsFile}). The file rotates
 * every few minutes, so a md5 mismatch is retried once before giving up.
 *
 * Networking goes through {@link InternetUtils}, which uses Gadgetbridge's Internet Helper when the
 * app holds no INTERNET permission itself. Blocking: call off the main and connection threads.
 */
public final class XiaomiAgpsFetcher {
    private static final Logger LOG = LoggerFactory.getLogger(XiaomiAgpsFetcher.class);

    // 7-day multi-GNSS (GPS/GAL/GLO/BDS/QZSS) predictive orbits, as a UBX-MGA-ANO stream.
    private static final String LTO_URL = "http://gnssdata.net/breamv5/lto7dv5.brm";
    private static final String MD5_URL = LTO_URL + ".md5";

    private static final int MAX_SIZE = 4 * 1024 * 1024;
    private static final int MAX_ATTEMPTS = 2;

    public interface Callback {
        void onFetched(byte[] bytes);

        void onError(String message);
    }

    private XiaomiAgpsFetcher() {
    }

    public static void fetch(final Context context, final Callback callback) {
        if (!GBApplication.hasInternetAccess()) {
            callback.onError("No internet access (install the Gadgetbridge Internet Helper)");
            return;
        }

        String lastError = "AGPS download failed";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            final byte[] bytes = download(context, LTO_URL, "xiaomi_agps.ubx", MAX_SIZE);
            if (bytes == null) {
                lastError = "AGPS download failed (server unreachable?)";
                continue;
            }

            // The published md5 is optional: when the sibling cannot be fetched, the UBX record
            // validation below is the only integrity check.
            final String expectedMd5 = fetchMd5(context);
            final String actualMd5 = md5Hex(bytes);
            if (expectedMd5 != null && actualMd5 != null && !expectedMd5.equalsIgnoreCase(actualMd5)) {
                LOG.warn("AGPS md5 mismatch (attempt {}/{}), file may have rotated - retrying",
                        attempt, MAX_ATTEMPTS);
                lastError = "AGPS integrity check failed (md5 mismatch)";
                continue;
            }

            if (!new XiaomiAgpsFile(bytes).isValid()) {
                lastError = "Downloaded data is not a valid AGPS bundle";
                continue;
            }

            LOG.info("Fetched AGPS bundle, {} bytes (md5 {})", bytes.length,
                    expectedMd5 != null ? "verified" : "unavailable");
            callback.onFetched(bytes);
            return;
        }

        callback.onError(lastError);
    }

    /** Download a URL to a cache file and return its bytes, or null on any failure. */
    private static byte[] download(final Context context, final String url,
                                   final String cacheName, final int maxSize) {
        final File target = new File(context.getCacheDir(), cacheName);
        // Clear any file left behind by an interrupted run, so a failed download cannot be read
        // back as if it had succeeded.
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        try {
            InternetUtils.Companion.downloadBinaryFile(
                    Uri.parse(url),
                    target,
                    reason -> {
                        LOG.warn("Download of {} failed: {}", url, reason);
                        return Unit.INSTANCE;
                    },
                    f -> Unit.INSTANCE
            );
            if (!target.exists() || target.length() == 0) {
                return null;
            }
            try (InputStream in = new BufferedInputStream(new FileInputStream(target))) {
                return FileUtils.readAll(in, maxSize);
            }
        } catch (final Exception e) {
            LOG.error("Download failed: {}", url, e);
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }
    }

    /** Fetch the sibling .md5 and return the 32-char hex digest, or null if unavailable/malformed. */
    private static String fetchMd5(final Context context) {
        final byte[] raw = download(context, MD5_URL, "xiaomi_agps.md5", 4096);
        if (raw == null) {
            return null;
        }
        // Format: "<md5hex>  <path>" (md5sum style); the digest is the first whitespace-delimited token.
        final String token = new String(raw).trim().split("\\s+", 2)[0];
        if (!token.matches("[0-9a-fA-F]{32}")) {
            LOG.warn("Unexpected md5 file contents");
            return null;
        }
        return token.toLowerCase(Locale.ROOT);
    }

    /** Lowercase hex md5 of {@code bytes}, or null if the digest is unavailable. */
    private static String md5Hex(final byte[] bytes) {
        final byte[] digest = CheckSums.md5(bytes);
        return digest != null ? GB.hexdump(digest).toLowerCase(Locale.ROOT) : null;
    }
}
