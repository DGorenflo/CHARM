package charm.bootstrap.semantics.pipeline;

import com.sap.ai.sdk.foundationmodels.openai.OpenAiClient;
import com.sap.ai.sdk.foundationmodels.openai.OpenAiChatCompletionRequest;
import com.sap.ai.sdk.foundationmodels.openai.OpenAiModel;
import com.sap.ai.sdk.orchestration.OrchestrationAiModel;
import com.sap.ai.sdk.orchestration.OrchestrationClient;
import com.sap.ai.sdk.orchestration.OrchestrationModuleConfig;
import com.sap.ai.sdk.orchestration.OrchestrationPrompt;

import java.math.BigDecimal;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.jena.ontology.OntResource;

import charm.bootstrap.semantics.model.Candidate;
import charm.bootstrap.semantics.model.DogTagConfig;
import charm.bootstrap.semantics.model.EmbeddedDogTag;
import charm.bootstrap.semantics.model.MatcherConfig;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Makes final 1:1 matching decisions using an LLM with conflict resolution.
 */
public class LlmDecider {

    private static final Logger LOG           = Logger.getLogger(LlmDecider.class.getName());
    private static final int    MAX_RETRIES   = 5;
    private static final int    BATCH_RETRIES = 3;
    private static final int    RATE_LIMIT_RETRIES = 6;   // 429 backoff attempts: 2s,4s,8s,16s,32s,60s

    // Matches the first http(s) URI in a (possibly verbose) LLM answer.
    private static final java.util.regex.Pattern URI_PATTERN =
            java.util.regex.Pattern.compile("https?://\\S+");

    // matcherName -> [inputTokens, outputTokens]
    private static final ConcurrentHashMap<String, long[]>  TOKEN_REGISTRY = new ConcurrentHashMap<>();
    // matcherName -> model name (for cost lookup)
    private static final ConcurrentHashMap<String, String>  MODEL_REGISTRY = new ConcurrentHashMap<>();

    private final String         name;
    private final String         modelName;
    private final int           concurrency;
    private final List<Double>  thresholds;
    private final MatcherConfig config;

    public LlmDecider(String name, String llmModel, int concurrency, List<Double> thresholds) {
        this.name        = name;
        this.modelName   = llmModel;
        this.concurrency = concurrency;
        this.thresholds  = thresholds;
        this.config      = new MatcherConfig();
    }

    // Prices per 1M tokens in USD — update when model pricing changes
    // LLM prices: input / output
    // Embedding prices: input only (no output tokens)
    private static final Map<String, double[]> MODEL_PRICES = Map.of(
        "gpt-5-mini",                          new double[]{0.25,  2.00},
        "text-embedding-ada-002",              new double[]{0.10,  0.00},
        "text-embedding-3-large",              new double[]{0.13,  0.00},
        "mistralai--mistral-medium-instruct",  new double[]{0.35,  1.80},
        "nvidia--llama-3.2-nv-embedqa-1b",     new double[]{0.10,  0.00},
        "mistralai--mistral-small-instruct",  new double[]{0.15,  0.60},
        "mistralai--mistral-small",  new double[]{0.15,  0.60}


    );

    public static void resetRegistry() {
        TOKEN_REGISTRY.clear();
        MODEL_REGISTRY.clear();
    }

    public static void recordTokens(String key, String modelName, long input, long output) {
        TOKEN_REGISTRY.merge(key, new long[]{input, output},
                (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
        MODEL_REGISTRY.putIfAbsent(key, modelName);
    }

    private static double cost(long tokens, double pricePerMillion) {
        return tokens / 1_000_000.0 * pricePerMillion;
    }

    // Returns CSV rows: matcher,model,input_tokens,output_tokens,total_tokens,cost_usd
    public static List<String> getCsvRows() {
        List<String> rows = new ArrayList<>();
        for (var entry : TOKEN_REGISTRY.entrySet()) {
            String key   = entry.getKey();
            long   in    = entry.getValue()[0], out = entry.getValue()[1];
            String model = MODEL_REGISTRY.getOrDefault(key, "unknown");
            double[] prices = MODEL_PRICES.getOrDefault(model, new double[]{0.0, 0.0});
            double rowCost  = cost(in, prices[0]) + cost(out, prices[1]);
            rows.add(String.format("%s,%s,%d,%d,%d,%.6f", key, model, in, out, in + out, rowCost));
        }
        return rows;
    }

    public static void logSummary() {
        if (TOKEN_REGISTRY.isEmpty()) return;
        double totalCost = 0;
        StringBuilder sb = new StringBuilder("\n=== Token Usage Summary ===\n");
        sb.append(String.format("  %-80s  %-30s  %12s  %12s  %12s  %10s%n",
                "Matcher", "Model", "input", "output", "total", "cost (USD)"));
        sb.append("  " + "-".repeat(164) + "\n");

        // per-model aggregation: model -> [llmIn, llmOut, embIn, cost]
        Map<String, double[]> modelTotals = new LinkedHashMap<>();

        for (var entry : TOKEN_REGISTRY.entrySet()) {
            String key   = entry.getKey();
            long   in    = entry.getValue()[0], out = entry.getValue()[1];
            String model = MODEL_REGISTRY.getOrDefault(key, "unknown");
            double[] prices = MODEL_PRICES.getOrDefault(model, new double[]{0.0, 0.0});
            double rowCost  = cost(in, prices[0]) + cost(out, prices[1]);
            totalCost += rowCost;

            sb.append(String.format("  %-80s  %-30s  %12d  %12d  %12d  %10.4f%n",
                    key, model, in, out, in + out, rowCost));

            boolean isEmb = "embedding".equals(key);
            modelTotals.computeIfAbsent(model, k -> new double[6]);
            double[] mt = modelTotals.get(model);
            if (isEmb) { mt[2] += in; }          // embIn
            else       { mt[0] += in; mt[1] += out; } // llmIn, llmOut
            mt[3] += rowCost;
        }

        sb.append("  " + "-".repeat(164) + "\n");

        // totals per model
        for (var mt : modelTotals.entrySet()) {
            String model = mt.getKey();
            double[] v   = mt.getValue();
            long llmIn = (long) v[0], llmOut = (long) v[1], embIn = (long) v[2];
            if (llmIn + llmOut > 0)
                sb.append(String.format("  %-80s  %-30s  %12d  %12d  %12d  %10.4f%n",
                        "LLM TOTAL [" + model + "]", model, llmIn, llmOut, llmIn + llmOut, v[3]));
            if (embIn > 0)
                sb.append(String.format("  %-80s  %-30s  %12d  %12s  %12d  %10.4f%n",
                        "EMBEDDING TOTAL [" + model + "]", model, embIn, "-", embIn, v[3]));
        }

        sb.append(String.format("  %-80s  %-30s  %10.4f USD%n",
                "COST TOTAL", "", totalCost));
        LOG.info(sb.toString());
    }

    private String callLlm(String prompt) {
        return callModel(prompt, modelName, name);
    }

    /**
     * Single entry point for every LLM chat call: applies the client-side rate limit and
     * retries on HTTP 429 with exponential backoff. Token usage is booked under {@code tokenKey}.
     * Both the matching decider and the summary/dogtag generation route through here so the
     * 429 handling can never drift apart between the two phases.
     */
    private static String callModel(String prompt, String modelName, String tokenKey) {
        return withBackoff(() -> {
            acquireRateLimit(modelName);
            if (modelName.toLowerCase().contains("mistral")) {
                var llmConfig = OrchestrationAiModel.MISTRAL_MEDIUM_INSTRUCT
                        .withParam(OrchestrationAiModel.Parameter.TEMPERATURE, 0);
                var config   = new OrchestrationModuleConfig().withLlmConfig(llmConfig);
                var response = new OrchestrationClient()
                        .chatCompletion(new OrchestrationPrompt(prompt), config);
                var usage = response.getTokenUsage();
                if (usage != null) {
                    long in  = usage.getPromptTokens()    != null ? usage.getPromptTokens().longValue()    : 0;
                    long out = usage.getCompletionTokens() != null ? usage.getCompletionTokens().longValue() : 0;
                    recordTokens(tokenKey, modelName, in, out);
                }
                return response.getContent();
            }
            var openAiModel = new OpenAiModel(modelName, null);
            var response = OpenAiClient.forModel(openAiModel)
                    .chatCompletion(new OpenAiChatCompletionRequest(prompt).withTemperature(BigDecimal.ZERO));
            var usage = response.getTokenUsage();
            if (usage != null) {
                long in = usage.getPromptTokens().longValue();
                Integer outInt = usage.getCompletionTokens();
                long out = outInt != null ? outInt.longValue() : 0;
                recordTokens(tokenKey, modelName, in, out);
            }
            return response.getContent();
        });
    }

    /** Blocks per model-specific rate limit; converts interruption into an unchecked error. */
    private static void acquireRateLimit(String modelName) {
        try {
            ModelRateLimiter.acquire(modelName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for rate-limit slot", e);
        }
    }

    /** True if the throwable (or any cause) looks like an HTTP 429 rate-limit response. */
    private static boolean isRateLimit(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String msg = c.getMessage();
            if (msg != null && (msg.contains("429") || msg.toLowerCase(Locale.ROOT).contains("too many requests"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs {@code call}, retrying on HTTP 429 with exponential backoff + jitter.
     * Non-429 errors are rethrown immediately. After all retries are exhausted the
     * last exception propagates so the caller's own retry/fail-open logic can act.
     */
    private static <T> T withBackoff(java.util.function.Supplier<T> call) {
        long baseDelayMs = 2000;
        for (int attempt = 0; ; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                if (!isRateLimit(e) || attempt >= RATE_LIMIT_RETRIES) throw e;
                long backoff = baseDelayMs * (1L << attempt);              // 2s, 4s, 8s, 16s, ...
                long jitter  = (long) (backoff * 0.25 * attempt % 500);    // deterministic, no Math.random
                long sleep   = Math.min(backoff + jitter, 60_000);
                // Only surface the warning once we're deep into retries (attempt index >= 4,
                // i.e. the 5th try) — the early backoffs are routine and just noise in the log.
                if (attempt >= 4)
                    LOG.warning("Rate limited (429), backing off " + sleep + "ms (attempt " + (attempt + 1) + "/" + RATE_LIMIT_RETRIES + ")");
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Returns list of [sourceUri, targetUri] pairs. */
    public List<String[]> decide11(
            Map<String, EmbeddedDogTag> sourceEmbeddings,
            Map<String, EmbeddedDogTag> targetEmbeddings,
            Map<String, List<Candidate>> candidates) throws InterruptedException {

        Map<String, String[]> allMatches     = new LinkedHashMap<>(); // srcUri -> [tgtUri, score]
        Map<String, String[]> matchedTargets = new LinkedHashMap<>(); // tgtUri -> [srcUri, score]
        Map<String, List<Candidate>> remaining = new HashMap<>();
        candidates.forEach((k, v) -> remaining.put(k, new ArrayList<>(v)));
        Map<String, Integer> retryCounts = new HashMap<>();
        candidates.forEach((k, v) -> retryCounts.put(k, 0));
        Set<String> queriedSources = new HashSet<>();

        int totalSources = candidates.size();
        int decided = 0;
        LOG.info("Starting 1:1 matching with " + totalSources + " sources, thresholds: " + thresholds);

        for (double threshold : thresholds) {
            List<String> queue = candidates.keySet().stream()
                    .filter(src -> !allMatches.containsKey(src))
                    .filter(src -> !queriedSources.contains(src))
                    .filter(src -> remaining.getOrDefault(src, List.of()).stream().anyMatch(c -> c.score >= threshold))
                    .collect(Collectors.toCollection(ArrayList::new));

            LOG.info("Threshold " + threshold + ": " + queue.size() + " sources in queue");

            while (!queue.isEmpty()) {
                int batchSize = Math.min(concurrency, queue.size());
                List<String> batch = new ArrayList<>(queue.subList(0, batchSize));
                queue = new ArrayList<>(queue.subList(batchSize, queue.size()));

                List<String[]> results = queryBatch(batch, remaining, sourceEmbeddings, targetEmbeddings);
                decided += batch.size();
                LOG.info("LLM decisions: " + decided + "/" + totalSources + " (" + (decided * 100 / totalSources) + "%), " + allMatches.size() + " matches so far");

                for (String[] result : results) {
                    String srcUri    = result[0];
                    String matchedUri = result[1];

                    if (matchedUri == null) {
                        queriedSources.add(srcUri);
                        continue;
                    }

                    double score = remaining.getOrDefault(srcUri, List.of()).stream()
                            .filter(c -> c.targetUri.equals(matchedUri))
                            .mapToDouble(c -> c.score).findFirst().orElse(0.0);

                    if (matchedTargets.containsKey(matchedUri)) {
                        String[] prev      = matchedTargets.get(matchedUri);
                        double   prevScore = Double.parseDouble(prev[1]);

                        if (score > prevScore) {
                            // Current source wins — steal target
                            LOG.fine("Conflict: " + srcUri + " (" + score + ") steals " + matchedUri + " from " + prev[0] + " (" + prevScore + ")");
                            allMatches.remove(prev[0]);
                            allMatches.put(srcUri, new String[]{matchedUri, String.valueOf(score)});
                            matchedTargets.put(matchedUri, new String[]{srcUri, String.valueOf(score)});
                            // Re-queue loser
                            if (retryCounts.getOrDefault(prev[0], 0) < MAX_RETRIES) {
                                retryCounts.merge(prev[0], 1, Integer::sum);
                                remaining.computeIfPresent(prev[0], (k, v) ->
                                        v.stream().filter(c -> !c.targetUri.equals(matchedUri)).collect(Collectors.toList()));
                                queriedSources.remove(prev[0]);
                                queue.add(prev[0]);
                            }
                        } else {
                            // Current source loses
                            LOG.fine("Conflict: " + srcUri + " (" + score + ") loses to " + prev[0] + " (" + prevScore + ") for " + matchedUri);
                            if (retryCounts.getOrDefault(srcUri, 0) < MAX_RETRIES) {
                                retryCounts.merge(srcUri, 1, Integer::sum);
                                remaining.computeIfPresent(srcUri, (k, v) ->
                                        v.stream().filter(c -> !c.targetUri.equals(matchedUri)).collect(Collectors.toList()));
                                queriedSources.remove(srcUri);
                                queue.add(srcUri);
                            }
                        }
                    } else {
                        allMatches.put(srcUri, new String[]{matchedUri, String.valueOf(score)});
                        matchedTargets.put(matchedUri, new String[]{srcUri, String.valueOf(score)});
                    }
                }
            }

            // Lock confirmed matches before lowering threshold
            Set<String> lockedTargets = new HashSet<>(matchedTargets.keySet());
            remaining.forEach((src, cands) -> cands.removeIf(c -> lockedTargets.contains(c.targetUri)));
            LOG.info("Threshold " + threshold + " complete: " + allMatches.size() + " total matches");
        }

        return allMatches.entrySet().stream()
                .map(e -> new String[]{e.getKey(), e.getValue()[0]})
                .collect(Collectors.toList());
    }

    private List<String[]> queryBatch(
            List<String> sources,
            Map<String, List<Candidate>> candidates,
            Map<String, EmbeddedDogTag> sourceEmbeddings,
            Map<String, EmbeddedDogTag> targetEmbeddings) throws InterruptedException {

        for (int attempt = 0; attempt < BATCH_RETRIES; attempt++) {
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(concurrency, sources.size()));
            List<Future<String[]>> futures = new ArrayList<>();

            for (String src : sources) {
                futures.add(executor.submit(() -> {
                    String candidatesCtx = formatCandidatesContext(
                            candidates.getOrDefault(src, List.of()), targetEmbeddings);
                    String prompt = config.matchingPromptTemplate
                            .replace("{source_context}", sourceEmbeddings.get(src).text)
                            .replace("{candidates_context}", candidatesCtx);
                    String response = callLlm(prompt).strip();
                    return new String[]{src, validateResponse(response)};
                }));
            }

            executor.shutdown();
            boolean finished = executor.awaitTermination(10L * sources.size(), TimeUnit.MINUTES);

            if (!finished) {
                executor.shutdownNow();
                LOG.warning("Decider batch timeout (attempt " + (attempt + 1) + "/" + BATCH_RETRIES + ")");
            } else {
                try {
                    List<String[]> results = new ArrayList<>();
                    for (Future<String[]> f : futures) results.add(f.get());
                    return results;
                } catch (ExecutionException e) {
                    LOG.warning("Decider batch error (attempt " + (attempt + 1) + "/" + BATCH_RETRIES + "): " + e.getCause().getMessage());
                }
            }

            if (attempt < BATCH_RETRIES - 1) Thread.sleep(5000);
        }

        // All attempts failed — return null matches for each source
        List<String[]> fallback = new ArrayList<>();
        for (String src : sources) fallback.add(new String[]{src, null});
        return fallback;
    }

    private String validateResponse(String response) {
        if (response == null) return null;
        String stripped = response.strip();
        if (stripped.isEmpty() || stripped.equalsIgnoreCase("none")) return null;

        // The LLM often answers verbosely (e.g. "The best match is <uri> because ...")
        // instead of returning a bare URI. Extract the first URI-looking token so a
        // chatty-but-correct answer is not discarded. If the model explicitly says
        // "none", we already returned null above.
        java.util.regex.Matcher m = URI_PATTERN.matcher(stripped);
        if (m.find()) {
            String uri = m.group();
            // Trim trailing punctuation the model may append (".", ",", ")", etc.)
            uri = uri.replaceAll("[.,;:)\\]}>\"']+$", "");
            try {
                java.net.URI.create(uri);
                return uri;
            } catch (Exception e) {
                LOG.warning("Extracted token is not a valid URI: " + uri + " (" + e.getMessage() + ")");
                return null;
            }
        }

        LOG.warning("No URI found in LLM response: " + stripped);
        return null;
    }

    private String formatCandidatesContext(List<Candidate> candidates, Map<String, EmbeddedDogTag> targetEmbeddings) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            EmbeddedDogTag tag = targetEmbeddings.get(c.targetUri);
            sb.append("\n--- CANDIDATE ").append(i + 1)
              .append(" (Score: ").append(String.format("%.2f", c.score)).append(") ---\n")
              .append("URI: ").append(c.targetUri).append("\n");
            if (tag != null) sb.append(tag.text).append("\n");
        }
        return sb.toString();
    }

    static Map<String, String> generateSummaries(Map<String, String> contexts, String modelName, int concurrency, charm.bootstrap.semantics.model.DogTagConfig config) throws InterruptedException {
        List<String> uris = new ArrayList<>(contexts.keySet());
        int total = uris.size();
        int logEvery = Math.max(1, total / 20);
        LOG.info("Generating summaries for " + total + " nodes...");

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(concurrency, total));
        Map<String, String> summaries = new ConcurrentHashMap<>();
        AtomicInteger done = new AtomicInteger(0);

        for (String uri : uris) {
            String prompt = config.summarizationPromptTemplate.replace("{node_context}", contexts.get(uri));
            executor.submit(() -> {
                try {
                    // Routes through callModel → withBackoff, so a 429 retries with backoff
                    // instead of silently falling back to the raw context below.
                    String content = callModel(prompt, modelName, "summarization");
                    summaries.put(uri, content);
                } catch (Exception e) {
                    LOG.warning("Summary failed for " + uri + ": " + e.getMessage());
                    summaries.put(uri, contexts.get(uri));
                }
                int n = done.incrementAndGet();
                if (n % logEvery == 0 || n == total)
                    LOG.info("Summaries: " + n + "/" + total + " (" + (n * 100 / total) + "%)");
            });
        }

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.MINUTES);
        return summaries;
    }

    /**
     * Verifies a list of source/target Jena resource pairs via the LLM in batches.
     * Returns a boolean list of the same size and order as {@code pairs}: {@code true}
     * = LLM confirmed the match, {@code false} = LLM rejected it.
     *
     * <p>Fail-open: on transport errors or unparsable responses after all retries,
     * every pair in the failing batch is marked {@code true} (kept). Callers must
     * therefore treat "true" as "not confirmed a false positive".
     *
     * @param pairs         input pairs, order preserved in the returned list.
     * @param batchSize     how many pairs share one LLM call.
     */
    public List<Boolean> verifyBatch(List<Pair<OntResource, OntResource>> pairs, int batchSize) {
        return verifyBatch(pairs, batchSize, false);
    }

    public List<Boolean> verifyBatch(List<Pair<OntResource, OntResource>> pairs, int batchSize, boolean createDogTags) {
        if (pairs.isEmpty()) return List.of();

        NodeContextFormatter formatter = new NodeContextFormatter(new DogTagConfig());

        Map<String, String> dogTagCache = new ConcurrentHashMap<>();
        if (createDogTags) {
            Set<OntResource> allResources = new LinkedHashSet<>();
            for (Pair<OntResource, OntResource> p : pairs) {
                allResources.add(p.getLeft());
                allResources.add(p.getRight());
            }
            Map<String, String> rawContexts = new LinkedHashMap<>();
            for (OntResource r : allResources) {
                String ctx = formatter.formatNode(r);
                if (!ctx.isEmpty()) rawContexts.put(r.getURI(), ctx);
            }
            try {
                dogTagCache.putAll(generateSummaries(rawContexts, modelName, concurrency, new DogTagConfig()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warning("verifyBatch: summary interrupted — falling back to raw context");
                dogTagCache.putAll(rawContexts);
            }
        }
        boolean[] verdicts = new boolean[pairs.size()];
        Arrays.fill(verdicts, true); // fail-open default

        int totalBatches = (pairs.size() + batchSize - 1) / batchSize;
        List<List<Integer>> batches = new ArrayList<>(totalBatches);
        for (int i = 0; i < pairs.size(); i += batchSize) {
            List<Integer> indices = new ArrayList<>();
            for (int j = i; j < Math.min(i + batchSize, pairs.size()); j++) indices.add(j);
            batches.add(indices);
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(concurrency, batches.size()));
        List<Future<?>> futures = new ArrayList<>(batches.size());

        for (List<Integer> batchIndices : batches) {
            futures.add(executor.submit(() -> {
                boolean[] batchVerdicts = queryVerificationBatch(batchIndices, pairs, formatter, dogTagCache);
                for (int i = 0; i < batchIndices.size(); i++) {
                    verdicts[batchIndices.get(i)] = batchVerdicts[i];
                }
            }));
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(10L * batches.size(), TimeUnit.MINUTES)) {
                executor.shutdownNow();
                LOG.warning("verifyBatch: overall timeout — keeping all remaining pairs (fail-open)");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        List<Boolean> result = new ArrayList<>(pairs.size());
        for (boolean v : verdicts) result.add(v);
        return result;
    }

    private boolean[] queryVerificationBatch(List<Integer> indices,
                                             List<Pair<OntResource, OntResource>> pairs,
                                             NodeContextFormatter formatter,
                                             Map<String, String> dogTagCache) {
        boolean[] verdicts = new boolean[indices.size()];
        Arrays.fill(verdicts, true); // fail-open

        StringBuilder pairsCtx = new StringBuilder();
        for (int i = 0; i < indices.size(); i++) {
            Pair<OntResource, OntResource> p = pairs.get(indices.get(i));
            String srcCtx = dogTagCache.getOrDefault(p.getLeft().getURI(),  formatter.formatNode(p.getLeft()));
            String tgtCtx = dogTagCache.getOrDefault(p.getRight().getURI(), formatter.formatNode(p.getRight()));
            pairsCtx.append("\n--- PAIR ").append(i + 1).append(" ---\n");
            pairsCtx.append("SOURCE:\n").append(srcCtx).append("\n");
            pairsCtx.append("TARGET:\n").append(tgtCtx).append("\n");
        }
        String prompt = config.verificationPromptTemplate.replace("{pairs_context}", pairsCtx.toString());

        for (int attempt = 0; attempt < BATCH_RETRIES; attempt++) {
            try {
                String response = callLlm(prompt).strip();
                parseVerdicts(response, verdicts);
                return verdicts;
            } catch (Exception e) {
                LOG.warning("Verification batch error (attempt " + (attempt + 1) + "/" + BATCH_RETRIES + "): " + e.getMessage());
                if (attempt < BATCH_RETRIES - 1) {
                    try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return verdicts; }
                }
            }
        }
        LOG.warning("verifyBatch: all attempts failed for batch of " + indices.size() + " pairs — keeping matches (fail-open)");
        return verdicts;
    }

    /** Parses "1. yes\n2. no\n..." into the pre-filled fail-open verdicts array. */
    private static void parseVerdicts(String response, boolean[] verdicts) {
        String[] lines = response.split("\\r?\\n");
        int idx = 0;
        for (String line : lines) {
            if (idx >= verdicts.length) break;
            String lower = line.toLowerCase(Locale.ROOT).strip();
            if (lower.isEmpty()) continue;
            if (lower.contains("yes")) { verdicts[idx++] = true; }
            else if (lower.contains("no")) { verdicts[idx++] = false; }
            // Unparsable line → skip; fail-open default (true) already set.
        }
    }
}
