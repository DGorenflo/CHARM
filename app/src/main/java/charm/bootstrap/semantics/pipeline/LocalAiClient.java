package charm.bootstrap.semantics.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Thin HTTP client for a locally hosted LLM / embedding server.
 *
 * <p>Replaces the former SAP AI Core integration. Instead of talking to a managed cloud
 * service, the matcher calls a plain HTTP server that runs on the same machine and exposes
 * two minimal, self-defined JSON endpoints:
 *
 * <pre>
 *   POST {baseUrl}/chat     { "model": "...", "prompt": "...", "temperature": 0 }
 *                       ->  { "text": "..." }
 *
 *   POST {baseUrl}/embed    { "model": "...", "input": ["...", "..."] }
 *                       ->  { "vectors": [[...], [...]] }
 * </pre>
 *
 * The full contract is documented in {@code LLM_ENDPOINTS.md} at the repository root.
 *
 * <p>Configuration is read once from the environment:
 * <ul>
 *   <li>{@code LLM_BASE_URL} — base URL of the local server (default {@code http://localhost:8000}).</li>
 *   <li>{@code LLM_API_KEY}  — optional; sent as {@code Authorization: Bearer <key>} when set.</li>
 * </ul>
 */
public final class LocalAiClient {

    private static final Logger LOG = Logger.getLogger(LocalAiClient.class.getName());

    private static final String DEFAULT_BASE_URL = "http://localhost:8000";
    private static final int    MAX_ATTEMPTS     = 3;
    private static final long   RETRY_BASE_MS    = 1000;

    private static final String  BASE_URL = resolveBaseUrl();
    private static final String  API_KEY  = System.getenv("LLM_API_KEY");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient   HTTP   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private LocalAiClient() {}

    private static String resolveBaseUrl() {
        String url = System.getenv("LLM_BASE_URL");
        if (url == null || url.isBlank()) url = DEFAULT_BASE_URL;
        // Strip a single trailing slash so endpoint concatenation stays clean.
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    /** Sends a chat/completion request and returns the model's answer text. */
    public static String chat(String model, String prompt) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("temperature", BigDecimal.ZERO);

        JsonNode response = post("/chat", body);
        JsonNode text = response.get("text");
        if (text == null || text.isNull()) {
            throw new RuntimeException("Local AI /chat response missing 'text' field: " + response);
        }
        return text.asText();
    }

    /**
     * Sends an embedding request for a batch of texts and returns one vector per input,
     * in the same order as {@code input}.
     */
    public static List<float[]> embed(String model, List<String> input) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        ArrayNode inputs = body.putArray("input");
        for (String t : input) inputs.add(t);

        JsonNode response = post("/embed", body);
        JsonNode vectors = response.get("vectors");
        if (vectors == null || !vectors.isArray()) {
            throw new RuntimeException("Local AI /embed response missing 'vectors' array: " + response);
        }
        if (vectors.size() != input.size()) {
            throw new RuntimeException("Local AI /embed returned " + vectors.size()
                    + " vectors for " + input.size() + " inputs");
        }
        List<float[]> result = new ArrayList<>(vectors.size());
        for (JsonNode vec : vectors) {
            if (!vec.isArray()) {
                throw new RuntimeException("Local AI /embed 'vectors' element is not an array: " + vec);
            }
            float[] arr = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) arr[i] = (float) vec.get(i).asDouble();
            result.add(arr);
        }
        return result;
    }

    /** POSTs a JSON body to {@code path}, retries on transport/non-2xx errors, returns parsed JSON. */
    private static JsonNode post(String path, ObjectNode body) {
        String payload;
        try {
            payload = MAPPER.writeValueAsString(body);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize request body", e);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (API_KEY != null && !API_KEY.isBlank()) {
            builder.header("Authorization", "Bearer " + API_KEY);
        }
        HttpRequest request = builder.build();

        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return MAPPER.readTree(response.body());
                }
                last = new RuntimeException("Local AI " + path + " returned HTTP " + status
                        + ": " + truncate(response.body()));
            } catch (IOException e) {
                last = new RuntimeException("Local AI " + path + " request failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during Local AI " + path + " request", e);
            }

            if (attempt < MAX_ATTEMPTS) {
                long sleep = RETRY_BASE_MS * (1L << (attempt - 1)); // 1s, 2s, ...
                LOG.warning(last.getMessage() + " — retrying (" + attempt + "/" + (MAX_ATTEMPTS - 1) + ")");
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
