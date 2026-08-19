# Local AI Endpoints

CHARM calls LLM (chat) and embedding models through a **local HTTP server**. That server must
provide two simple JSON endpoints: `POST /chat` and `POST /embed`.

This document is the binding contract. Whoever implements the local server (e.g. as a thin
adapter in front of Ollama, vLLM, llama.cpp, a custom Python service, etc.) must provide exactly
these endpoints and request/response formats.

## Configuration

The matcher reads the connection details once from environment variables:

| Variable | Default | Description |
|---|---|---|
| `LLM_BASE_URL` | `http://localhost:8000` | Base URL of the local server. The endpoints are addressed as `{LLM_BASE_URL}/chat` and `{LLM_BASE_URL}/embed`. An optional trailing `/` is ignored. |
| `LLM_API_KEY` | *(empty)* | Optional. If set, every request is sent with the header `Authorization: Bearer <LLM_API_KEY>`. Many local servers ignore it – then just leave it empty. |

Example `.env`:

```
LLM_BASE_URL=http://localhost:8000
LLM_API_KEY=
```

## General

- Transport: HTTP/1.1, `Content-Type: application/json`, UTF-8.
- The `model` value is passed through unchanged from the matcher configuration
  (`CHARM_Matcher.java`, constants such as `LLM_MODEL`, `LLM_EMBEDDING_MODEL`).
  The local server decides itself which concrete model it maps a name to.
- The client retries a failed request (network error or status ≠ 2xx) up to 3 times with a short
  backoff (1s, 2s) and then throws. Callers additionally have their own batch-retry and
  fail-open logic.
- Time budget: the client allows up to 5 minutes per request.

---

## `POST {LLM_BASE_URL}/chat`

A single chat/completion call. One prompt in, one text out.

### Request

```json
{
  "model": "mistralai--mistral-small",
  "prompt": "<full prompt text>",
  "temperature": 0
}
```

| Field | Type | Description |
|---|---|---|
| `model` | string | Model name configured by the matcher. |
| `prompt` | string | The complete prompt (already fully assembled). |
| `temperature` | number | Sampling temperature. CHARM always sends `0` (deterministic output desired). |

### Response `200 OK`

```json
{
  "text": "<full model response as a string>"
}
```

| Field | Type | Description |
|---|---|---|
| `text` | string | The generated text. Required field – if missing, the client throws an error. |

### Example

```bash
curl -X POST http://localhost:8000/chat \
  -H "Content-Type: application/json" \
  -d '{"model":"mistralai--mistral-small","prompt":"Answer only with OK.","temperature":0}'
# -> {"text":"OK"}
```

---

## `POST {LLM_BASE_URL}/embed`

Produces embedding vectors for a batch of texts. CHARM sends batches of up to
50 texts (`DogTagConfig.embeddingBatchSize`).

### Request

```json
{
  "model": "text-embedding-3-large",
  "input": ["text 1", "text 2", "text 3"]
}
```

| Field | Type | Description |
|---|---|---|
| `model` | string | Embedding model name configured by the matcher. |
| `input` | string[] | List of texts to embed. |

### Response `200 OK`

```json
{
  "vectors": [
    [0.0123, -0.0456, 0.0789, "..."],
    [0.0011, -0.0022, 0.0033, "..."],
    [0.0044, -0.0055, 0.0066, "..."]
  ]
}
```

| Field | Type | Description |
|---|---|---|
| `vectors` | number[][] | One vector per input text, **in exactly the same order** as `input`. |

Requirements:

- `vectors.length` **must** equal `input.length` (otherwise the client errors).
- All vectors **must have the same dimension**. The dimension is freely choosable, is derived at
  runtime from the first vector, and is used for the similarity search (cosine/HNSW).
- Values are floating-point numbers (read as `float` on the client side).

### Example

```bash
curl -X POST http://localhost:8000/embed \
  -H "Content-Type: application/json" \
  -d '{"model":"text-embedding-3-large","input":["hello world","foo bar"]}'
# -> {"vectors":[[0.01, -0.04, ...],[0.02, 0.05, ...]]}
```

---

## Error behavior

- Any HTTP status outside `2xx` counts as an error; the client retries the request
  (see "General") and then throws an exception.
- The response must be valid JSON containing the respective required field (`text` or
  `vectors`). If it is missing or has the wrong type, the client throws.
- On persistent errors the higher-level logic kicks in: verification calls are "fail-open"
  (the match is kept), matching batches return "no match" when in doubt.

---

## Bundled servers

A reference implementation of the endpoints is included:

### Mock server

[mock_ai_server.py](mock_ai_server.py) returns **deterministic but meaningless**
responses (chat = canned text, embeddings = SHA-256-derived, L2-normalized). For checking that
CHARM reaches the endpoints and parses the responses, **not** for match quality.

```bash
python3 mock_ai_server.py            # listens on 0.0.0.0:8000
# or: python3 mock_ai_server.py --port 9000 --embed-dim 32
```
