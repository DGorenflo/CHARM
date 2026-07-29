# CHARM – Ontology Matcher

CHARM is a Java-based ontology matching service that finds correspondences between two ontologies. It exposes an asynchronous HTTP API: you submit a job and poll for the result.

## Prerequisites

- Java 11+
- Maven 3.9.2+
- Docker (optional, for containerized setup)

For LLM-based matchers you also need access to a compatible AI endpoint (SAP AI Core / OpenAI-compatible).

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
| `MELT_MAX_REQUESTS` | `1` | Max parallel jobs (usually kept at 1 due to memory constraints) |
| `MELT_LOCATION` | system temp | Directory for temporary upload files |
| `LOGGING_LEVEL_ROOT` | `INFO` | Log level (`DEBUG`, `INFO`, `WARN`, `ERROR`) |

### 3. Start the container

```bash
docker run --env-file .env -p 8080:8080 charm-matcher
```

The server is ready when you see a log line like `Started server on port 8080`.

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

The matcher always runs asynchronously. The flow is:

```
POST /match  →  { jobId }
     ↓
GET /result/{jobId}   (poll until HTTP 200)
     ↓
GET /tokens/{jobId}   (optional: LLM token usage)
```

### Submit a job

```bash
curl -X POST http://localhost:8080/match \
  -F "source=@/path/to/source.owl" \
  -F "target=@/path/to/target.owl"
```

Response (HTTP 202):
```json
{ "jobId": "550e8400-e29b-41d4-a716-446655440000" }
```

You can also pass an existing alignment to refine (`-F "inputAlignment=@alignment.rdf"`) and matcher parameters (`-F "parameters=@params.txt"`).

### Poll for the result

```bash
curl http://localhost:8080/result/{jobId}
```

| Status | Meaning |
|---|---|
| `202` | Job is still running |
| `200` | Done – response body contains the alignment (RDF) |
| `500` | Job failed – response body contains the error message |

### Get token usage (optional)

```bash
curl http://localhost:8080/tokens/{jobId}
```

Returns a CSV with LLM token usage after the job has completed.

---

## Notes

- Only one matching job runs at a time. Additional requests are queued.
- Temporary files are automatically deleted after the job finishes.
- Ontology files must be in a format supported by Apache Jena (OWL, RDF/XML, Turtle, etc.).
