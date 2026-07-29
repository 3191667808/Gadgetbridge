/*  Copyright (C) 2026 Liu Haoxin

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
package nodomain.freeyourgadget.gadgetbridge.mcp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import fi.iki.elonen.NanoHTTPD;
import nodomain.freeyourgadget.gadgetbridge.BuildConfig;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes;

/** Owns the MCP HTTP endpoint for the lifetime of DeviceCommunicationService. */
public final class McpServerManager implements AutoCloseable {
    public static final String ACTION_STATUS_CHANGED =
            BuildConfig.APPLICATION_ID + ".mcp.action.STATUS_CHANGED";

    private static final Logger LOG = LoggerFactory.getLogger(McpServerManager.class);
    private static final Gson GSON = new Gson();
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final long REFRESH_TIMEOUT_SECONDS = 120L;
    private static final AtomicReference<Status> STATUS =
            new AtomicReference<>(new Status(false, null, null));

    private final Context context;
    private final LocalBroadcastManager localBroadcastManager;
    private final McpSnapshotRepository repository = new McpSnapshotRepository();
    private final McpProtocol protocol = new McpProtocol(repository::get, this::requestRefresh);
    private final ScheduledExecutorService timeoutExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "Gadgetbridge-MCP-Timeout");
                thread.setDaemon(true);
                return thread;
            });

    private final BroadcastReceiver newDataReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context receiverContext, final Intent intent) {
            if (!GBApplication.ACTION_NEW_DATA.equals(intent.getAction())) {
                return;
            }
            final GBDevice changedDevice = intent.getParcelableExtra(GBDevice.EXTRA_DEVICE);
            final GBDevice selected = selectedDevice;
            if (changedDevice == null || selected == null
                    || !selected.getAddress().equalsIgnoreCase(changedDevice.getAddress())) {
                return;
            }
            cancelRefreshTimeout();
            repository.reloadAfterSync(changedDevice);
        }
    };

    private volatile McpPreferences.Config config = McpPreferences.read();
    private volatile GBDevice selectedDevice;
    private NanoServer server;
    private boolean receiverRegistered;
    private ScheduledFuture<?> refreshTimeout;

    public McpServerManager(@NonNull final Context context) {
        this.context = context.getApplicationContext();
        this.localBroadcastManager = LocalBroadcastManager.getInstance(this.context);
    }

    @NonNull
    public static Status getStatus() {
        return STATUS.get();
    }

    /** Stops the previous listener and applies one complete, validated configuration atomically. */
    public synchronized void reconfigure() {
        stopServer();
        cancelRefreshTimeout();
        config = McpPreferences.read();

        if (!config.isEnabled()) {
            unregisterReceiver();
            selectedDevice = null;
            repository.selectDevice(null);
            updateStatus(false, null, null);
            return;
        }

        if (config.isAllowLan() && config.getAccessToken().isBlank()) {
            updateStatus(false, null, "LAN mode requires an access token");
            return;
        }

        final GBDevice resolvedDevice = resolveDevice(config.getDeviceAddress());
        if (!sameDevice(selectedDevice, resolvedDevice)) {
            selectedDevice = resolvedDevice;
            repository.selectDevice(resolvedDevice);
        }
        registerReceiver();

        try {
            server = new NanoServer(config);
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            updateStatus(true, config.getEndpoint(), null);
            LOG.info("MCP server listening on {}:{}", config.getBindAddress(), config.getPort());
        } catch (final IOException exception) {
            LOG.error("Unable to start MCP server", exception);
            stopServer();
            updateStatus(false, null, exception.getLocalizedMessage());
        }
    }

    @NonNull
    private synchronized JsonObject requestRefresh() {
        final JsonObject result = new JsonObject();
        final GBDevice device = selectedDevice;
        if (device == null) {
            result.addProperty("accepted", false);
            result.addProperty("message", "No Gadgetbridge device is selected");
        } else if (!device.isInitialized()) {
            result.addProperty("accepted", false);
            result.addProperty("message", "The selected device is not connected");
        } else if (repository.isRefreshing()) {
            result.addProperty("accepted", false);
            result.addProperty("message", "Synchronization is already in progress");
        } else if (device.isBusy()) {
            result.addProperty("accepted", false);
            result.addProperty("message", "The selected device is busy: " + device.getBusyTask());
        } else {
            repository.markRefreshing();
            GBApplication.deviceService(device).onFetchRecordedData(RecordedDataTypes.TYPE_SYNC);
            scheduleRefreshTimeout();
            result.addProperty("accepted", true);
            result.addProperty("message", "Synchronization requested");
        }
        result.add("syncStatus", repository.get().getSyncStatus());
        return result;
    }

    @Nullable
    private static GBDevice resolveDevice(@NonNull final String configuredAddress) {
        if (!configuredAddress.isBlank()) {
            final GBDevice configured = GBApplication.app()
                    .getDeviceManager()
                    .getDeviceByAddress(configuredAddress);
            if (configured != null) {
                return configured;
            }
        }

        final List<GBDevice> devices = GBApplication.app().getDeviceManager().getDevices();
        for (final GBDevice device : devices) {
            if (device.getDeviceCoordinator().supportsActivityTracking(device)) {
                return device;
            }
        }
        return devices.isEmpty() ? null : devices.get(0);
    }

    private static boolean sameDevice(
            @Nullable final GBDevice first,
            @Nullable final GBDevice second
    ) {
        if (first == null || second == null) {
            return first == second;
        }
        return first.getAddress().equalsIgnoreCase(second.getAddress());
    }

    private synchronized void scheduleRefreshTimeout() {
        cancelRefreshTimeout();
        refreshTimeout = timeoutExecutor.schedule(
                () -> repository.markRefreshError("Synchronization did not finish within two minutes"),
                REFRESH_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
        );
    }

    private synchronized void cancelRefreshTimeout() {
        if (refreshTimeout != null) {
            refreshTimeout.cancel(false);
            refreshTimeout = null;
        }
    }

    private void registerReceiver() {
        if (receiverRegistered) {
            return;
        }
        localBroadcastManager.registerReceiver(
                newDataReceiver,
                new IntentFilter(GBApplication.ACTION_NEW_DATA)
        );
        receiverRegistered = true;
    }

    private void unregisterReceiver() {
        if (!receiverRegistered) {
            return;
        }
        localBroadcastManager.unregisterReceiver(newDataReceiver);
        receiverRegistered = false;
    }

    private void updateStatus(
            final boolean running,
            @Nullable final String endpoint,
            @Nullable final String message
    ) {
        STATUS.set(new Status(running, endpoint, message));
        repository.setServiceStatus(running, endpoint, message);
        localBroadcastManager.sendBroadcast(new Intent(ACTION_STATUS_CHANGED));
    }

    private synchronized void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    @Override
    public synchronized void close() {
        stopServer();
        cancelRefreshTimeout();
        unregisterReceiver();
        timeoutExecutor.shutdownNow();
        repository.close();
        updateStatus(false, null, null);
    }

    public static final class Status {
        private final boolean running;
        private final String endpoint;
        private final String error;

        private Status(
                final boolean running,
                @Nullable final String endpoint,
                @Nullable final String error
        ) {
            this.running = running;
            this.endpoint = endpoint;
            this.error = error;
        }

        public boolean isRunning() {
            return running;
        }

        @Nullable
        public String getEndpoint() {
            return endpoint;
        }

        @Nullable
        public String getError() {
            return error;
        }
    }

    private final class NanoServer extends NanoHTTPD {
        private final McpPreferences.Config serverConfig;

        private NanoServer(@NonNull final McpPreferences.Config serverConfig) {
            super(serverConfig.getBindAddress(), serverConfig.getPort());
            this.serverConfig = serverConfig;
        }

        @Override
        public Response serve(@NonNull final IHTTPSession session) {
            if (session.getHeaders().containsKey("origin")) {
                return textResponse(Response.Status.FORBIDDEN, "Browser-originated requests are not allowed");
            }
            if (!isAuthorized(session)) {
                final Response response = textResponse(Response.Status.UNAUTHORIZED, "Missing or invalid bearer token");
                response.addHeader("WWW-Authenticate", "Bearer");
                return response;
            }

            if (Method.GET.equals(session.getMethod()) && "/health".equals(session.getUri())) {
                final JsonObject health = new JsonObject();
                health.addProperty("service", "Gadgetbridge MCP");
                health.addProperty("version", BuildConfig.VERSION_NAME);
                health.addProperty("mcpEndpoint", "/mcp");
                health.addProperty("listenScope", serverConfig.isAllowLan() ? "lan" : "loopback");
                health.addProperty("authenticationRequired", serverConfig.isAllowLan());
                return jsonResponse(Response.Status.OK, GSON.toJson(health));
            }

            if (!Method.POST.equals(session.getMethod()) || !"/mcp".equals(session.getUri())) {
                return textResponse(Response.Status.NOT_FOUND, "Not found");
            }

            final long contentLength = parseContentLength(session.getHeaders().get("content-length"));
            if (contentLength > MAX_REQUEST_BYTES) {
                return textResponse(Response.Status.PAYLOAD_TOO_LARGE, "Request body is too large");
            }

            try {
                final Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                final String request = files.get("postData");
                if (request == null || request.isBlank()) {
                    return textResponse(Response.Status.BAD_REQUEST, "Missing JSON-RPC request body");
                }
                if (request.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
                    return textResponse(Response.Status.PAYLOAD_TOO_LARGE, "Request body is too large");
                }
                final String protocolResponse = protocol.handle(request);
                if (protocolResponse == null) {
                    return textResponse(Response.Status.ACCEPTED, "");
                }
                final Response response = jsonResponse(Response.Status.OK, protocolResponse);
                response.addHeader("MCP-Protocol-Version", McpProtocol.SUPPORTED_PROTOCOL_VERSION);
                return response;
            } catch (final IOException | ResponseException exception) {
                LOG.warn("Failed to read MCP request", exception);
                return textResponse(Response.Status.BAD_REQUEST, "Unable to read request body");
            }
        }

        private boolean isAuthorized(@NonNull final IHTTPSession session) {
            if (!serverConfig.isAllowLan()) {
                return true;
            }
            final String authorization = session.getHeaders().get("authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                return false;
            }
            final byte[] supplied = authorization.substring("Bearer ".length())
                    .getBytes(StandardCharsets.UTF_8);
            final byte[] expected = serverConfig.getAccessToken().getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(supplied, expected);
        }

        @NonNull
        private Response jsonResponse(@NonNull final Response.Status status, @NonNull final String body) {
            return newFixedLengthResponse(status, "application/json; charset=utf-8", body);
        }

        @NonNull
        private Response textResponse(@NonNull final Response.Status status, @NonNull final String body) {
            return newFixedLengthResponse(status, "text/plain; charset=utf-8", body);
        }

        private long parseContentLength(@Nullable final String value) {
            if (value == null) {
                return -1L;
            }
            try {
                return Long.parseLong(value);
            } catch (final NumberFormatException ignored) {
                return -1L;
            }
        }
    }
}
