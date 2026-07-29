package nodomain.freeyourgadget.gadgetbridge.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class McpProtocolTest {
    private AtomicInteger refreshCount;
    private McpProtocol protocol;

    @Before
    public void setUp() {
        refreshCount = new AtomicInteger();
        final McpSnapshot snapshot = McpSnapshot.empty()
                .withServiceStatus(true, "http://127.0.0.1:8765/mcp", null)
                .withSyncStatus(false, 1234L, null, "Ready");
        protocol = new McpProtocol(
                () -> snapshot,
                () -> {
                    refreshCount.incrementAndGet();
                    final JsonObject result = new JsonObject();
                    result.addProperty("accepted", true);
                    return result;
                }
        );
    }

    @Test
    public void initializeAdvertisesSupportedProtocol() {
        final JsonObject response = request("initialize", null);
        Assert.assertEquals(
                McpProtocol.SUPPORTED_PROTOCOL_VERSION,
                response.getAsJsonObject("result").get("protocolVersion").getAsString()
        );
        Assert.assertEquals(
                "Gadgetbridge MCP",
                response.getAsJsonObject("result")
                        .getAsJsonObject("serverInfo")
                        .get("name")
                        .getAsString()
        );
    }

    @Test
    public void listsTwoFocusedTools() {
        final JsonObject response = request("tools/list", null);
        final var tools = response.getAsJsonObject("result").getAsJsonArray("tools");
        Assert.assertEquals(2, tools.size());
        Assert.assertEquals("band_get_data", tools.get(0).getAsJsonObject().get("name").getAsString());
        Assert.assertEquals("band_refresh_now", tools.get(1).getAsJsonObject().get("name").getAsString());
    }

    @Test
    public void listsNineStableResources() {
        final JsonObject response = request("resources/list", null);
        final var resources = response.getAsJsonObject("result").getAsJsonArray("resources");
        Assert.assertEquals(9, resources.size());
        Assert.assertEquals(
                "miband://snapshot",
                resources.get(0).getAsJsonObject().get("uri").getAsString()
        );
        Assert.assertEquals(
                "miband://sleep/latest",
                resources.get(8).getAsJsonObject().get("uri").getAsString()
        );
    }

    @Test
    public void snapshotPreservesStructuredEndpointContract() {
        final JsonObject response = request("tools/call", getDataParams("all"));
        final String text = response.getAsJsonObject("result")
                .getAsJsonArray("content")
                .get(0)
                .getAsJsonObject()
                .get("text")
                .getAsString();
        final JsonObject endpoint = JsonParser.parseString(text)
                .getAsJsonObject()
                .getAsJsonObject("serviceStatus")
                .getAsJsonObject("endpoint");
        Assert.assertEquals("127.0.0.1", endpoint.get("host").getAsString());
        Assert.assertEquals(8765, endpoint.get("port").getAsInt());
        Assert.assertEquals("http://127.0.0.1:8765/mcp", endpoint.get("url").getAsString());
    }

    @Test
    public void getDataReturnsRequestedSection() {
        final JsonObject response = request("tools/call", getDataParams("status"));
        final String text = response.getAsJsonObject("result")
                .getAsJsonArray("content")
                .get(0)
                .getAsJsonObject()
                .get("text")
                .getAsString();
        final JsonObject status = JsonParser.parseString(text).getAsJsonObject();
        Assert.assertTrue(status.has("serviceStatus"));
        Assert.assertTrue(status.has("syncStatus"));
    }

    @Test
    public void refreshInvokesHandlerOnce() {
        final JsonObject params = new JsonObject();
        params.addProperty("name", "band_refresh_now");

        final JsonObject response = request("tools/call", params);
        Assert.assertEquals(1, refreshCount.get());
        Assert.assertFalse(response.getAsJsonObject("result").get("isError").getAsBoolean());
    }

    @Test
    public void notificationsDoNotProduceResponse() {
        Assert.assertNull(protocol.handle(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"
        ));
    }

    @Test
    public void malformedJsonReturnsParseError() {
        final String rawResponse = protocol.handle("{");
        Assert.assertNotNull(rawResponse);
        final JsonObject response = JsonParser.parseString(rawResponse).getAsJsonObject();
        Assert.assertEquals(-32700, response.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    public void unknownSectionIsToolError() {
        final JsonObject response = request("tools/call", getDataParams("unknown"));
        Assert.assertTrue(response.getAsJsonObject("result").get("isError").getAsBoolean());
    }

    private static JsonObject getDataParams(final String section) {
        final JsonObject arguments = new JsonObject();
        arguments.addProperty("section", section);
        final JsonObject params = new JsonObject();
        params.addProperty("name", "band_get_data");
        params.add("arguments", arguments);
        return params;
    }

    private JsonObject request(final String method, final JsonObject params) {
        final JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 1);
        request.addProperty("method", method);
        if (params != null) {
            request.add("params", params);
        }
        final String response = protocol.handle(request.toString());
        Assert.assertNotNull(response);
        return JsonParser.parseString(response).getAsJsonObject();
    }
}
