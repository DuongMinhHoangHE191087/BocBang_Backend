# BocBang Backend API Documentation

Documentation for all REST and WebSocket API endpoints used to communicate with the Spring Boot STT (Speech-to-Text) backend server.

---

## 1. Base URL & API Versioning

* **Base Server URL**: `http://<host>:<port>` (Default port: `8099` or `8080`)
* **Current API Base Path**: `/api/v1/stt`
* **Legacy API Base Path**: `/api/stt` *(Deprecated: returns RFC 9745 `Deprecation` header and `Link: </api/v1/stt/...>; rel="successor-version"`)*

---

## 2. Authentication & Credentials

Authentication is controlled by the server configuration (`stt.security.api-keys`).

### API Key Mechanisms
If enabled on the server, requests to `/api/**` and `/ws/**` require a valid API key sent in **one** of the following ways:

| Format | Syntax | Recommended Use |
|---|---|---|
| **Header (Bearer)** | `Authorization: Bearer <YOUR_API_KEY>` | HTTP REST & Client SDKs |
| **Header (Custom)** | `X-Api-Key: <YOUR_API_KEY>` | HTTP REST |
| **Query Parameter** | `?apiKey=<YOUR_API_KEY>` | WebSocket (`/ws/stt?apiKey=...`) |

### Exempt Endpoints (No Auth Required)
* `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`
* `/index.html`, `/monitor.html` (Demo UI static files)
* *(Note: `/actuator/metrics` and `/actuator/prometheus` require an API key only if `stt.security.protect-actuator=true`)*

---

## 3. HTTP REST Endpoints (`/api/v1/stt`)

### 3.1 Get Server Status & Capacity

Check engine models, configurations, and current server load before connecting.

* **Method / Path**: `GET /api/v1/stt/status`
* **Headers**: `Authorization: Bearer <key>` *(if enabled)*
* **Response `200 OK`**:
  ```json
  {
    "engine": "jni",
    "defaultModel": "qwen3-asr",
    "models": [
      "qwen3-asr",
      "whisper-en"
    ],
    "refinerEnabled": true,
    "sampleRate": 16000,
    "maxUtteranceSeconds": 60,
    "websocketPath": "/ws/stt",
    "jobsPath": "/api/v1/stt/jobs",
    "capacity": {
      "workers": 4,
      "maxSessions": 4,
      "activeSessions": 1,
      "queuedTasks": 0
    },
    "ready": true
  }
  ```

---

### 3.2 Synchronous Short Audio Transcription

Synchronously transcribe short PCM WAV clips (up to `maxUtteranceSeconds`, default 60s). Must be 16 kHz 16-bit mono WAV.

* **Method / Path**: `POST /api/v1/stt/transcriptions`
* **Content-Type**: `multipart/form-data`
* **Form Parameters**:
  * `file`: Audio file (Required, `.wav` 16 kHz 16-bit mono)
* **Response `200 OK`**:
  ```json
  {
    "text": "Xin chào đây là bài thử nghiệm",
    "sampleRate": 16000,
    "durationMs": 3200
  }
  ```
* **Example `curl`**:
  ```bash
  curl -X POST "http://localhost:8099/api/v1/stt/transcriptions" \
    -H "Authorization: Bearer my-secret-key" \
    -F "file=@short_sample.wav"
  ```

---

### 3.3 Submit Asynchronous Long Audio Job

Queue long audio files (WAV, MP3, M4A, OGG, FLAC, etc.) for background processing. Automatically converts audio via FFmpeg, detects speech with Silero VAD, transcribes with ASR, and refines text with LLM.

* **Method / Path**: `POST /api/v1/stt/jobs`
* **Content-Type**: `multipart/form-data`
* **Form Parameters**:
  * `file`: Audio file (Required, up to 500 MB)
* **Query Parameters (Optional)**:
  * `model`: Model name (e.g., `qwen3-asr` or `whisper-en`)
  * `refine`: Boolean (`true` / `false`) - enable LLM text refinement (punctuation/fix-up)
* **Response `202 Accepted`**:
  * **Headers**: `Location: /api/v1/stt/jobs/{jobId}`
  * **Body**: `TranscriptionJob` object
  ```json
  {
    "jobId": "c8f1e290-6712-4a0b-891d-9171f114a849",
    "model": "qwen3-asr",
    "refine": true,
    "status": "RUNNING",
    "stage": "CONVERTING",
    "progress": 0.1,
    "durationSeconds": null,
    "completedSegments": 0,
    "totalSegments": 0,
    "rawText": null,
    "text": null,
    "segments": [],
    "error": null,
    "createdAt": "2026-11-03T07:20:00Z",
    "updatedAt": "2026-11-03T07:20:01Z"
  }
  ```
* **Example `curl`**:
  ```bash
  curl -i -X POST "http://localhost:8099/api/v1/stt/jobs?model=qwen3-asr&refine=true" \
    -H "Authorization: Bearer my-secret-key" \
    -F "file=@long_meeting.mp3"
  ```

---

### 3.4 Get Job Status & Result

Poll job status until `status` is `DONE`, `FAILED`, or `CANCELLED`.

* **Method / Path**: `GET /api/v1/stt/jobs/{jobId}`
* **Response `200 OK`**:
  ```json
  {
    "jobId": "c8f1e290-6712-4a0b-891d-9171f114a849",
    "model": "qwen3-asr",
    "refine": true,
    "status": "DONE",
    "stage": "DONE",
    "progress": 1.0,
    "durationSeconds": 125.4,
    "completedSegments": 6,
    "totalSegments": 6,
    "rawText": "xin chao moi nguoi hom nay chung ta hop ban ve du an",
    "text": "Xin chào mọi người. Hôm nay chúng ta họp bàn về dự án.",
    "segments": [
      {
        "index": 0,
        "start": 0.5,
        "end": 4.2,
        "text": "Xin chào mọi người."
      },
      {
        "index": 1,
        "start": 5.0,
        "end": 9.8,
        "text": "Hôm nay chúng ta họp bàn về dự án."
      }
    ],
    "error": null,
    "createdAt": "2026-11-03T07:20:00Z",
    "updatedAt": "2026-11-03T07:20:30Z"
  }
  ```
* **Response `404 Not Found`**: Returned if the `jobId` does not exist or expired (jobs TTL is 24h).

---

### 3.5 Cancel & Delete Job

Cancels a running job immediately (triggers atomic C++ abort flag) and removes temporary audio files.

* **Method / Path**: `DELETE /api/v1/stt/jobs/{jobId}`
* **Response**: `204 No Content` (Success) or `404 Not Found`

---

## 4. Realtime Streaming over WebSocket (`/ws/stt`)

Provides full-duplex low-latency speech recognition.

* **URL**: `ws://<host>:<port>/ws/stt` (or `wss://...`)
* **Handshake Query Parameters**:
  * `sampleRate`: Integer (Optional, default: `16000`. Must match model sample rate).
  * `apiKey`: String (Optional, if authentication is enabled and headers cannot be set by client).

### Handshake Headers (Preferred for Auth)
* `Authorization: Bearer <YOUR_API_KEY>` or `X-Api-Key: <YOUR_API_KEY>`

---

### 4.1 Client-to-Server Protocol

#### 1. Binary Frames (Audio Payload)
* **Format**: Raw 16-bit little-endian signed mono PCM (`pcm_s16le`), 16 kHz.
* **Frame Requirements**: Must contain an **even** number of bytes (`pcm.length % 2 == 0`).
* **Frame Chunk Size**: Recommended ~100ms per frame (3,200 bytes). Max frame size is `65536` bytes.

#### 2. Text Frames (Control Commands)
* **Finish Utterance Command**:
  Send JSON to signal the end of the current speaking segment:
  ```json
  {"type": "end"}
  ```

---

### 4.2 Server-to-Client Protocol (JSON Messages)

#### 1. `ready` (Sent upon successful connection handshake)
```json
{
  "type": "ready",
  "sampleRate": 16000,
  "encoding": "pcm_s16le",
  "channels": 1,
  "maxFrameBytes": 65536
}
```

#### 2. `partial` (Interim transcript - streaming engines)
```json
{
  "type": "partial",
  "text": "xin chào",
  "lagMs": 45
}
```

#### 3. `final` (Finalized transcript after `{"type":"end"}`)
```json
{
  "type": "final",
  "text": "Xin chào các bạn",
  "lagMs": 120
}
```

#### 4. `error` (Application level error frame)
```json
{
  "type": "error",
  "code": "capacity_exceeded",
  "message": "Server is at capacity, retry shortly"
}
```

### WebSocket Error Codes & Server Actions

| Error `code` | Scenario | Server Action |
|---|---|---|
| `invalid_sample_rate` | Requested `sampleRate` does not match model rate (16000 Hz) | Connection closed (Close Code `1008`) |
| `capacity_exceeded` | Max session capacity reached on server | Connection closed (Close Code `1013`) |
| `overloaded` | Audio streaming buffer overflow (> `max-buffered-audio`) | Connection closed (Close Code `1013`) |
| `invalid_frame` | Odd number of bytes in binary PCM frame | Error sent, connection kept open |
| `invalid_command` | Malformed JSON or unknown command | Error sent, connection kept open |
| `invalid_audio` | Audio utterance exceeds duration limit | Error sent, connection kept open |
| `recognition_failed` | Native model inference failed for current utterance | Error sent, utterance skipped, connection kept open |
| `engine_failure` | Critical native crash/failure | Connection closed (Close Code `1011`) |

---

## 5. Actuator & Health Endpoints

Used by load balancers, Kubernetes / Docker health checks, and Prometheus scrapers.

| Path | Method | Auth Required | Description |
|---|---|---|---|
| `/actuator/health` | GET | No | Overall service health (`UP` or `DOWN`) |
| `/actuator/health/liveness` | GET | No | Liveness probe (Always `200 OK` while JVM is alive) |
| `/actuator/health/readiness` | GET | No | Readiness probe (Returns `200 OK` when ready, `503 Service Unavailable` with status `BUSY` when server capacity is full) |
| `/actuator/metrics` | GET | Optional* | JVM & custom application metrics |
| `/actuator/prometheus` | GET | Optional* | Prometheus scrape endpoint |

*\* If `stt.security.protect-actuator=true` and API keys are set, metrics endpoints require an API key.*

---

## 6. RFC 9457 Error Response Format

HTTP API errors are returned as `application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Server at capacity",
  "status": 503,
  "detail": "Server is currently full, please try again",
  "instance": "/api/v1/stt/transcriptions"
}
```

* **HTTP `400 Bad Request`**: Empty file, invalid parameter, sample rate mismatch.
* **HTTP `401 Unauthorized`**: Missing or invalid API key (`WWW-Authenticate: Bearer realm="bocbang"`).
* **HTTP `503 Service Unavailable`**: Maximum session/inference capacity reached. Includes HTTP header `Retry-After: 1`.
