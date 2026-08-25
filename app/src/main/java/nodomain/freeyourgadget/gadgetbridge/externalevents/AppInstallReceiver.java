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
package nodomain.freeyourgadget.gadgetbridge.externalevents;

import static nodomain.freeyourgadget.gadgetbridge.model.DeviceService.EXTRA_OPTIONS;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.IntentCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.install.InstallValidation;
import nodomain.freeyourgadget.gadgetbridge.devices.InstallHandler;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.BundleUtils;
import nodomain.freeyourgadget.gadgetbridge.util.UriHelper;

/**
 * Installs device applications received through the Intent API.
 *
 * <p>The caller must put a readable {@code content://} URI in {@link Intent#EXTRA_STREAM}, include
 * the same URI in {@link ClipData}, and add {@link Intent#FLAG_GRANT_READ_URI_PERMISSION}. Intent
 * data and MIME type must remain unset so that the command matches the dynamically registered
 * action-only receiver. The caller should restrict the broadcast to the installed Gadgetbridge
 * package, which can differ between release and nightly variants.</p>
 *
 * <pre>{@code
 * final Intent intent = new Intent(AppInstallReceiver.COMMAND_INSTALL_APP)
 *         .setPackage("nodomain.freeyourgadget.gadgetbridge")
 *         .putExtra(Intent.EXTRA_STREAM, appUri)
 *         .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
 * intent.setClipData(ClipData.newUri(getContentResolver(), "device app", appUri));
 * intent.putExtra(AppInstallReceiver.EXTRA_DEVICE, deviceAddress); // optional
 * sendBroadcast(intent);
 * }</pre>
 *
 * <p>Without {@link #EXTRA_DEVICE}, installation proceeds only when exactly one enabled, ready and
 * compatible device is found. The {@code options} extra is optional and accepts only primitive
 * values, primitive arrays, strings, and string arrays. The command is fire-and-forget and is
 * available while Gadgetbridge's device communication service is running. Files larger than
 * 64 MiB are rejected, and a command received while another request is being processed is ignored.</p>
 */
public class AppInstallReceiver extends BroadcastReceiver {
    private static final Logger LOG = LoggerFactory.getLogger(AppInstallReceiver.class);

    public static final String COMMAND_INSTALL_APP = "nodomain.freeyourgadget.gadgetbridge.command.INSTALL_APP";
    public static final String EXTRA_DEVICE = IntentApiReceiver.EXTRA_DEVICE;
    public static final String PREF_ALLOW_APP_INSTALL = "third_party_apps_install_apps";

    static final String CACHE_DIRECTORY = "intent-api-app-install";
    static final long CACHE_MAX_AGE_MILLIS = TimeUnit.DAYS.toMillis(1);
    static final long MAX_APP_FILE_SIZE_BYTES = 64L * 1024L * 1024L;
    static final long MAX_CACHE_SIZE_BYTES = 128L * 1024L * 1024L;

    private static final AtomicBoolean REQUEST_IN_PROGRESS = new AtomicBoolean();
    private static final AtomicBoolean CACHE_CLEANUP_PENDING = new AtomicBoolean();
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1)
    );

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (!COMMAND_INSTALL_APP.equals(intent.getAction())) {
            LOG.warn("Unexpected action {}", intent.getAction());
            return;
        }

        final Intent receivedIntent;
        try {
            receivedIntent = new Intent(intent);
        } catch (final RuntimeException e) {
            LOG.error("Failed to copy headless app installation intent", e);
            return;
        }

        if (!REQUEST_IN_PROGRESS.compareAndSet(false, true)) {
            LOG.warn("Another headless app installation request is already being processed");
            return;
        }

        final PendingResult pendingResult;
        try {
            pendingResult = goAsync();
        } catch (final RuntimeException e) {
            REQUEST_IN_PROGRESS.set(false);
            LOG.error("Failed to start asynchronous app installation handling", e);
            return;
        }
        try {
            EXECUTOR.execute(() -> {
                try {
                    handleIntent(context.getApplicationContext(), receivedIntent);
                } finally {
                    REQUEST_IN_PROGRESS.set(false);
                    pendingResult.finish();
                }
            });
        } catch (final RejectedExecutionException e) {
            REQUEST_IN_PROGRESS.set(false);
            pendingResult.finish();
            LOG.warn("Headless app installation queue is full", e);
        }
    }

    void handleIntent(@NonNull final Context context, @NonNull final Intent intent) {
        try {
            handleIntentInternal(context, intent);
        } catch (final RuntimeException e) {
            LOG.error("Failed to process headless app installation intent", e);
        }
    }

    private void handleIntentInternal(@NonNull final Context context, @NonNull final Intent intent) {
        final List<GBDevice> targetDevices = getTargetDevices(intent);
        if (targetDevices.isEmpty()) {
            LOG.warn("No target device found for app installation");
            return;
        }

        final List<GBDevice> eligibleDevices = new ArrayList<>();
        for (final GBDevice device : targetDevices) {
            if (isEligibleTarget(device)) {
                eligibleDevices.add(device);
            }
        }
        if (eligibleDevices.isEmpty()) {
            LOG.warn("No device is enabled and ready for headless app installation");
            return;
        }

        final Uri uri = getUri(intent);
        if (uri == null) {
            return;
        }

        final Bundle options = getOptions(intent);
        final Uri cachedUri;
        try {
            cachedUri = copyToCache(context, uri);
        } catch (final Exception e) {
            LOG.error("Failed to cache app from {}", uri, e);
            return;
        }

        final List<InstallCandidate> candidates = new ArrayList<>();
        for (final GBDevice device : eligibleDevices) {
            final InstallCandidate candidate = findCandidate(context, cachedUri, options, device);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }

        if (candidates.size() != 1) {
            if (candidates.isEmpty()) {
                LOG.warn("No compatible device found for headless app installation");
            } else {
                LOG.warn("Found {} compatible devices for headless app installation; refusing ambiguous request", candidates.size());
            }
            deleteCachedFile(cachedUri);
            return;
        }

        final InstallCandidate candidate = candidates.get(0);
        LOG.info("Installing app on {} through Intent API", candidate.device().getAliasOrName());
        try {
            candidate.handler().onStartInstall(candidate.device());
            GBApplication.deviceService(candidate.device()).onInstallApp(cachedUri, options);
        } catch (final Exception e) {
            LOG.error("Failed to start app installation on {}", candidate.device(), e);
            deleteCachedFile(cachedUri);
        }
    }

    @Nullable
    private InstallCandidate findCandidate(@NonNull final Context context,
                                           @NonNull final Uri uri,
                                           @NonNull final Bundle options,
                                           @NonNull final GBDevice device) {
        final InstallHandler handler;
        try {
            handler = device.getDeviceCoordinator().findInstallHandler(uri, options, context);
        } catch (final Exception e) {
            LOG.error("Failed to find install handler for {} and {}", uri, device, e);
            return null;
        }

        try {
            if (handler == null || !handler.isValid() || !handler.isApp(device)) {
                return null;
            }

            final InstallValidation validation = new InstallValidation();
            handler.validateInstallation(validation, device);
            if (!validation.isInstallEnabled()) {
                LOG.warn("App installation validation failed for {}: {}", device.getAliasOrName(), validation.getInfoText());
                return null;
            }
        } catch (final Exception e) {
            LOG.error("Failed to validate app installation for {}", device, e);
            return null;
        }

        return new InstallCandidate(device, handler);
    }

    private boolean isEligibleTarget(@NonNull final GBDevice device) {
        if (!GBApplication.getDeviceSpecificSharedPrefs(device.getAddress())
                .getBoolean(PREF_ALLOW_APP_INSTALL, false)) {
            LOG.debug("Headless app installation is disabled for {}", device.getAliasOrName());
            return false;
        }

        if (!device.getDeviceCoordinator().supportsAppInstallation(device)) {
            LOG.debug("App installation is not supported for {}", device.getAliasOrName());
            return false;
        }

        if (!device.isInitialized() || device.isBusy()) {
            LOG.warn("Device {} is not ready for headless app installation", device.getAliasOrName());
            return false;
        }

        return true;
    }

    @NonNull
    private List<GBDevice> getTargetDevices(@NonNull final Intent intent) {
        final String address = intent.getStringExtra(EXTRA_DEVICE);
        if (address == null || address.isEmpty()) {
            return GBApplication.app().getDeviceManager().getDevices();
        }

        final GBDevice device = GBApplication.app().getDeviceManager().getDeviceByAddress(address);
        if (device == null) {
            return List.of();
        }
        return List.of(device);
    }

    @Nullable
    private static Uri getUri(@NonNull final Intent intent) {
        if (intent.getData() != null || intent.getType() != null) {
            LOG.warn("Intent data and MIME type are not supported; use EXTRA_STREAM");
            return null;
        }

        final Uri uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri.class);
        if (uri == null) {
            LOG.warn("No URI provided in EXTRA_STREAM for app installation");
            return null;
        }
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            LOG.warn("Unsupported app URI scheme {}; expected content", uri.getScheme());
            return null;
        }
        if (!hasReadGrant(intent, uri)) {
            LOG.warn("App URI must be included in ClipData with read permission granted");
            return null;
        }
        return uri;
    }

    private static boolean hasReadGrant(@NonNull final Intent intent, @NonNull final Uri uri) {
        if ((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            return false;
        }

        final ClipData clipData = intent.getClipData();
        if (clipData == null) {
            return false;
        }
        for (int i = 0; i < clipData.getItemCount(); i++) {
            if (uri.equals(clipData.getItemAt(i).getUri())) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private static Bundle getOptions(@NonNull final Intent intent) {
        final Bundle options = IntentCompat.getParcelableExtra(intent, EXTRA_OPTIONS, Bundle.class);
        if (options == null || options.isEmpty()) {
            return Bundle.EMPTY;
        }

        final Bundle safeOptions = new Bundle();
        for (final String key : options.keySet()) {
            if (key == null) {
                LOG.warn("Ignoring app installation option with null key");
                continue;
            }
            //noinspection deprecation
            final Object value = options.get(key);
            if (isSafeOptionValue(value)) {
                BundleUtils.addToBundle(safeOptions, key, value);
            } else {
                LOG.warn("Ignoring unsupported app installation option {}", key);
            }
        }
        return safeOptions;
    }

    private static boolean isSafeOptionValue(@Nullable final Object value) {
        return value == null
                || value instanceof Boolean || value instanceof boolean[]
                || value instanceof Byte || value instanceof byte[]
                || value instanceof Short || value instanceof short[]
                || value instanceof Character || value instanceof char[]
                || value instanceof Integer || value instanceof int[]
                || value instanceof Long || value instanceof long[]
                || value instanceof Float || value instanceof float[]
                || value instanceof Double || value instanceof double[]
                || value instanceof String || value instanceof String[];
    }

    @NonNull
    private static Uri copyToCache(@NonNull final Context context, @NonNull final Uri uri) throws IOException {
        final UriHelper uriHelper = UriHelper.get(uri, context);
        if (uriHelper.getFileSize() > MAX_APP_FILE_SIZE_BYTES) {
            throw new IOException("App file is too large: " + uriHelper.getFileSize());
        }

        final String sourceFileName = new File(uriHelper.getFileName()).getName();
        final String fileName = sourceFileName.isEmpty()
                || ".".equals(sourceFileName)
                || "..".equals(sourceFileName) ? "app" : sourceFileName;
        final File cacheDir = new File(context.getCacheDir(), CACHE_DIRECTORY);
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Failed to create cache directory " + cacheDir);
        }
        deleteExpiredCacheFiles(cacheDir);

        final long currentCacheSize = getCacheSize(cacheDir);
        if (currentCacheSize > MAX_CACHE_SIZE_BYTES - uriHelper.getFileSize()) {
            throw new IOException("App installation cache limit exceeded");
        }

        final File requestCacheDir = new File(cacheDir, UUID.randomUUID().toString());
        if (!requestCacheDir.mkdir()) {
            throw new IOException("Failed to create request cache directory " + requestCacheDir);
        }

        final File cachedFile = new File(requestCacheDir, fileName);
        try {
            copyUriToFile(context, uri, cachedFile, currentCacheSize);
        } catch (final IOException | RuntimeException e) {
            if (cachedFile.exists() && !cachedFile.delete()) {
                LOG.warn("Failed to delete incomplete cached install file {}", cachedFile);
            }
            if (!requestCacheDir.delete()) {
                LOG.warn("Failed to delete incomplete install cache directory {}", requestCacheDir);
            }
            throw e;
        }
        return Uri.fromFile(cachedFile);
    }

    private static void copyUriToFile(@NonNull final Context context,
                                      @NonNull final Uri uri,
                                      @NonNull final File destination,
                                      final long currentCacheSize) throws IOException {
        final InputStream inputStream = context.getContentResolver().openInputStream(uri);
        if (inputStream == null) {
            throw new IOException("Unable to open input stream for " + uri);
        }

        try (InputStream input = new BufferedInputStream(inputStream);
             FileOutputStream output = new FileOutputStream(destination)) {
            final byte[] buffer = new byte[8192];
            long copiedBytes = 0L;
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                if (bytesRead == 0) {
                    continue;
                }
                copiedBytes += bytesRead;
                if (copiedBytes > MAX_APP_FILE_SIZE_BYTES) {
                    throw new IOException("App file size limit exceeded while copying");
                }
                if (currentCacheSize > MAX_CACHE_SIZE_BYTES - copiedBytes) {
                    throw new IOException("App installation cache limit exceeded while copying");
                }
                output.write(buffer, 0, bytesRead);
            }
        }
    }

    private static long getCacheSize(@NonNull final File entry) {
        if (entry.isFile()) {
            return entry.length();
        }

        final File[] children = entry.listFiles();
        if (children == null) {
            return 0L;
        }

        long size = 0L;
        for (final File child : children) {
            final long childSize = getCacheSize(child);
            if (Long.MAX_VALUE - size < childSize) {
                return Long.MAX_VALUE;
            }
            size += childSize;
        }
        return size;
    }

    /**
     * Removes cache entries left by completed or interrupted installations without blocking service
     * startup. At most one cleanup can be pending at a time.
     */
    public static void scheduleCacheCleanup(@NonNull final Context context) {
        if (!CACHE_CLEANUP_PENDING.compareAndSet(false, true)) {
            return;
        }

        final Context applicationContext = context.getApplicationContext();
        try {
            EXECUTOR.execute(() -> {
                try {
                    cleanupCache(applicationContext);
                } catch (final RuntimeException e) {
                    LOG.error("Failed to clean headless app installation cache", e);
                } finally {
                    CACHE_CLEANUP_PENDING.set(false);
                }
            });
        } catch (final RejectedExecutionException e) {
            CACHE_CLEANUP_PENDING.set(false);
            LOG.debug("Deferring headless app installation cache cleanup", e);
        }
    }

    static void cleanupCache(@NonNull final Context context) {
        final File cacheDir = new File(context.getCacheDir(), CACHE_DIRECTORY);
        if (cacheDir.isDirectory()) {
            deleteExpiredCacheFiles(cacheDir);
        }
    }

    private static void deleteExpiredCacheFiles(@NonNull final File cacheDir) {
        final File[] cachedFiles = cacheDir.listFiles();
        if (cachedFiles == null) {
            return;
        }

        final long oldestAllowedTimestamp = System.currentTimeMillis() - CACHE_MAX_AGE_MILLIS;
        for (final File cachedEntry : cachedFiles) {
            if (cachedEntry.lastModified() < oldestAllowedTimestamp
                    && !deleteCacheEntry(cachedEntry)) {
                LOG.warn("Failed to delete expired install cache entry {}", cachedEntry);
            }
        }
    }

    /**
     * @noinspection BooleanMethodIsAlwaysInverted
     */
    private static boolean deleteCacheEntry(@NonNull final File entry) {
        if (entry.isDirectory()) {
            final File[] children = entry.listFiles();
            if (children == null) {
                return false;
            }
            for (final File child : children) {
                if (!deleteCacheEntry(child)) {
                    return false;
                }
            }
        }
        return entry.delete();
    }

    private static void deleteCachedFile(@NonNull final Uri uri) {
        if (!"file".equals(uri.getScheme())) {
            return;
        }
        final String path = uri.getPath();
        if (path == null) {
            LOG.warn("Failed to delete cached install file: URI has no path");
            return;
        }
        final File file = new File(path);
        if (!deleteCacheEntry(file)) {
            LOG.warn("Failed to delete cached install file {}", file);
            return;
        }

        final File requestCacheDir = file.getParentFile();
        if (requestCacheDir != null
                && requestCacheDir.getParentFile() != null
                && CACHE_DIRECTORY.equals(requestCacheDir.getParentFile().getName())
                && !requestCacheDir.delete()) {
            LOG.warn("Failed to delete install cache directory {}", requestCacheDir);
        }
    }

    @NonNull
    public IntentFilter buildFilter() {
        return new IntentFilter(COMMAND_INSTALL_APP);
    }

    private record InstallCandidate(GBDevice device, InstallHandler handler) {
    }
}
