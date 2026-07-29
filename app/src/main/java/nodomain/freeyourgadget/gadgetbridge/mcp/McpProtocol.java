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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import nodomain.freeyourgadget.gadgetbridge.BuildConfig;

/** Pure JSON-RPC/MCP protocol implementation with no Android or transport dependencies. */
public final class McpProtocol {
    public static final String SUPPORTED_PROTOCOL_VERSION = "2025-06-18";

    private static final String JSON_RPC_VERSION = "2.0";
    private static final String JSON_MIME_TYPE = "application/json";
    private static final String TOOL_GET_DATA = "band_get_data";
    private static final String TOOL_REFRESH_NOW = "band_refresh_now";

    private static final String RESOURCE_SNAPSHOT = "miband://snapshot";
    private static final String RESOURCE_STATUS = "miband://status";
    private static final String RESOURCE_DEVICE = "miband://device";
    private static final String RESOURCE_ACTIVITY = "miband://activity/today";
    private static final String RESOURCE_DAILY_METRICS = "miband://daily-metrics/latest";
    private static final String RESOURCE_HEART_RATE = "miband://heart-rate/latest";
    private static final String RESOURCE_BATTERY = "miband://battery/latest";
    private static final String RESOURCE_STRESS = "miband://stress/latest";
    private static final String RESOURCE_SLEEP = "miband://sleep/latest";

    private static final Gson GSON = new Gson();
    private static final Map<String, String> SECTION_RESOURCES = createSectionResources();

    private final Supplier<McpSnapshot> snapshotProvider;
    private final Supplier<JsonObject> refreshHandler;

    public McpProtocol(
            @NonNull final Supplier<McpSnapshot> snapshotProvider,
            @NonNull final Supplier<JsonObject> refreshHandler
    ) {
        this.snapshotProvider = snapshotProvider;
        this.refreshHandler = refreshHandler;
    }

    /**
     * Handles one JSON-RPC message. A null result means the request was a notification and must not
     * receive an HTTP response body.
     */
    @Nullable
    public String handle(@NonNull final String requestBody) {
        final JsonObject request;
        try {
            final JsonElement parsed = JsonParser.parseString(requestBody);
            if (!parsed.isJsonObject()) {
                return GSON.toJson(error(JsonNull.INSTANCE, -32600, "Invalid Request"));
            }
            request = parsed.getAsJsonObject();
        } catch (final JsonParseException exception) {
            return GSON.toJson(error(JsonNull.INSTANCE, -32700, "Parse error"));
        }

        final boolean notification = !request.has("id");
        final JsonElement id = request.has("id") ? request.get("id") : JsonNull.INSTANCE;
        final JsonObject response = dispatch(request, id);
        return notification ? null : GSON.toJson(response);
    }

    @NonNull
    private JsonObject dispatch(@NonNull final JsonObject request, @NonNull final JsonElement id) {
        if (!request.has("jsonrpc")
                || !JSON_RPC_VERSION.equals(stringValue(request.get("jsonrpc")))) {
            return error(id, -32600, "Invalid JSON-RPC version");
        }

        final String method = stringValue(request.get("method"));
        if (method == null) {
            return error(id, -32600, "Missing method");
        }

        return switch (method) {
            case "initialize" -> initialize(id);
            case "ping" -> result(id, new JsonObject());
            case "tools/list" -> toolsList(id);
            case "tools/call" -> callTool(id, objectValue(request.get("params")));
            case "resources/list" -> resourcesList(id);
            case "resources/read" -> readResource(id, objectValue(request.get("params")));
            default -> error(id, -32601, "Method not found: " + method);
        };
    }

    @NonNull
    private JsonObject initialize(@NonNull final JsonElement id) {
        final JsonObject capabilities = new JsonObject();
        final JsonObject tools = new JsonObject();
        tools.addProperty("listChanged", false);
        capabilities.add("tools", tools);

        final JsonObject resources = new JsonObject();
        resources.addProperty("listChanged", false);
        resources.addProperty("subscribe", false);
        capabilities.add("resources", resources);

        final JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "Gadgetbridge MCP");
        serverInfo.addProperty("version", BuildConfig.VERSION_NAME);

        final JsonObject payload = new JsonObject();
        payload.addProperty("protocolVersion", SUPPORTED_PROTOCOL_VERSION);
        payload.add("capabilities", capabilities);
        payload.add("serverInfo", serverInfo);
        return result(id, payload);
    }

    @NonNull
    private JsonObject toolsList(@NonNull final JsonElement id) {
        final JsonArray tools = new JsonArray();
        tools.add(toolDefinition(
                TOOL_GET_DATA,
                "Read the latest cached band data. Returns the full snapshot when section is omitted.",
                true
        ));
        tools.add(toolDefinition(
                TOOL_REFRESH_NOW,
                "Request a fresh synchronization from the selected Gadgetbridge device.",
                false
        ));
        final JsonObject payload = new JsonObject();
        payload.add("tools", tools);
        return result(id, payload);
    }

    @NonNull
    private JsonObject callTool(
            @NonNull final JsonElement id,
            @Nullable final JsonObject params
    ) {
        final String name = params == null ? null : stringValue(params.get("name"));
        if (name == null) {
            return error(id, -32602, "Missing tool name");
        }

        return switch (name) {
            case TOOL_GET_DATA -> getData(id, objectValue(params.get("arguments")));
            case TOOL_REFRESH_NOW -> toolResult(id, refreshHandler.get(), false);
            default -> toolResult(id, "Unknown tool: " + name, true);
        };
    }

    @NonNull
    private JsonObject getData(
            @NonNull final JsonElement id,
            @Nullable final JsonObject arguments
    ) {
        final String requestedSection = arguments == null
                ? "all"
                : stringValue(arguments.get("section"));
        final String section = requestedSection == null ? "all" : requestedSection;
        final String uri = SECTION_RESOURCES.get(section);
        if (uri == null) {
            return toolResult(
                    id,
                    "Unknown section: " + section + ". Expected one of: "
                            + String.join(", ", SECTION_RESOURCES.keySet()),
                    true
            );
        }
        return toolResult(id, resourcePayload(snapshotProvider.get(), uri), false);
    }

    @NonNull
    private JsonObject resourcesList(@NonNull final JsonElement id) {
        final JsonArray resources = new JsonArray();
        resources.add(resource(RESOURCE_SNAPSHOT, "Full Snapshot", "Complete current band snapshot."));
        resources.add(resource(RESOURCE_STATUS, "Status", "MCP service, data source, and synchronization status."));
        resources.add(resource(RESOURCE_DEVICE, "Device", "Selected band identity and firmware details."));
        resources.add(resource(RESOURCE_ACTIVITY, "Today's Activity", "Today's step count."));
        resources.add(resource(RESOURCE_DAILY_METRICS, "Daily Metrics", "Latest available daily health summary."));
        resources.add(resource(RESOURCE_HEART_RATE, "Heart Rate", "Most recent heart-rate sample."));
        resources.add(resource(RESOURCE_BATTERY, "Battery", "Most recent battery level."));
        resources.add(resource(RESOURCE_STRESS, "Stress", "Most recent non-zero stress sample."));
        resources.add(resource(RESOURCE_SLEEP, "Sleep", "Latest sleep summary."));
        final JsonObject payload = new JsonObject();
        payload.add("resources", resources);
        return result(id, payload);
    }

    @NonNull
    private JsonObject readResource(
            @NonNull final JsonElement id,
            @Nullable final JsonObject params
    ) {
        final String uri = params == null ? null : stringValue(params.get("uri"));
        if (uri == null || !SECTION_RESOURCES.containsValue(uri)) {
            return error(id, -32602, uri == null ? "Missing resource URI" : "Unknown resource: " + uri);
        }

        final JsonObject content = new JsonObject();
        content.addProperty("uri", uri);
        content.addProperty("mimeType", JSON_MIME_TYPE);
        content.addProperty("text", GSON.toJson(resourcePayload(snapshotProvider.get(), uri)));
        final JsonArray contents = new JsonArray();
        contents.add(content);
        final JsonObject payload = new JsonObject();
        payload.add("contents", contents);
        return result(id, payload);
    }

    @NonNull
    private static JsonElement resourcePayload(
            @NonNull final McpSnapshot snapshot,
            @NonNull final String uri
    ) {
        return switch (uri) {
            case RESOURCE_SNAPSHOT -> snapshot.toJson();
            case RESOURCE_STATUS -> {
                final JsonObject status = new JsonObject();
                status.add(McpSnapshot.SECTION_SERVICE_STATUS, snapshot.section(McpSnapshot.SECTION_SERVICE_STATUS));
                status.add(McpSnapshot.SECTION_BAND_STATUS, snapshot.section(McpSnapshot.SECTION_BAND_STATUS));
                status.add(McpSnapshot.SECTION_SYNC_STATUS, snapshot.section(McpSnapshot.SECTION_SYNC_STATUS));
                yield status;
            }
            case RESOURCE_DEVICE -> snapshot.section(McpSnapshot.SECTION_DEVICE);
            case RESOURCE_ACTIVITY -> snapshot.section(McpSnapshot.SECTION_ACTIVITY);
            case RESOURCE_DAILY_METRICS -> snapshot.section(McpSnapshot.SECTION_DAILY_METRICS);
            case RESOURCE_HEART_RATE -> snapshot.section(McpSnapshot.SECTION_HEART_RATE);
            case RESOURCE_BATTERY -> snapshot.section(McpSnapshot.SECTION_BATTERY);
            case RESOURCE_STRESS -> snapshot.section(McpSnapshot.SECTION_STRESS);
            case RESOURCE_SLEEP -> snapshot.section(McpSnapshot.SECTION_SLEEP);
            default -> JsonNull.INSTANCE;
        };
    }

    @NonNull
    private static JsonObject toolDefinition(
            @NonNull final String name,
            @NonNull final String description,
            final boolean hasSection
    ) {
        final JsonObject properties = new JsonObject();
        if (hasSection) {
            final JsonObject section = new JsonObject();
            section.addProperty("type", "string");
            section.addProperty("description", "Select all data or one focused section.");
            final JsonArray values = new JsonArray();
            SECTION_RESOURCES.keySet().forEach(values::add);
            section.add("enum", values);
            section.addProperty("default", "all");
            properties.add("section", section);
        }

        final JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.addProperty("additionalProperties", false);

        final JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        tool.add("inputSchema", schema);
        return tool;
    }

    @NonNull
    private static JsonObject resource(
            @NonNull final String uri,
            @NonNull final String name,
            @NonNull final String description
    ) {
        final JsonObject resource = new JsonObject();
        resource.addProperty("uri", uri);
        resource.addProperty("name", name);
        resource.addProperty("description", description);
        resource.addProperty("mimeType", JSON_MIME_TYPE);
        return resource;
    }

    @NonNull
    private static JsonObject toolResult(
            @NonNull final JsonElement id,
            @NonNull final JsonElement payload,
            final boolean isError
    ) {
        return toolResult(id, GSON.toJson(payload), isError);
    }

    @NonNull
    private static JsonObject toolResult(
            @NonNull final JsonElement id,
            @NonNull final String text,
            final boolean isError
    ) {
        final JsonObject textContent = new JsonObject();
        textContent.addProperty("type", "text");
        textContent.addProperty("text", text);
        final JsonArray content = new JsonArray();
        content.add(textContent);
        final JsonObject payload = new JsonObject();
        payload.addProperty("isError", isError);
        payload.add("content", content);
        return result(id, payload);
    }

    @NonNull
    private static JsonObject result(
            @NonNull final JsonElement id,
            @NonNull final JsonObject payload
    ) {
        final JsonObject response = baseResponse(id);
        response.add("result", payload);
        return response;
    }

    @NonNull
    private static JsonObject error(
            @NonNull final JsonElement id,
            final int code,
            @NonNull final String message
    ) {
        final JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        final JsonObject response = baseResponse(id);
        response.add("error", error);
        return response;
    }

    @NonNull
    private static JsonObject baseResponse(@NonNull final JsonElement id) {
        final JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", JSON_RPC_VERSION);
        response.add("id", id.deepCopy());
        return response;
    }

    @Nullable
    private static JsonObject objectValue(@Nullable final JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    @Nullable
    private static String stringValue(@Nullable final JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        return value.getAsString();
    }

    @NonNull
    private static Map<String, String> createSectionResources() {
        final Map<String, String> resources = new LinkedHashMap<>();
        resources.put("all", RESOURCE_SNAPSHOT);
        resources.put("status", RESOURCE_STATUS);
        resources.put("device", RESOURCE_DEVICE);
        resources.put("activity", RESOURCE_ACTIVITY);
        resources.put("daily_metrics", RESOURCE_DAILY_METRICS);
        resources.put("heart_rate", RESOURCE_HEART_RATE);
        resources.put("battery", RESOURCE_BATTERY);
        resources.put("stress", RESOURCE_STRESS);
        resources.put("sleep", RESOURCE_SLEEP);
        return resources;
    }
}
