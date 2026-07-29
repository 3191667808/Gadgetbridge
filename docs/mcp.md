# Gadgetbridge MCP server

Gadgetbridge embeds an optional Model Context Protocol server. It publishes the
latest synchronized wearable data without exporting a database or passing files
through a second application.

## Configure

Open **Settings > External integrations > MCP server** and enable the server.
The settings screen provides the current status, device selection, port,
endpoint copy action, and bearer-token actions.

The defaults are deliberately local:

- endpoint: `http://127.0.0.1:8765/mcp`
- bind address: `127.0.0.1`
- selected device: first paired device that supports activity tracking
- LAN access: disabled

The port must be between `1024` and `65535`. When LAN access is enabled, the
server binds to `0.0.0.0`, displays the phone's current private IPv4 address,
and requires this header on every request:

```http
Authorization: Bearer <token copied from Gadgetbridge>
```

Regenerating the token immediately invalidates existing LAN client
configurations. Loopback mode ignores the token because another device cannot
reach that listener.

An MCP client should use the copied URL as a Streamable HTTP endpoint. A minimal
protocol request is:

```sh
curl -X POST http://127.0.0.1:8765/mcp \
  -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

Add the `Authorization` header when using the LAN endpoint.

## Protocol surface

The server intentionally keeps a small command surface:

- `band_get_data` reads the complete immutable snapshot or one requested data
  section.
- `band_refresh_now` requests a device synchronization and reports whether the
  request was accepted.

The same snapshot is available through nine resources:

- `miband://snapshot`
- `miband://status`
- `miband://device`
- `miband://activity/today`
- `miband://daily-metrics/latest`
- `miband://heart-rate/latest`
- `miband://battery/latest`
- `miband://stress/latest`
- `miband://sleep/latest`

`band_refresh_now` only requests synchronization. Completion is driven by
Gadgetbridge's `ACTION_NEW_DATA` event; the server does not guess completion
from an elapsed delay. A two-minute timeout changes the snapshot to an error
state if Gadgetbridge never signals completion.

## Architecture

The MCP listener is owned by `DeviceCommunicationService`. Enabling MCP starts
that existing foreground service after boot or package replacement even when
the separate Gadgetbridge auto-start setting is disabled. Disabling MCP stops
the listener but does not change device connection settings.

HTTP worker threads only read one immutable in-memory snapshot. A dedicated
single-thread executor performs read-only Provider/DAO queries and atomically
replaces the complete snapshot. This keeps network latency independent from
database work and prevents clients from observing partially updated health
data.

The implementation uses NanoHTTPD and Gadgetbridge's existing Gson dependency.
There is no Ktor runtime, database export, compatibility bridge, or second
foreground notification.

## Security and network permission

Android requires `android.permission.INTERNET` for both outbound connections
and local TCP listeners, so Gadgetbridge declares it for MCP. Mainline builds still
set `BuildConfig.INTERNET_ACCESS` to `false`; Gadgetbridge's existing outbound
network features therefore remain behind the Internet Helper instead of being
implicitly enabled by the manifest permission.

LAN mode expands exposure beyond the phone. It is opt-in, rejects requests
without a constant-time-checked bearer token, and rejects browser-originated
requests carrying an `Origin` header. Treat the token as a password and only
use LAN mode on networks you trust. MCP health data is served as plain HTTP, so
the bearer token and response body are not encrypted in transit.

## Source and license

Gadgetbridge is licensed under the GNU AGPLv3. Distributing a modified APK or
making its network service available to users requires offering the complete
corresponding source under the license.
