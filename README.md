# Nidoo-gateway

[![CI](https://github.com/Gekkotron/Nidoo-gateway/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/Gekkotron/Nidoo-gateway/actions/workflows/ci.yml)
[![Coverage](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/Gekkotron/Nidoo-gateway/badges/coverage.json)](https://github.com/Gekkotron/Nidoo-gateway/actions/workflows/ci.yml)
[![SDK coverage](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/Gekkotron/Nidoo-gateway/badges/sdk-coverage.json)](sdk/kotlin/README.md)

An end-to-end encrypted gateway for HTTP APIs and MQTT communications, using AES-GCM. Ship home-automation traffic over the public internet through a single encrypted tunnel — no per-service certificates, no exposed brokers.

> The gateway of [Nidoo](https://github.com/Gekkotron/Nidoo): the Nidoo app reaches the hub through it when away from home.

## Features

- **AES-256-GCM Encryption**: Every request and response is encrypted between the client and the gateway
- **Multi-Service Support**: Unified gateway for both generic HTTP APIs and MQTT broker communications
- **MQTT Publish & Subscribe**: Full MQTT support with encrypted publish and SSE-based subscription streaming
- **Generic HTTP Proxy**: Forward any HTTP request with encrypted payload
- **Automatic Key Management**: Secure secret key generation on first launch
- **Replay Attack Protection**: Timestamp validation with a configurable window, and reused nonces are rejected
- **Docker Ready**: Complete containerization with Docker Compose support
- **Tailscale Funnel Integration**: Secure public internet exposure without port forwarding
- **Health Checks**: A `/health` endpoint, plus gateway and Tailscale checks that can restart services and publish their state over MQTT

## Architecture

<p align="center">
  <img src="docs/architecture.svg" width="880" alt="Nidoo-gateway architecture: the client sends an AES-GCM encrypted request to the gateway, which decrypts it and forwards it in plain form to an HTTP API or the MQTT broker, then encrypts the reply on the way back.">
</p>

The gateway acts as a secure proxy, encrypting/decrypting traffic between your client applications and backend services (HTTP APIs and MQTT).

## Quick Start

### Prerequisites

- Python 3.11+ (CI tests 3.11 and 3.12) or Docker
- (Optional) Tailscale for secure internet exposure

### Installation

#### Option 1: Docker (Recommended for Production)

1. Clone the repository:
```bash
git clone https://github.com/Gekkotron/Nidoo-gateway.git
cd Nidoo-gateway
```

2. Configure (optional): Compose reads `MAX_AGE_SECONDS` from a `.env` file; to change anything else, edit `environment:` in `docker-compose.yml` (see [Environment Variables](#environment-variables)):
```bash
cp .env.example .env
```

3. Start with Docker Compose:
```bash
docker compose up -d
```

The server will start on port 10000 and automatically generate a secret key in `data/secret_key.txt`.

#### Option 2: Direct Python (Development)

1. Create and activate a virtual environment:
```bash
python -m venv .venv
source .venv/bin/activate  # On Windows: .venv\Scripts\activate
```

2. Install dependencies:
```bash
pip install -r requirements.txt
```

3. Run the server:
```bash
python -m server
```

### Secret Key Setup

On first launch, the server automatically generates a 256-bit AES secret key with full access (scope `*`) and displays it:

```
================================================================================
NEW SECRET KEY GENERATED!
================================================================================
Secret key: 3616864d6ed4f70b8a774e17c7435b411d8bc5714fddaf88e3850104a9d88ccd
Saved to: data/secret_key.txt
Scope: * (full access to every port and destination)
================================================================================
Please update your client with this secret key.
================================================================================
```

**Important**: Save this key securely and configure your clients with it.

#### Multiple secrets with per-endpoint scopes

The key file (`data/secret_key.txt`, or whatever `SECRET_KEY_FILE` points to) is a **line-based** list of secrets. Each line is
`<64-hex-chars>[:<scope>]`, where the scope selects which endpoint
families the secret may reach:

| Port | Endpoints it unlocks             |
|------|----------------------------------|
| `80` | `/gateway` (HTTP forwarding)     |
| `1883` | `/mqtt/publish`, `/mqtt/subscribe` |

Scope syntax:

- `*` — full access to every port and destination (auto-generation writes this).
- Comma-separated port list — e.g. `80` (HTTP only), `80,1883` (both).
- Optional `@<destinations>` suffix restricts what target host each key
  may reach. Destinations are a comma-separated list of **IPs or CIDR
  ranges** (hostnames are rejected at parse time to keep the check
  DNS-independent):
  - `80@192.168.1.50` — HTTP forwarding, only to that IP.
  - `1883@192.168.1.0/24` — MQTT broker must be inside the /24.
  - `*@192.168.1.0/24` — full port access, LAN only.
  - Missing `@…` (or `@*`) means any destination.
- **No colon at all** — a bare hex line is treated as `*`, so legacy
  single-key files keep working unchanged.

Destination enforcement:

- `/gateway` extracts the target host from the payload's `url` (via
  `urlparse`) or `host` field.
- `/mqtt/publish` and `/mqtt/subscribe` use `broker_host` from the
  payload, or `MQTT_BROKER_HOST` from config if the client omits it.
- The client-sent value must parse as an IP; a hostname sent to an
  IP-scoped secret is refused with an encrypted 403.

Blank lines and `#` comments are ignored. Example:

```
# Full-access key for your admin client (auto-generated).
3616864d6ed4f70b8a774e17c7435b411d8bc5714fddaf88e3850104a9d88ccd:*

# HTTP-only key you can hand to a web-only service.
9d3a0e5cb1f0f5a5e3d4c1b6a7089d1e2f3a4b5c6d7e8f90112233445566778899:80

# Key that can do BOTH HTTP and MQTT (multiple ports, no destination filter).
2222222222222222222222222222222222222222222222222222222222222222:80,1883

# Same as above, but pinned to one target host (works for HTTP and MQTT
# because it's the same host for both).
3333333333333333333333333333333333333333333333333333333333333333:80,1883@192.168.1.91

# Multiple destinations on one line -- port(s) may reach any of them.
# Here: HTTP forwarding is allowed to two Jeedom boxes.
4444444444444444444444444444444444444444444444444444444444444444:80@192.168.1.50,192.168.1.51

# MQTT-only key for a device, LAN broker only.
aabb00112233445566778899aabbccddeeff00112233445566778899aabbccdd:1883@192.168.1.91

# Full access, but LAN only.
1122334455667788112233445566778811223344556677881122334455667788:*@192.168.1.0/24

# --- Per-port destination (different targets per port) ---
# The single-line syntax "80,1883@ip1,ip2" allows BOTH ports to reach BOTH
# ips. If you want port A -> host X and port B -> host Y under ONE key,
# split them across two lines reusing the SAME hex key. Lines sharing a
# key are merged into a single secret with multiple (ports, destinations)
# rules; a request is permitted when ANY rule matches its (port, host).
5555555555555555555555555555555555555555555555555555555555555555:80@192.168.1.50
5555555555555555555555555555555555555555555555555555555555555555:1883@192.168.1.91
```

The gateway trial-decrypts each incoming request against every configured
key. On a match, it enforces the key's scope against the endpoint being
called. Requests whose ciphertext no key can authenticate are rejected
with an **unencrypted HTTP 401** (the server cannot encrypt a response
without knowing which key the client holds).

#### Adding a new scoped secret

`python -m server.add_secret` generates a fresh random 256-bit key and
appends it to the secret-key file. Use it to hand out keys per service
without editing the file by hand:

```bash
# HTTP-only key
python -m server.add_secret --scope 80

# MQTT-only key with a label above it
python -m server.add_secret --scope 1883 --comment "device: living-room"

# HTTP-only key locked to one target IP
python -m server.add_secret --scope 80 --dest 192.168.1.50

# Full-access key restricted to the LAN
python -m server.add_secret --scope '*' --dest 192.168.1.0/24

# Embedded destination form is also accepted
python -m server.add_secret --scope '80@192.168.1.50'
```

The tool prints the new hex secret so you can copy it into a client.
Existing keys are left untouched; the file is validated before the append
so a corrupted line stops the tool before it writes anything.

## Configuration

### Environment Variables

The gateway reads these from real environment variables: `export` them, pass `-e` to `docker run`, or set them under `environment:` in `docker-compose.yml`. The server itself does **not** load `.env`; that file is read by the test clients and health checks, and by Compose for `MAX_AGE_SECONDS` only.

| Variable | Default | Description |
|----------|---------|-------------|
| `SECRET_KEY_FILE` | `data/secret_key.txt` | Path to the secret key file |
| `MAX_AGE_SECONDS` | `60` | Maximum age for request timestamps (replay protection) |
| `HOST` | `0.0.0.0` | Server bind address |
| `PORT` | `10000` | Server port |
| `MQTT_BROKER_HOST` | `192.168.1.91` | Default MQTT broker host, used when a request gives no `broker_host` |
| `MQTT_BROKER_PORT` | `1883` | Default MQTT broker port |
| `HTTP_MAX_RESPONSE_BYTES` | `26214400` (25 MiB) | Size cap for `/gateway` responses in raw mode (`"raw": true`) |

### Docker Volumes

The Docker setup uses a persistent volume for the secret key:
- `./data:/app/data` - Stores the secret key across container restarts

## Usage

### Client Implementation

The project includes ready-to-use Python client classes for HTTP and MQTT:

#### HTTP Request Example

```python
from test_client.client_http_test import EncryptedHttpClient

# Load secret key from file
with open('data/secret_key.txt', 'r') as f:
    # Line-based file: each entry is `<hex>[:<scope>]` -- take the hex part.
    SECRET_KEY = f.readline().strip().split(':', 1)[0]

# Initialize client
client = EncryptedHttpClient(
    gateway_url="http://localhost:10000",
    secret_key=SECRET_KEY
)

# Make encrypted HTTP request
response = client.send_request(
    url="https://api.example.com/endpoint",
    method="POST",
    body={"key": "value"},
    headers={"Content-Type": "application/json"}
)

print(f"Status: {response['status']}")
print(f"Body: {response['body']}")
```

#### Jeedom JSON-RPC Example

Use the gateway to communicate with a Jeedom home automation server via its JSON-RPC API:

```python
from test_client.client_http_test import EncryptedHttpClient

# Load secret key
with open('data/secret_key.txt', 'r') as f:
    # Line-based file: each entry is `<hex>[:<scope>]` -- take the hex part.
    SECRET_KEY = f.readline().strip().split(':', 1)[0]

client = EncryptedHttpClient(
    gateway_url="https://yourdevice.tailnet-name.ts.net",
    secret_key=SECRET_KEY
)

# Get Jeedom server datetime
response = client.send_request(
    url="http://jeedom-host/core/api/jeeApi.php",
    method="POST",
    headers={"Content-Type": "application/json"},
    body={
        "jsonrpc": "2.0",
        "id": 1,
        "method": "datetime",
        "params": {
            "apikey": "your-jeedom-api-key"
        }
    }
)

print(f"Status: {response['status']}")
print(f"Jeedom datetime: {response['body']['result']}")

# Get all objects with full details
response = client.send_request(
    url="http://jeedom-host/core/api/jeeApi.php",
    method="POST",
    headers={"Content-Type": "application/json"},
    body={
        "jsonrpc": "2.0",
        "id": 1,
        "method": "jeeObject::full",
        "params": {
            "apikey": "your-jeedom-api-key"
        }
    }
)

for obj in response['body']['result']:
    print(f"Object: {obj['name']} (id={obj['id']})")

# Execute a command (e.g., turn on a light)
response = client.send_request(
    url="http://jeedom-host/core/api/jeeApi.php",
    method="POST",
    headers={"Content-Type": "application/json"},
    body={
        "jsonrpc": "2.0",
        "id": 1,
        "method": "cmd::execCmd",
        "params": {
            "apikey": "your-jeedom-api-key",
            "id": 42,  # Command ID
            "options": {}
        }
    }
)

print(f"Command result: {response['body']}")
```

#### MQTT Publish Example

```python
from test_client.client_mqtt_test import EncryptedClient

# Initialize client
client = EncryptedClient(
    gateway_url="http://localhost:10000",
    secret_key=SECRET_KEY
)

# Publish encrypted message
response = client.send_mqtt(
    topic="home/temperature",
    message="22.5",
    qos=1,
    retain=True
)

print(f"Status: {response['status']}")
print(f"Body: {response['body']}")
```

#### MQTT Subscribe (SSE) Example

Subscribe to MQTT topics and receive messages in real-time via Server-Sent Events. This helper is an interactive demo: it reads the key from `SECRET_KEY_FILE` and prints messages until Ctrl+C.

```python
from test_client.client_mqtt_sse_test import subscribe_to_mqtt_topic

# Subscribe to a topic
subscribe_to_mqtt_topic(
    server_url="http://localhost:10000",
    topic="home/sensors/#",  # Supports MQTT wildcards
    qos=1
)

# With custom broker settings
subscribe_to_mqtt_topic(
    server_url="http://localhost:10000",
    topic="home/temperature",
    broker_host="192.168.1.100",
    broker_port=1883,
    username="mqtt_user",
    password="mqtt_password",
    qos=1
)
```

The SSE stream will receive encrypted messages with the following types:
- **connected**: Successfully subscribed to the topic
- **message**: MQTT message received (includes topic, payload, payload_b64, qos, retain, timestamp)
- **error**: An error occurred
- **disconnected**: Connection closed

#### Kotlin / Android

Use the official client library, [`athena-gateway-client`](sdk/kotlin/README.md):

```kotlin
implementation("com.github.Gekkotron.Nidoo-gateway:athena-gateway-client:v1.4")
```

Fetch binary files (camera snapshots, clips) with `download()` (needs gateway v1.3+):

```kotlin
val snapshot = gateway.download("http://192.168.1.20:5000/api/events/<id>/snapshot.jpg")
snapshot.bytes        // exact bytes
snapshot.contentType  // e.g. "image/jpeg"
```

Store the secret key outside source control — e.g. in `EncryptedSharedPreferences` / the Android Keystore — and see the SDK README for certificate pinning.

### Request Format

All requests must include a `timestamp` field for replay protection:

```json
{
    "timestamp": 1234567890,
    // Endpoint-specific fields...
}
```

Different endpoints require different fields (see API Endpoints section for details).

#### MQTT Publish Request Fields

- `topic`: MQTT topic to publish to
- `message`: Message payload
- `broker_host`: (Optional) MQTT broker hostname
- `broker_port`: (Optional) MQTT broker port
- `username`: (Optional) MQTT username
- `password`: (Optional) MQTT password
- `qos`: (Optional) Quality of Service (0-2)
- `retain`: (Optional) Retain flag
- `timestamp`: Current Unix timestamp

#### MQTT Subscribe (SSE) Request Fields

Used with the `/mqtt/subscribe` endpoint for real-time subscriptions:

- `topics`: List of MQTT topics to subscribe to on one stream (supports wildcards: `+` for single level, `#` for multi-level)
- `topic`: Single MQTT topic (legacy form; may be combined with `topics`). At least one of `topic` / `topics` is required
- `broker_host`: (Optional) MQTT broker hostname
- `broker_port`: (Optional) MQTT broker port
- `username`: (Optional) MQTT username
- `password`: (Optional) MQTT password
- `qos`: (Optional) Quality of Service (0-2), applied to every topic
- `timestamp`: Current Unix timestamp (required for replay protection)

**Example Subscribe Payload:**
```json
{
    "topics": ["home/sensors/#", "home/lights/+/state"],
    "broker_host": "192.168.1.91",
    "broker_port": 1883,
    "qos": 1,
    "timestamp": 1234567890
}
```

**SSE Response Messages:**

Each SSE event contains an encrypted payload with one of these message types:

1. **Connected Event:**
```json
{
    "type": "connected",
    "topic": "home/sensors/#",
    "topics": ["home/sensors/#", "home/lights/+/state"],
    "message": "Successfully connected and subscribed to home/sensors/#, home/lights/+/state"
}
```

2. **Message Event:**
```json
{
    "type": "message",
    "topic": "home/sensors/temperature",
    "payload": 22.5,
    "payload_b64": "MjIuNQ==",
    "qos": 1,
    "retain": false,
    "timestamp": 1234567890
}
```

`payload` is the message parsed as JSON when possible (here the number `22.5`), otherwise the text, or `null` when the bytes are not UTF-8. `payload_b64` always carries the exact bytes (gateway v1.2+).

3. **Error Event:**
```json
{
    "type": "error",
    "message": "Connection failed with code 5",
    "code": 5
}
```

`code` is the broker's CONNACK code and is present only on connection failures.

4. **Disconnected Event:**
```json
{
    "type": "disconnected",
    "message": "Disconnected from broker (code 0)"
}
```

All messages are encrypted using AES-GCM and base64-encoded before being sent via SSE.

### Response Format

All responses are encrypted and contain at least:

```json
{
    "status": 200,
    "body": "...",
    "timestamp": 1234567890
}
```

`/gateway` answers may add `headers` (v1.4+), or `body_b64` and `content_type` in raw mode, and errors the gateway produces itself carry `"source": "gateway"`. See [POST /gateway](#post-gateway).

## Testing

### Unit Tests

Offline tests (no broker, no network) run in GitHub Actions on pushes to `master` and on pull requests, on Python 3.11 and 3.12; coverage must stay at 90% or more:

```bash
pip install -r requirements-dev.txt
python -m pytest --cov=server --cov-report=term-missing
```

### Integration Test Clients

These talk to a running gateway. Point them at it with `GATEWAY_URL`
(default `http://localhost:10000`), e.g. from your git-ignored `.env`.

### Run All Tests

```bash
python test_client/client_all_services_test.py
```

### Run Individual Tests

```bash
# HTTP gateway tests (6 test cases)
python test_client/client_http_test.py

# MQTT publish tests (4 test cases)
python test_client/client_mqtt_test.py

# MQTT SSE subscribe tests (3 test cases)
python test_client/client_mqtt_sse_automated_test.py
```

### Test Coverage

**HTTP Gateway Tests:**
- Valid GET request
- Valid POST request
- Expired timestamp rejection
- Future timestamp rejection
- Wrong secret key handling
- Missing URL field

**MQTT Tests:**
- Basic message publishing
- QoS and retain flags
- Custom broker configuration
- Error handling for missing fields

**MQTT SSE Tests:**
- Basic subscription connection
- Wildcard topic subscription
- Invalid (empty) topic rejection

## Health Checks

Two independent health checks help diagnose flaky home-automation connections. Run each on its own, or both together — the exit code makes them cron/monitoring friendly (`0` OK, `1` failure, `2` configuration error).

```bash
# Both checks (default)
python -m test_client.health_check

# HTTP gateway only: /health ping + encrypted round-trip
python -m test_client.health_check --gateway

# Tailscale only: daemon + funnel + remote HTTPS probe
python -m test_client.health_check --tailscale

# Auto-remediate: restart whatever failed, then re-check
python -m test_client.health_check --restart

# Quiet mode (exit code only) — cron-friendly
python -m test_client.health_check --all -q --restart
```

**Auto-restart (`--restart`):** on a *hard failure* (exit 1), the failing component is restarted, then re-checked with **exponential backoff** until it recovers or the attempt budget is exhausted. Configuration errors (exit 2) never trigger a restart — fix the config first.

- Gateway restart defaults to `docker compose restart gateway` (matching `docker-compose.yml`). Override with `GATEWAY_RESTART_CMD` if you run the app another way.
- Tailscale restart defaults to `./run_tailscale.sh` — the same script used to bring the funnel up manually. The script uses `sudo` internally, so passwordless sudo is required for headless/cron use. Override with `TAILSCALE_RESTART_CMD` if you want something else.

Post-restart backoff (delays are cumulative, loop stops on the first PASS):

| Variable | Purpose | Default |
|---|---|---|
| `RESTART_BACKOFF_INITIAL` | Seconds before the first re-check | `2` |
| `RESTART_BACKOFF_FACTOR` | Multiplier applied between attempts | `2` |
| `RESTART_BACKOFF_MAX_ATTEMPTS` | Maximum re-check attempts (`0` = retry forever) | `5` |
| `RESTART_BACKOFF_MAX_DELAY` | Per-attempt delay cap in seconds | `300` |

With defaults, delays are 2s, 4s, 8s, 16s, 32s (max ~62s of waiting).
Set `RESTART_BACKOFF_MAX_ATTEMPTS=0` to retry forever — the delay grows exponentially, plateaus at `RESTART_BACKOFF_MAX_DELAY` (5min by default), and the loop only exits when the check passes. Pair this with a systemd/cron *timer* rather than a supervisor loop if you want to be able to kill it easily.

### MQTT state topic

After each check completes, the orchestrator publishes the current state as a JSON message to the configured MQTT broker. The message is **retained** by default so a subscriber (Jeedom, Home Assistant, …) that connects between runs immediately sees the last known state — no need to wait for the next timer fire. Publishing is a side channel; if the broker is unreachable, a warning goes to stderr but the exit code is not affected.

**Semantics:** state, not event. `status` cycles through `ok` / `fail` / `config_error` on every run. Downstream automations should compare the payload's `status` field against their expected value rather than treating any message as an alert. If you preferred the old "silent while healthy" behaviour, set `HEALTH_ALERT_PUBLISH_ON_SUCCESS=false` — but also set `HEALTH_ALERT_MQTT_RETAIN=false`, otherwise a resolved failure sticks around as a retained message forever.

**Topic layout:** `<HEALTH_ALERT_MQTT_TOPIC_PREFIX>/<component>`

Default topics (matching the docker-compose `container_name: encryption-gateway`):
- `encryption-gateway/health/gateway`
- `encryption-gateway/health/tailscale`

Override `HEALTH_ALERT_MQTT_TOPIC_PREFIX` if you'd rather group these under something else (e.g. per-house or per-room).

**Payload (JSON):**
```json
{
  "component": "gateway",
  "status": "ok",             // "ok" | "fail" | "config_error"
  "exit_code": 0,
  "restart_attempted": false,
  "timestamp": 1723456789,
  "host": "raspberrypi"
}
```

**Env vars:**

| Variable | Purpose | Default |
|---|---|---|
| `MQTT_BROKER_HOST` | Broker hostname (required for publishing) | *(unset — disabled)* |
| `MQTT_BROKER_PORT` | Broker port | `1883` |
| `MQTT_USERNAME` / `MQTT_PASSWORD` | Optional broker credentials | *(unset)* |
| `HEALTH_ALERT_ENABLED` | `auto` (enable if broker set), `true`, or `false` | `auto` |
| `HEALTH_ALERT_PUBLISH_ON_SUCCESS` | Publish on PASS too (state topic) or only on FAIL (event) | `true` |
| `HEALTH_ALERT_MQTT_TOPIC_PREFIX` | Topic prefix | `encryption-gateway/health` |
| `HEALTH_ALERT_MQTT_QOS` | QoS 0/1/2 | `1` |
| `HEALTH_ALERT_MQTT_RETAIN` | Retain flag — leave on for state semantics | `true` |
| `HEALTH_ALERT_MQTT_TIMEOUT` | Connect timeout, seconds | `5` |

Pass `--no-alert` on the command line to skip publishing for a single invocation (useful when running the check interactively during debugging).

**Gateway check** (`test_client/health_check_gateway.py`) runs:
1. `GET /health` — proves Flask is up.
2. Encrypted round-trip through `/gateway` to `HEALTH_CHECK_URL` (default `https://httpbin.org/get`) — proves the crypto path is intact end-to-end.

**Tailscale check** (`test_client/health_check_tailscale.py`) runs:
1. `tailscale status --json` — daemon is running and this node is online.
2. `tailscale funnel status` — funnel is bound to the expected port.
3. HTTPS `GET /health` on `TAILSCALE_FUNNEL_URL` — the public URL is reachable.

Additional environment variables (see `.env.example`):

| Variable | Purpose | Default |
|---|---|---|
| `HEALTH_CHECK_URL` | Target URL for the encrypted round-trip | `https://httpbin.org/get` |
| `HEALTH_TIMEOUT` | Per-request timeout, seconds | `10` |
| `TAILSCALE_FUNNEL_URL` | Public funnel URL to probe remotely | *(unset — remote probe fails)* |
| `TAILSCALE_FUNNEL_PORT` | Expected port bound to the funnel | `10000` |
| `TAILSCALE_BIN` | tailscale CLI binary path | `tailscale` |
| `TAILSCALE_CHECK_LOCAL` | `auto`/`true`/`false` — run local daemon+funnel checks | `auto` |
| `GATEWAY_RESTART_CMD` | Command run by `--restart` for the gateway | `docker compose restart gateway` |
| `TAILSCALE_RESTART_CMD` | Command run by `--restart` for the funnel | `./run_tailscale.sh` |

### Running the health check automatically at startup (systemd)

Ships with a `.service` + `.timer` pair under `scripts/systemd/`. The timer fires once ~1 minute after boot and then every 5 minutes; the service runs `python -m test_client.health_check --all --restart` and exits (single-shot per fire). Compared to a supervisor loop, this pattern:

- Uses the system journal for logs (`journalctl -u encryption-gateway-health -f`).
- Bounds each run with `TimeoutStartSec=15min`, so a hung network call can't jam future firings.
- Can be started/stopped cleanly with `systemctl` — no PID files.

**Install (one command; rerun after moving the repo or changing the venv):**

```bash
sudo scripts/install-systemd.sh
```

The installer picks up the current directory as `REPO_DIR`, the invoking user (`$SUDO_USER`) as the service user, and `./.venv/bin/python` if you use a venv (falling back to system `python3`). Override any of these with env vars (`REPO_DIR`, `SERVICE_USER`, `PYTHON_BIN`), e.g. `sudo REPO_DIR=/opt/enc-gw PYTHON_BIN=/usr/local/bin/python3.11 scripts/install-systemd.sh`.

The service loads `REPO_DIR/.env` and fails to start if that file is missing, so create it first (`cp .env.example .env`). Remove everything with `sudo scripts/uninstall-systemd.sh`.

**Common ops:**

```bash
systemctl status encryption-gateway-health.timer      # is the timer armed?
systemctl list-timers | grep encryption-gateway       # when's the next fire?
journalctl -u encryption-gateway-health.service -f    # follow live logs
systemctl start encryption-gateway-health.service     # run one check immediately
systemctl disable --now encryption-gateway-health.timer   # stop the periodic runs
```

**Tuning the interval:** edit `OnUnitActiveSec=` in `scripts/systemd/encryption-gateway-health.timer` and rerun the installer.

### Cron alternative (non-systemd hosts)

If you're not on systemd, a plain crontab entry works fine:

```cron
@reboot   sleep 60 && cd /opt/enc-gw && /opt/enc-gw/.venv/bin/python -m test_client.health_check --all --restart >> /var/log/enc-gw-health.log 2>&1
*/5 * * * * cd /opt/enc-gw && /opt/enc-gw/.venv/bin/python -m test_client.health_check --all --restart >> /var/log/enc-gw-health.log 2>&1
```

Note that cron doesn't provide the `TimeoutStartSec` safety net — a hung check can pile up if the interval is short. Prefer systemd where available.

## Tailscale Funnel Deployment

Expose your gateway securely over the internet using Tailscale Funnel:

1. Install and authenticate Tailscale on your machine
2. Enable Funnel for your account
3. Start the server
4. Run the Tailscale script:

```bash
chmod +x run_tailscale.sh
./run_tailscale.sh
```

The script uses `sudo`: it turns Funnel off (which clears any existing Funnel setup on this machine) and then serves port 10000 in the background. Your gateway will be accessible via a public HTTPS URL like `https://yourdevice.tailnet-name.ts.net`; `tailscale funnel status` shows it.

## Security Features

### Encryption

- **Algorithm**: AES-GCM with 256-bit keys
- **Nonce**: 12-byte random nonce per message
- **Authentication**: Built-in authenticated encryption prevents tampering
- **Key Management**: Automatic secure key generation using OS-level randomness

### Replay Attack Protection

The gateway validates timestamps to prevent replay attacks:
- Requests older than `MAX_AGE_SECONDS` are rejected
- Future timestamps are also rejected
- Default window: 60 seconds (configurable)
- Each request's AES-GCM nonce is remembered for the length of the window, so an identical request replayed inside the window is rejected with `Request replayed`
- Applies to all three encrypted endpoints: `/gateway`, `/mqtt/publish` and `/mqtt/subscribe`

### Best Practices

1. **Store the secret key securely**: Never commit it to version control
2. **Use HTTPS**: Deploy with Tailscale Funnel or behind a reverse proxy with TLS
3. **Rotate keys periodically**: Generate new keys and update clients
4. **Monitor logs**: Watch for suspicious activity or repeated failed decryption attempts
5. **Network isolation**: Run the gateway in a separate network segment if possible

## Project Structure

```
Nidoo-gateway/
├── server/
│   ├── __init__.py
│   ├── __main__.py          # Entry point
│   ├── app.py               # Flask application factory
│   ├── config.py            # Configuration from environment variables
│   ├── crypto.py            # AES-GCM encryption/decryption
│   ├── gateway.py           # Request routing and handling
│   ├── key_manager.py       # Secret loading, scopes, first-launch generation
│   ├── add_secret.py        # CLI: append a scoped secret
│   ├── replay.py            # Nonce cache for replay protection
│   └── services/
│       ├── http_service.py        # Generic HTTP proxy handler
│       ├── mqtt_service.py        # MQTT publish handler
│       └── mqtt_sse_service.py    # MQTT subscribe via SSE handler
├── test_client/
│   ├── client_http_test.py              # HTTP gateway client and tests
│   ├── client_mqtt_test.py              # MQTT publish client and tests
│   ├── client_mqtt_sse_test.py          # MQTT SSE subscribe demo
│   ├── client_mqtt_sse_automated_test.py  # MQTT SSE subscribe tests
│   ├── client_all_services_test.py      # Test runner
│   ├── health_check.py                  # Health check entry point
│   ├── health_check_gateway.py          # Gateway check
│   ├── health_check_tailscale.py        # Tailscale Funnel check
│   ├── health_alert.py                  # MQTT state publishing
│   └── _crypto.py, _env.py              # Shared helpers
├── tests/                   # Offline pytest suite
├── scripts/                 # systemd install/uninstall + units
├── sdk/kotlin/              # Kotlin client library (athena-gateway-client)
├── docs/                    # Architecture diagram, design notes
├── .github/workflows/ci.yml # Tests and coverage gate
├── .env.example             # Settings for test clients and health checks
├── docker-compose.yml       # Docker Compose configuration
├── Dockerfile               # Container image definition
├── requirements.txt         # Server dependencies
├── requirements-dev.txt     # Test dependencies
├── pytest.ini
├── jitpack.yml              # JitPack build for the Kotlin SDK
├── run_tailscale.sh         # Tailscale Funnel helper script
├── update.sh
├── LICENSE
└── README.md
```

## API Endpoints

### POST /gateway

Gateway endpoint for encrypted HTTP requests to any API.

- **Content-Type**: `application/octet-stream`
- **Body**: Base64-encoded encrypted payload
- **Response**: Base64-encoded encrypted response

**Payload Fields:**
- `url`: Target HTTP server URL (required if `host` not provided)
- `host`: Target server host (required if `url` not provided, e.g., "http://example.com:8080")
- `endpoint`: API endpoint path (optional, defaults to "/", e.g., "/api/v1/data")
- `method`: HTTP method (GET, POST, PUT, DELETE, etc.), defaults to "POST"
- `body`: Request body (dict for JSON or string for raw data)
- `headers`: (Optional) HTTP headers dict, defaults to `{"Content-Type": "application/json"}`
- `timeout`: (Optional) Request timeout in seconds, defaults to 30
- `raw`: (Optional, v1.3+) `true` to get the upstream body as exact bytes, e.g. a JPEG snapshot or MP4 clip (see below)
- `timestamp`: Current Unix timestamp (required for replay protection)

**Response headers (v1.4+):** non-raw answers also carry `"headers"`: the upstream response headers with lower-case names; several `Set-Cookie` lines are joined with `\n`. The Kotlin SDK exposes them as `GatewayResponse.headers` (empty from an older gateway).

**Raw responses (`"raw": true`):**
By default `body` is the upstream JSON or text, which corrupts binary content. With `raw` the gateway streams the upstream body and answers:

```json
{"status": 200, "body": null, "body_b64": "<base64 of the exact bytes>", "content_type": "image/jpeg", "timestamp": 1234567890}
```

`content_type` is the upstream `Content-Type` header, or `null`. The body is capped at `HTTP_MAX_RESPONSE_BYTES` (25 MiB by default); when the upstream `Content-Length` or the bytes read exceed it, the gateway answers an encrypted `500` error with `response too large`. Without `raw` the behaviour is unchanged.

**Usage:**
This endpoint acts as a generic encrypted HTTP proxy. You can forward any HTTP request through it with end-to-end encryption. The gateway will decrypt your request, forward it to the target URL, and return the encrypted response.

You can specify the target in two ways:
1. **Complete URL**: Use the `url` parameter with the full URL
2. **Host + Endpoint**: Use `host` (e.g., "http://api.example.com") and `endpoint` (e.g., "/api/data") - they will be combined into a complete URL

### POST /mqtt/publish

Encrypted MQTT publish endpoint for publishing messages to MQTT brokers.

- **Content-Type**: `application/octet-stream`
- **Body**: Base64-encoded encrypted payload with MQTT publish details
- **Response**: Base64-encoded encrypted response

**Payload Fields:**
- `topic`: MQTT topic to publish to (required)
- `message`: Message payload to publish (required)
- `qos`: (Optional) Quality of Service (0-2), defaults to 0
- `retain`: (Optional) Retain flag, defaults to false
- `broker_host`: (Optional) MQTT broker hostname
- `broker_port`: (Optional) MQTT broker port
- `username`: (Optional) MQTT username
- `password`: (Optional) MQTT password
- `timestamp`: Current Unix timestamp (required for replay protection)

**Usage:**
This endpoint allows you to publish messages to MQTT topics through the encrypted gateway. Messages are published once and the response confirms success or failure.

### POST /mqtt/subscribe

Server-Sent Events endpoint for subscribing to MQTT topics in real-time.

- **Content-Type**: `application/octet-stream`
- **Body**: Base64-encoded encrypted payload with subscription details
- **Response**: `text/event-stream` with encrypted SSE messages
- **Headers**:
  - `Cache-Control: no-cache`
  - `X-Accel-Buffering: no`
  - `Connection: keep-alive`

**Usage:**
This endpoint establishes a persistent HTTP connection and streams MQTT messages as they arrive. Each SSE event contains an encrypted message that must be decrypted by the client. The stream remains open until:
- An error occurs
- The MQTT connection is lost
- The client closes the connection

### GET /health

Health check endpoint.

- **Response**: `{"status": "ok", "version": "1.4.0"}`

## Dependencies

### Server Dependencies

- **Flask 3.0.0**: Web framework
- **cryptography 41.0.7**: AES-GCM encryption
- **requests 2.31.0**: HTTP client for proxying requests
- **paho-mqtt 1.6.1**: MQTT client library
- **python-dotenv 1.0.0**: Environment variable management

### Client Dependencies

- **sseclient-py 1.8.0**: SSE client support (for MQTT subscriptions)

## Development

### Running in Development Mode

```bash
# Activate virtual environment
source .venv/bin/activate

# Run the server (no auto-reload: restart it after code changes)
python -m server
```

### Building Docker Image

```bash
docker build -t encryption-gateway:latest .
```

### Code Organization

- **Modular Design**: Separate services for different protocols
- **Type Hints**: Type annotations on the public functions
- **Error Handling**: Errors are returned to the client as encrypted responses
- **Clean Architecture**: Clear separation of concerns

## Troubleshooting

### Connection Refused

Ensure the server is running and accessible:
```bash
curl http://localhost:10000/health
```

### Decryption Failed

- Verify the secret key matches between client and server
- Check that the key file hasn't been corrupted
- Ensure timestamp is within the valid window

### MQTT Connection Failed

- Verify MQTT broker is running and accessible
- Check broker hostname and port configuration
- Validate MQTT credentials if authentication is enabled

### Docker Volume Permissions

The container runs as root, so it can always write `./data`. If you then can't edit `data/secret_key.txt` from the host, take ownership back:
```bash
sudo chown -R "$(id -u):$(id -g)" ./data
```

## License

[MIT](LICENSE) © Gekkotron

## Contributing

Contributions are welcome! Areas for improvement:

- Additional service handlers (Home Assistant, InfluxDB, etc.)
- Automated key rotation
- Rate limiting and DDoS protection
- Prometheus metrics endpoint

## Acknowledgments

Built with:
- Flask for the web framework
- Cryptography library for robust encryption
- Paho MQTT for MQTT support
- Docker for containerization

## Support

For issues, questions, or contributions, please [open an issue](https://github.com/Gekkotron/Nidoo-gateway/issues).
