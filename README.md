# SignalCend HiveMQ Extension

Multi-signal device state arbitration for HiveMQ — automatic, non-blocking,
zero custom code required after installation.

## What It Does

This extension intercepts every inbound MQTT PUBLISH packet and routes it
through SignalCend's arbitration API before delivery to downstream subscribers.
The arbitration evaluates five signals simultaneously:

1. **Timestamp confidence** — is the device clock trustworthy?
2. **RF signal quality** — is the transmission environment clean?
3. **Race condition detection** — did this offline event arrive after a reconnect?
4. **Sequence continuity** — is this event in the right causal order?
5. **Confidence floor enforcement** — always returns a verdict, never silence

Every message that passes through the extension carries three additional
MQTT 5 user properties that downstream consumers can branch on directly:

| Property | Values | Meaning |
|---|---|---|
| `X-SignalCend-Action` | `ACT` / `CONFIRM` / `LOG_ONLY` | What to do with this state |
| `X-SignalCend-Confidence` | `0.20` – `1.0` | Evidence quality score |
| `X-SignalCend-Authoritative-Status` | `online` / `offline` / etc. | Arbitrated ground truth |

The extension also publishes every arbitration verdict to a dedicated topic:
```
signalcend/arbitrated/{original_topic}
```
This allows device twin systems, historians, and shadow services to subscribe
to authoritative state separately from the raw event stream.

---

## Requirements

- HiveMQ 4.x or later (Community or Enterprise)
- Java 11 or higher (matches HiveMQ's runtime)
- A SignalCend API key and secret (free tier at signalcend.com)

---

## Installation — 4 Steps

### Step 1 — Build the extension JAR

```bash
mvn clean package
```

This produces `target/signalcend-hivemq-extension-1.0.0.jar` with all
dependencies shaded in.

### Step 2 — Create the extension folder

```bash
mkdir -p {HIVEMQ_HOME}/extensions/signalcend-hivemq-extension
```

### Step 3 — Copy files

```bash
# The shaded JAR
cp target/signalcend-hivemq-extension-1.0.0.jar \
   {HIVEMQ_HOME}/extensions/signalcend-hivemq-extension/

# The HiveMQ extension descriptor
cp src/main/resources/hivemq-extension.xml \
   {HIVEMQ_HOME}/extensions/signalcend-hivemq-extension/

# The configuration template — rename and fill in your credentials
cp signalcend.properties.template \
   {HIVEMQ_HOME}/extensions/signalcend-hivemq-extension/signalcend.properties
```

### Step 4 — Configure credentials

Edit `signalcend.properties` and set the two required values:

```properties
signalcend.api.key=your-api-key-here
signalcend.api.secret=your-api-secret-here
```

HiveMQ supports live extension hot-deployment — no broker restart required.
Drop the files into the extensions folder and HiveMQ loads the extension
automatically within seconds.

---

## Configuration Reference

| Property | Required | Default | Description |
|---|---|---|---|
| `signalcend.api.key` | ✅ | — | Your SignalCend API key |
| `signalcend.api.secret` | ✅ | — | Your SignalCend API secret |
| `signalcend.api.base.url` | | `https://api.signalcend.com/v1` | API endpoint |
| `signalcend.topic.filter` | | `#` | Topics to arbitrate (comma-separated) |
| `signalcend.status.field` | | `status` | Payload field for device status |
| `signalcend.device.id.field` | | `device_id` | Payload field for device ID |
| `signalcend.timestamp.field` | | `timestamp` | Payload field for event timestamp |
| `signalcend.signal.strength.field` | | `signal_strength` | Payload field for RSSI |
| `signalcend.sequence.field` | | `sequence` | Payload field for sequence number |
| `signalcend.reconnect.window.seconds` | | `30` | Race condition detection window (max 600) |
| `signalcend.http.timeout.ms` | | `5000` | API call timeout in milliseconds |
| `signalcend.act.on.confirm` | | `true` | Pass through CONFIRM verdicts |
| `signalcend.block.on.log.only` | | `false` | Drop LOG_ONLY messages |
| `signalcend.session.id.prefix` | | `hivemq` | Session ID prefix |

---

## Topic Filtering

By default the extension arbitrates all topics (`#`). To arbitrate only
specific topic trees:

```properties
# Only arbitrate device and sensor topics
signalcend.topic.filter=devices/#,sensors/#,fleet/#

# Industrial deployment — only production line topics
signalcend.topic.filter=plant/line-1/#,plant/line-2/#,plant/utilities/#
```

Topics not matching the filter pass through without any API call.

---

## Custom Field Mapping

If your payloads use non-standard field names, map them in the config:

```properties
# Your payload uses "node_id" instead of "device_id"
signalcend.device.id.field=node_id

# Your payload uses "state" instead of "status"
signalcend.status.field=state

# Your payload uses "rssi" instead of "signal_strength"
signalcend.signal.strength.field=rssi

# Your payload uses "ts" instead of "timestamp"
signalcend.timestamp.field=ts
```

The extension also detects common field name aliases automatically
(id, device, clientId, state, value, rssi, snr, seq, ts, time).

---

## Downstream Consumer Integration

Subscribers receive the arbitrated message with SignalCend user properties
attached. Branch your application logic on the action enum:

### Python subscriber example
```python
def on_message(client, userdata, msg):
    props = {p.name: p.value for p in msg.properties.UserProperty}
    action     = props.get("X-SignalCend-Action", "ACT")
    confidence = float(props.get("X-SignalCend-Confidence", "1.0"))
    auth_status = props.get("X-SignalCend-Authoritative-Status", "")

    if action == "ACT":
        update_device_state(auth_status)
        trigger_automations(auth_status)
    elif action == "CONFIRM":
        queue_for_verification(auth_status, confidence)
    elif action == "LOG_ONLY":
        audit_log(auth_status, confidence)
        # Do not trigger automations
```

### Subscribing to arbitrated verdicts only
```python
# Subscribe to the dedicated arbitration verdict topic
# instead of the raw device topic
client.subscribe("signalcend/arbitrated/devices/#")
```

---

## Behavior on API Failure

The extension is designed to fail open. If the SignalCend API is
unreachable or returns an error:

- The original message is passed through unmodified
- The failure is logged at WARN level
- No messages are dropped due to API unavailability
- Normal broker operation continues

This means the extension is safe to install without risk of message loss
during network interruptions or API maintenance windows.

---

## Security Notes

- Store `signalcend.api.secret` as an environment variable in production
  rather than in the properties file if your deployment policy requires it
- The extension uses HTTPS for all API calls
- API secrets are never logged at any log level
- The HMAC signing computation falls back to local computation if the
  `/v1/sign` endpoint is unavailable, maintaining security at all times

---

## Resolution Volume Estimation

Every MQTT PUBLISH on a filtered topic consumes one SignalCend resolution.
Estimate your monthly volume:

```
Monthly resolutions = devices × state_transitions_per_day × 30
```

A 300-device smart building averaging 1 state transition per device per day:
`300 × 1 × 30 = 9,000 resolutions/month` — well within the free tier.

A 4,000-vehicle fleet averaging 25 transitions per vehicle per day:
`4,000 × 25 × 30 = 3,000,000 resolutions/month` — enterprise tier.

Use `signalcend.topic.filter` to arbitrate only state-relevant topics
and reduce resolution consumption on pure telemetry streams.

---

## Support

Documentation: signalcend.com
API reference: signalcend.com/#api-reference
Free API key: signalcend.com (1,000 resolutions, no card required)
Support: support@signalcend.com
