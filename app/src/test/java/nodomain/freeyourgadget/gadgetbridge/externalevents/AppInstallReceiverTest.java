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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.IntentCompat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.robolectric.Robolectric;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.install.FwAppInstallerActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.install.InstallActivity;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceManager;
import nodomain.freeyourgadget.gadgetbridge.devices.InstallHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.test.TestDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceCommunicationService;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

public class AppInstallReceiverTest extends TestBase {
    private static final String TEST_CONTENT_AUTHORITY = "app-install-test";

    private final AppInstallReceiver receiver = new AppInstallReceiver();
    private final List<File> filesToDelete = new ArrayList<>();

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        Robolectric.setupContentProvider(TestContentProvider.class, TEST_CONTENT_AUTHORITY);
        registerDevices();
        drainStartedServices();
    }

    @After
    @Override
    public void tearDown() throws Exception {
        for (final File file : filesToDelete) {
            if (file.exists() && !file.delete()) {
                throw new AssertionError("Failed to delete test file " + file);
            }
        }
        super.tearDown();
    }

    @Test
    public void installsOnExplicitEnabledDevice() throws Exception {
        final TestInstallHandler handler = new TestInstallHandler(true, true);
        final TestInstallDevice device = createDevice(1, handler);
        enableIntentApi(device);
        registerDevices(device);

        final Bundle options = new Bundle();
        options.putString("test_option", "test_value");
        options.putBundle("unsupported_option", new Bundle());
        final Uri sourceUri = createSourceUri();
        final Intent intent = createInstallIntent(sourceUri)
                .putExtra(AppInstallReceiver.EXTRA_DEVICE, device.getAddress())
                .putExtra(DeviceService.EXTRA_OPTIONS, options);

        receiver.handleIntent(getContext(), intent);

        assertTrue(handler.started);
        assertTrue(handler.validationCalled);
        final Intent forwardedIntent = getNextStartedService();
        assertInstallIntent(forwardedIntent, device, sourceUri);
        final Bundle forwardedOptions = forwardedIntent.getBundleExtra(DeviceService.EXTRA_OPTIONS);
        assertNotNull(forwardedOptions);
        assertEquals("test_value", forwardedOptions.getString("test_option"));
        assertFalse(forwardedOptions.containsKey("unsupported_option"));
        assertNull(getNextStartedService());
    }

    @Test
    public void installsOnOnlyCompatibleDeviceWhenTargetIsOmitted() throws Exception {
        final TestInstallHandler firmwareHandler = new TestInstallHandler(false, true);
        final TestInstallDevice firmwareDevice = createDevice(2, firmwareHandler);
        final TestInstallHandler appHandler = new TestInstallHandler(true, true);
        final TestInstallDevice appDevice = createDevice(3, appHandler);
        enableIntentApi(firmwareDevice);
        enableIntentApi(appDevice);
        registerDevices(firmwareDevice, appDevice);

        receiver.handleIntent(getContext(), createInstallIntent(createSourceUri()));

        assertFalse(firmwareHandler.started);
        assertTrue(appHandler.started);
        assertInstallIntent(getNextStartedService(), appDevice, null);
        assertNull(getNextStartedService());
    }

    @Test
    public void explicitTargetIsNotAmbiguousWhenAnotherDeviceIsCompatible() throws Exception {
        final TestInstallHandler firstHandler = new TestInstallHandler(true, true);
        final TestInstallDevice firstDevice = createDevice(4, firstHandler);
        final TestInstallHandler secondHandler = new TestInstallHandler(true, true);
        final TestInstallDevice secondDevice = createDevice(5, secondHandler);
        enableIntentApi(firstDevice);
        enableIntentApi(secondDevice);
        registerDevices(firstDevice, secondDevice);

        receiver.handleIntent(getContext(), createInstallIntent(createSourceUri())
                .putExtra(AppInstallReceiver.EXTRA_DEVICE, secondDevice.getAddress()));

        assertFalse(firstHandler.started);
        assertTrue(secondHandler.started);
        assertInstallIntent(getNextStartedService(), secondDevice, null);
        assertNull(getNextStartedService());
    }

    @Test
    public void rejectsAmbiguousRequestWithoutTarget() throws Exception {
        final TestInstallHandler firstHandler = new TestInstallHandler(true, true);
        final TestInstallDevice firstDevice = createDevice(6, firstHandler);
        final TestInstallHandler secondHandler = new TestInstallHandler(true, true);
        final TestInstallDevice secondDevice = createDevice(7, secondHandler);
        enableIntentApi(firstDevice);
        enableIntentApi(secondDevice);
        registerDevices(firstDevice, secondDevice);

        receiver.handleIntent(getContext(), createInstallIntent(createSourceUri()));

        assertFalse(firstHandler.started);
        assertFalse(secondHandler.started);
        assertNull(getNextStartedService());
    }

    @Test
    public void rejectsFirmware() throws Exception {
        final TestInstallHandler handler = new TestInstallHandler(false, true);
        final TestInstallDevice device = createDevice(8, handler);
        enableIntentApi(device);
        registerDevices(device);

        receiver.handleIntent(getContext(), createInstallIntent(createSourceUri())
                .putExtra(AppInstallReceiver.EXTRA_DEVICE, device.getAddress()));

        assertFalse(handler.validationCalled);
        assertFalse(handler.started);
        assertNull(getNextStartedService());
    }

    @Test
    public void rejectsFailedInstallationValidation() throws Exception {
        final TestInstallHandler handler = new TestInstallHandler(true, false);
        final TestInstallDevice device = createDevice(9, handler);
        enableIntentApi(device);
        registerDevices(device);

        receiver.handleIntent(getContext(), createInstallIntent(createSourceUri())
                .putExtra(AppInstallReceiver.EXTRA_DEVICE, device.getAddress()));

        assertTrue(handler.validationCalled);
        assertFalse(handler.started);
        assertNull(getNextStartedService());
    }

    @Test
    public void rejectsDeviceWithoutPerDevicePermission() throws Exception {
        final TestInstallHandler handler = new TestInstallHandler(true, true);
        final TestInstallDevice device = createDevice(10, handler);
        registerDevices(device);

        receiver.handleIntent(getContext(), new Intent(AppInstallReceiver.COMMAND_INSTALL_APP)
                .putExtra(Intent.EXTRA_STREAM, new Bundle())
                .putExtra(AppInstallReceiver.EXTRA_DEVICE, device.getAddress()));

        assertEquals(0, device.coordinator.findHandlerCalls);
        assertFalse(handler.started);
        assertNull(getNextStartedService());
    }

    @Test
    public void actionOnlyFilterMatchesCanonicalExtraStreamIntent() {
        final IntentFilter filter = receiver.buildFilter();

        assertTrue(filter.match(
                AppInstallReceiver.COMMAND_INSTALL_APP,
                null,
                null,
                null,
                null,
                "AppInstallReceiverTest"
        ) >= 0);
        assertEquals(IntentFilter.NO_MATCH_DATA, filter.match(
                AppInstallReceiver.COMMAND_INSTALL_APP,
                null,
                "content",
                Uri.parse("content://app-install-test/application.bin"),
                null,
                "AppInstallReceiverTest"
        ));
    }

    @Test
    public void rejectsIntentDataEvenWhenExtraStreamIsPresent() throws Exception {
        final TestInstallHandler handler = new TestInstallHandler(true, true);
        final TestInstallDevice device = createDevice(11, handler);
        enableIntentApi(device);
        registerDevices(device);

        final Uri sourceUri = createSourceUri();
        receiver.handleIntent(getContext(), createInstallIntent(sourceUri)
                .setData(sourceUri)
                .putExtra(AppInstallReceiver.EXTRA_DEVICE, device.getAddress()));

        assertFalse(handler.started);
        assertNull(getNextStartedService());
    }

    @Test
    public void rejectsMissingUriGrant() throws Exception {
        final TestInstallHandler handler = new TestInstallHandler(true, true);
        final TestInstallDevice device = createDevice(12, handler);
        enableIntentApi(device);
        registerDevices(device);

        receiver.handleIntent(getContext(), createInstallIntent(createSourceUri())
                .setFlags(0)
                .putExtra(AppInstallReceiver.EXTRA_DEVICE, device.getAddress()));

        assertFalse(handler.started);
        assertNull(getNextStartedService());
    }

    @Test
    public void rejectsWrongParcelableTypeWithoutThrowing() {
        final TestInstallHandler handler = new TestInstallHandler(true, true);
        final TestInstallDevice device = createDevice(13, handler);
        enableIntentApi(device);
        registerDevices(device);

        receiver.handleIntent(getContext(), new Intent(AppInstallReceiver.COMMAND_INSTALL_APP)
                .putExtra(Intent.EXTRA_STREAM, new Bundle())
                .putExtra(AppInstallReceiver.EXTRA_DEVICE, device.getAddress()));

        assertFalse(handler.started);
        assertNull(getNextStartedService());
    }

    @Test
    public void rejectsFileLargerThanLimit() {
        final TestInstallHandler handler = new TestInstallHandler(true, true);
        final TestInstallDevice device = createDevice(14, handler);
        enableIntentApi(device);
        registerDevices(device);

        final Uri sourceUri = createContentUri(
                new byte[]{1},
                AppInstallReceiver.MAX_APP_FILE_SIZE_BYTES + 1,
                "too-large.bin"
        );
        receiver.handleIntent(getContext(), createInstallIntent(sourceUri)
                .putExtra(AppInstallReceiver.EXTRA_DEVICE, device.getAddress()));

        assertEquals(0, device.coordinator.findHandlerCalls);
        assertFalse(handler.started);
        assertNull(getNextStartedService());
    }

    @Test
    public void removesExpiredCacheEntries() throws Exception {
        final File cacheDir = new File(getContext().getCacheDir(), AppInstallReceiver.CACHE_DIRECTORY);
        assertTrue(cacheDir.isDirectory() || cacheDir.mkdirs());
        final File staleDir = new File(cacheDir, "stale-test-entry");
        assertTrue(staleDir.mkdir());
        final File staleFile = new File(staleDir, "old.bin");
        Files.writeString(staleFile.toPath(), "old app");
        filesToDelete.add(staleFile);
        filesToDelete.add(staleDir);
        assertTrue(staleDir.setLastModified(
                System.currentTimeMillis() - AppInstallReceiver.CACHE_MAX_AGE_MILLIS - 1000L
        ));

        AppInstallReceiver.cleanupCache(getContext());

        assertFalse(staleDir.exists());
    }

    private TestInstallDevice createDevice(final int suffix, final TestInstallHandler handler) {
        final String address = String.format("AA:BB:CC:DD:EE:%02X", suffix);
        final TestInstallDevice device = new TestInstallDevice(address, handler);
        device.setState(GBDevice.State.INITIALIZED);
        GBApplication.getDeviceSpecificSharedPrefs(address).edit().clear().commit();
        return device;
    }

    private void enableIntentApi(final GBDevice device) {
        GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()).edit()
                .putBoolean(AppInstallReceiver.PREF_ALLOW_APP_INSTALL, true)
                .commit();
    }

    private Intent createInstallIntent(@NonNull final Uri uri) {
        final Intent intent = new Intent(AppInstallReceiver.COMMAND_INSTALL_APP)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newUri(getContext().getContentResolver(), "device app", uri));
        return intent;
    }

    private Uri createSourceUri() {
        final byte[] contents = "test app".getBytes(StandardCharsets.UTF_8);
        return createContentUri(contents, contents.length, "application.bin");
    }

    private Uri createContentUri(@NonNull final byte[] contents,
                                 final long declaredSize,
                                 @NonNull final String fileName) {
        final Uri uri = new Uri.Builder()
                .scheme("content")
                .authority(TEST_CONTENT_AUTHORITY)
                .appendPath(UUID.randomUUID().toString())
                .appendQueryParameter("name", fileName)
                .appendQueryParameter("size", Long.toString(declaredSize))
                .build();

        final ShadowContentResolver contentResolver = shadowOf(getContext().getContentResolver());
        contentResolver.registerInputStreamSupplier(uri, () -> new ByteArrayInputStream(contents) {
            @Override
            public int available() {
                return 0;
            }
        });
        return uri;
    }

    @SuppressWarnings("unchecked")
    private void registerDevices(final GBDevice... devices) {
        try {
            final DeviceManager deviceManager = GBApplication.app().getDeviceManager();
            final Field field = DeviceManager.class.getDeclaredField("deviceList");
            field.setAccessible(true);
            final List<GBDevice> deviceList = (List<GBDevice>) field.get(deviceManager);
            deviceList.clear();
            deviceList.addAll(Arrays.asList(devices));
        } catch (final Exception e) {
            throw new AssertionError("Failed to register test devices", e);
        }
    }

    private void assertInstallIntent(@Nullable final Intent intent,
                                     @NonNull final GBDevice expectedDevice,
                                     @Nullable final Uri sourceUri) {
        assertNotNull(intent);
        assertNotNull(intent.getComponent());
        assertEquals(DeviceCommunicationService.class.getName(), intent.getComponent().getClassName());
        assertEquals(DeviceService.ACTION_INSTALL, intent.getAction());

        final GBDevice forwardedDevice = IntentCompat.getParcelableExtra(
                intent,
                GBDevice.EXTRA_DEVICE,
                GBDevice.class
        );
        assertNotNull(forwardedDevice);
        assertEquals(expectedDevice.getAddress(), forwardedDevice.getAddress());

        final Uri forwardedUri = IntentCompat.getParcelableExtra(intent, DeviceService.EXTRA_URI, Uri.class);
        assertNotNull(forwardedUri);
        assertEquals("file", forwardedUri.getScheme());
        if (sourceUri != null) {
            assertNotEquals(sourceUri, forwardedUri);
        }
        final File forwardedFile = new File(forwardedUri.getPath());
        assertTrue(forwardedFile.exists());
        try {
            assertEquals("test app", Files.readString(forwardedFile.toPath()));
        } catch (final Exception e) {
            throw new AssertionError("Failed to read cached install file", e);
        }
        filesToDelete.add(forwardedFile);
        final File requestCacheDir = forwardedFile.getParentFile();
        assertNotNull(requestCacheDir);
        filesToDelete.add(requestCacheDir);
    }

    private void drainStartedServices() {
        while (getNextStartedService() != null) {
            // Drain app startup noise so each test can assert only its own service invocation.
        }
    }

    @Nullable
    private Intent getNextStartedService() {
        return shadowOf((Application) app).getNextStartedService();
    }

    private static final class TestInstallDevice extends GBDevice {
        private final TestInstallCoordinator coordinator;

        private TestInstallDevice(final String address, final TestInstallHandler handler) {
            super(address, "Testie", "Test Alias", "Test Folder", DeviceType.TEST);
            coordinator = new TestInstallCoordinator(handler);
        }

        @Override
        public TestInstallCoordinator getDeviceCoordinator() {
            return coordinator;
        }
    }

    private static final class TestInstallCoordinator extends TestDeviceCoordinator {
        private final TestInstallHandler handler;
        private int findHandlerCalls;

        private TestInstallCoordinator(final TestInstallHandler handler) {
            this.handler = handler;
        }

        @Override
        public boolean supportsAppInstallation(@NonNull final GBDevice device) {
            return true;
        }

        @Nullable
        @Override
        public InstallHandler findInstallHandler(final Uri uri, final Bundle options, final Context context) {
            findHandlerCalls++;
            return handler;
        }
    }

    private static final class TestInstallHandler implements InstallHandler {
        private final boolean app;
        private final boolean validationEnabled;
        private boolean validationCalled;
        private boolean started;

        private TestInstallHandler(final boolean app, final boolean validationEnabled) {
            this.app = app;
            this.validationEnabled = validationEnabled;
        }

        @NonNull
        @Override
        public Class<? extends Activity> getInstallActivity() {
            return FwAppInstallerActivity.class;
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public boolean isApp(@NonNull final GBDevice device) {
            return app;
        }

        @Override
        public void validateInstallation(@NonNull final InstallActivity installActivity,
                                         @NonNull final GBDevice device) {
            validationCalled = true;
            installActivity.setInfoText(validationEnabled ? "Ready" : "Rejected");
            installActivity.setInstallEnabled(validationEnabled);
        }

        @Override
        public void onStartInstall(@NonNull final GBDevice device) {
            started = true;
        }
    }

    public static final class TestContentProvider extends ContentProvider {
        @Override
        public boolean onCreate() {
            return true;
        }

        @Nullable
        @Override
        public Cursor query(@NonNull final Uri uri,
                            @Nullable final String[] projection,
                            @Nullable final String selection,
                            @Nullable final String[] selectionArgs,
                            @Nullable final String sortOrder) {
            final MatrixCursor cursor = new MatrixCursor(new String[]{
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE
            });
            cursor.addRow(new Object[]{
                    uri.getQueryParameter("name"),
                    Long.parseLong(uri.getQueryParameter("size"))
            });
            return cursor;
        }

        @Nullable
        @Override
        public String getType(@NonNull final Uri uri) {
            return "application/octet-stream";
        }

        @Nullable
        @Override
        public Uri insert(@NonNull final Uri uri, @Nullable final ContentValues values) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int delete(@NonNull final Uri uri,
                          @Nullable final String selection,
                          @Nullable final String[] selectionArgs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int update(@NonNull final Uri uri,
                          @Nullable final ContentValues values,
                          @Nullable final String selection,
                          @Nullable final String[] selectionArgs) {
            throw new UnsupportedOperationException();
        }
    }
}
