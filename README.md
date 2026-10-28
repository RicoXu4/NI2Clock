# NI2Clock

A landscape Android clock with Material sheets for weather, Zabbix monitoring,
and Codex account limits. The clock updates every second and fades to a new
position every 30 seconds. The display stays awake while the activity is visible.

- Swipe left for weather; swipe right to dismiss it.
- Swipe right for Zabbix; swipe left to dismiss it.
- Swipe up for Codex usage limits.
- Drag any sheet down, or use its close icon, to dismiss it.
- Long press the clock or an open sheet for settings.

All three panels use the same full-width header, icon actions, scrollable cards,
and a status footer. Portrait layouts are supported as well.

## Project background

This project began as an experiment in building an Android clock entirely with
AI-generated code, for older phones used to monitor a private network.
The [original project notes](docs/project-background.md) preserve that background
and the earlier feature list. The instructions below describe the current app.

## Build

Use Android Studio's bundled JDK (21), or JDK 17, with Android SDK 35 and Build Tools 35.0.0 installed.
The project uses Gradle 8.10 and Android Gradle Plugin 8.7.3. Minimum Android API
is 18; target API remains 33.

```sh
cp clock.properties.example clock.properties
# Fill in the weather API key and optional initial Zabbix defaults locally.
./gradlew assembleDebug testDebugUnitTest lintDebug
```

On macOS with Android Studio installed, if the default Java version is unsupported:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug testDebugUnitTest lintDebug
```

`clock.properties` is ignored by Git. Existing local credentials were preserved
there during cleanup. These values are compiled into the APK; use credentials
appropriate for a client application. Moving them out of source does not remove
credentials from earlier Git history.

## Material design

The app uses real Material 3 components with a dark green color scheme: rounded
cards, outlined text fields, a certificate switch, and accessible icon-only
actions. The digital clock and all swipe/long-press gestures are retained.
Full-width sheets scroll when their contents exceed the available height.

Material Components is pinned to 1.6.1 to preserve Android 4.3 support; its
[official source declares minimum API 14](https://github.com/material-components/material-components-android/blob/1.6.1/lib/build.gradle).
Both activities use AppCompat, and vector icons use the compatibility renderer.

## Code structure

- `MainActivity`: view binding, swipe handling, permissions, clock updates and
  service connections. Timers and services stop when the activity is hidden.
- `ClockPosition`: movement bounds for screen and orientation changes.
- `WeatherService`: network-provider location, HTTPS OpenWeatherMap requests,
  hourly refresh, and manual refresh. Requires coarse location permission and
  an enabled network location provider.
- `ZabbixService`: on-demand metric requests on binding and manual refresh.
- `ZabbixMetrics`: parses CPU/RAM percentages and network rates, leaving missing
  or unsupported values unavailable rather than reporting zero.
- `JsonHttpClient`: background HTTP transport with timeouts and resource cleanup.

## Zabbix settings

Long press anywhere on the dashboard to open **Settings**. Enter an HTTP or HTTPS
Zabbix address (a base address or the full `api_jsonrpc.php` URL), then choose an
API token or username/password login. The save icon returns to the dashboard and refreshes
the monitoring service. The cancel icon or Back discards edits. Saved settings override
optional defaults from `clock.properties` and survive app restarts.

Credentials are stored in private app preferences, excluded from app backups and
device transfer. The settings screen masks secrets and blocks screenshots.

**Ignore certificate errors** is off by default. Enabling it skips certificate
and hostname verification only on Zabbix connections, allowing self-signed
servers. It does not modify global TLS defaults or weather certificate checks.
HTTPS requests enable supported TLS 1.2/1.3 protocols for older Android devices.
Redirects are not followed; enter the final API address. HTTP is permitted for
user-configured local servers; weather always uses HTTPS.

The client obtains the server version before authenticating. It uses body `auth`
for Zabbix 6.0/6.2 and an Authorization bearer header for 6.4 and later. Password
login obtains a temporary session and logs out after fetching metrics. See the
[Zabbix API authentication documentation](https://www.zabbix.com/documentation/current/en/manual/api)
and [user.login reference](https://www.zabbix.com/documentation/current/en/manual/api/reference/user/login).

Metrics use `item.get`. Name-based matching (`CPU`, `RAM`, `Network`) selects the
first matching item with supported units across hosts visible to the credentials.
A deployment with multiple hosts should scope access or add explicit host/item
selection before relying on the panel.

## Codex usage bridge

Codex account limits are read on your computer through the official
[`account/rateLimits/read` app-server method](https://learn.chatgpt.com/docs/app-server#6-rate-limits-chatgpt).
The phone never receives Codex login credentials. The usage endpoint
exports only quota names, used percentages, window durations and reset times.
It requires a separate pairing key and does not expose a general Codex RPC proxy.

With the Codex CLI signed into the same account as the desktop app:

```sh
# Replace this address with your computer's LAN address.
python3 tools/codex_usage_bridge.py --host 10.78.2.225 --configure
# Rebuild/install the app to seed the pairing defaults, or enter them in Settings.
python3 tools/codex_usage_bridge.py --host 10.78.2.225
```

The bridge runs in the foreground until stopped. Keep the computer and bridge
online and reachable from the phone. `--configure` writes the address and a random
pairing key to ignored `clock.properties`; it does not change the Codex login.
Saved phone settings override build-time defaults. Long press to change the
computer bridge address and pairing key without rebuilding.

The sheet refreshes once per minute while open, and the bridge caches reads for
60 seconds. Missing limits stay unavailable, rather than appearing as zero.
Offline or old results retain their original update time and are labeled stale.
Usage is shared across the signed-in account, rather than limited to this chat.
The Zabbix certificate-bypass switch does not affect this connection.

Bridge tests use Python's standard library:

```sh
python3 -m unittest discover -s tools -p 'test_*.py'
```

## Codex approval popup

When the clock is visible, it checks the paired bridge every three seconds and
opens a full-screen Material panel for pending actions, including over the weather, Zabbix,
or usage sheet. Review the complete tool input, working folder and chat ID,
then choose **Approve** (this action once) or **Deny**. Screenshot capture is
blocked while action details are displayed. Monitoring pauses in Settings and
when the app is hidden; this is not a system-wide Android overlay.

Real requests come from Codex's documented
[`PermissionRequest` hook](https://learn.chatgpt.com/docs/hooks#permissionrequest).
Register the hook on this computer:

```sh
python3 tools/install_codex_approval_hook.py
# In Codex CLI, open /hooks and review/trust the NI2Clock hook.
# Start/resume a chat with the hook loaded, and keep the bridge running.
```

The installer preserves existing hooks and backs up an existing hooks.json.
It does not bypass hook trust or change approval policy. This only covers
Codex sessions that load and trust the hook, and tool paths supporting
PermissionRequest. The usage app-server alone cannot observe desktop approvals.

**Approve for me:** PermissionRequest hooks run before native review. The phone
hook therefore returns no decision immediately when the active reviewer is
`auto_review`, leaving safe requests to Codex without a phone popup or delay.
Hook-based approvals are supported for manual (`user`) review mode only; this hook
cannot forward a later human prompt after automatic review.

The current hook protocol does not include the effective reviewer. The bridge
checks the supplied transcript's exact session and latest turn context instead.
Transcript format is not a stable API: missing, mismatched or unknown context
always falls back to native Codex, without forwarding or granting permission.
It never infers the reviewer from global config or `permission_mode`. Pending
requests are withdrawn if the active reviewer or turn changes, and old phone
responses cannot approve them.

**Computer Use app access:** The bridge also listens to the desktop app's local
IPC conversation streams for pending “Allow Codex to use this app?” cards. These
are forwarded in either manual or **Approve for me** mode because they are
already waiting for a human response. The phone shows the app, reason, chat,
and access scope. Approve uses the desktop's primary action: this conversation
when supported, otherwise this request. It never selects **Always allow**.
Deny declines that exact pending request. Desktop-side answers remove the phone
card; disconnects, changed requests, revision gaps and stale responses cannot
grant access. macOS Screen Recording/Accessibility prompts remain local.

This adapter uses the inspected desktop's private IPC protocol (stream version
11, elicitation response version 1), not a stable public API. It subscribes to
the 64 most recently active, nonarchived local chats recorded in state_5.sqlite;
remote-host chats are not supported. Unknown protocol versions fail closed and
leave approval in the desktop app. Chat content is never forwarded to the
phone; only app-access request fields are exposed. No bundled desktop files or
saved Computer Use permissions are modified.

The hook submits through a local Unix socket restricted to the computer user;
remote clients cannot create approval requests. The paired HTTP API exposes
GET /approvals and POST /approvals/<opaque-request-id> with only approve/deny.
The same pairing key now authorizes these per-action responses, so protect it
as an approval credential. HTTP is intended for your trusted private LAN; use
an HTTPS endpoint if the network is untrusted.

Requests expire after two minutes. An offline bridge, a cancelled hook or an
unanswered request returns control to Codex's normal approval flow. Decisions
are never persisted or applied to another request; duplicate/expired decisions
return HTTP 409. No session-wide permission, input rewrite or arbitrary Codex
RPC is exposed. Codex may still reject an action under other hooks or policy.

## Verification

Unit tests cover clock bounds, metric values/units, address validation, both
authentication modes, Codex usage parsing, and self-signed HTTPS acceptance/rejection without changing
global trust. The bundled certificate/private key is a test-only localhost fixture.

On the Samsung GT-N7102 running Android 4.3, ADB testing covers installation,
launch, panel swipes/dismissal, settings navigation/persistence, and live Codex usage from the local bridge. These checks do not guarantee location-provider availability or deployment-specific
Zabbix host/item matching. Missing metrics remain unavailable rather than showing zero.
