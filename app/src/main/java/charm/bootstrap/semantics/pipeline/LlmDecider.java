package charm.bootstrap.semantics.pipeline;

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

    // Matches the first http(s) URI in a (possibly verbose) LLM answer.
    private static final java.util.regex.Pattern URI_PATTERN =
            java.util.regex.Pattern.compile("https?://\\S+");

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

    private String callLlm(String prompt) {
        return callModel(prompt, modelName, name);
    }

    /**
     * Single entry point for every LLM chat call. Delegates to the locally hosted
     * {@code /chat} endpoint via {@link LocalAiClient}. Both the matching decider and the
     * summary/dogtag generation route through here so behavior can never drift apart
     * between the two phases.
     */
    private static String callModel(String prompt, String modelName, String tokenKey) {
        return LocalAiClient.chat(modelName, prompt);
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
                    // Routes through callModel → LocalAiClient, whose own retry logic handles
                    // transient transport errors instead of silently falling back to raw context.
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
