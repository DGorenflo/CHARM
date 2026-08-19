# CHARM – Ontology Matcher

CHARM is a Java-based ontology matching service that finds correspondences between two ontologies. It exposes the standard MELT HTTP API: you `POST` two ontologies to `/match` and get the alignment back synchronously in the response.

## Prerequisites

- Java 11+
- Maven 3.9.2+
- Docker (optional, for containerized setup)

For LLM-based matchers you also need a **local AI server** running on the same machine that
exposes the `/chat` and `/embed` endpoints described in [LLM_ENDPOINTS.md](LLM_ENDPOINTS.md).
Point CHARM at it via the `LLM_BASE_URL` environment variable.

---

## Option A: Run with Docker (recommended)

### 1. Build the image

```bash
docker build -t charm-matcher .
```

### 2. Configure environment variables

Copy `example.env` to `.env` and fill in your values:

```bash
cp example.env .env
```

Available variables:

| Variable | Default | Description |
|---|---|---|
| `MELT_PORT` | `8080` | Port the server listens on |
| `MELT_MAX_REQUESTS` | `1` | Max parallel match requests (usually kept at 1 due to memory constraints) |
| `MELT_LOCATION` | system temp | Directory for temporary upload files |
| `LOGGING_LEVEL_ROOT` | `INFO` | Log level (`DEBUG`, `INFO`, `WARN`, `ERROR`) |
| `LLM_BASE_URL` | `http://localhost:8000` | Base URL of the local AI server (see [LLM_ENDPOINTS.md](LLM_ENDPOINTS.md)) |
| `LLM_API_KEY` | *(empty)* | Optional bearer token for the local AI server; sent as `Authorization: Bearer <key>` when set |

### 3. Start the container

```bash
docker run --env-file .env -p 8080:8080 charm-matcher
```

The server is ready when you see a log line like `Matching service runs at: http://localhost:8080/match`.

---

## Option B: Run locally with Maven

### 1. Build

```bash
cd app
mvn clean package -DskipTests
```

The fat JAR is created at `app/target/CHARM-1.0.jar`.

### 2. Run

```bash
java -Xmx8g -Djdk.xml.maxElementDepth=0 \
  -cp "target/CHARM-1.0.jar:target/dependency/*" \
  de.uni_mannheim.informatik.dws.melt.receiver_http.Main
```

> **Note:** Production deployments use `-Xmx40g`. For local testing, adjust to your available RAM.

---

## API Usage

The matcher uses the standard MELT `receiver-http` server: `POST /match` runs the matcher
**synchronously** and returns the resulting alignment directly in the response body. There are no
jobs and no polling.

### Run a match

```bash
curl -X POST http://localhost:8080/match \
  -F "source=@/path/to/source.owl" \
  -F "target=@/path/to/target.owl" \
  -o alignment.rdf
```

