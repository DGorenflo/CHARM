package charm.bootstrap.semantics;

import org.apache.jena.ontology.OntResource;
import charm.bootstrap.BootstrapMatcher;
import charm.bootstrap.semantics.model.Candidate;
import charm.bootstrap.semantics.model.EmbeddedDogTag;
import charm.bootstrap.semantics.pipeline.CandidateRetriever;
import charm.bootstrap.semantics.pipeline.DogTagEmbeddPipeline;

import java.util.*;
import java.util.logging.Logger;

/**
 * Matches entities purely by embedding cosine similarity (no LLM decisions).
 * Context is formatted via NodeContextFormatter and embedded directly without dog-tag summarization.
 * Enforces 1:1 cardinality: when multiple sources compete for the same target,
 * the highest-scoring source wins; losers are re-evaluated against their next best candidate.
 */
public class SimilarityMatcher extends BootstrapMatcher {

    private static final Logger LOG = Logger.getLogger(SimilarityMatcher.class.getName());

    private static final int CANDIDATE_POOL = 3;

    private final String embeddingModel;
    private final int concurrency;
    private final double threshold;
    private final Set<LLMMatcher.EntityType> entityTypes;

    public SimilarityMatcher() {
        this("text-embedding-ada-002", 100, 0.9, EnumSet.of(LLMMatcher.EntityType.CLASS));
    }

    public SimilarityMatcher(String embeddingModel, int concurrency, double threshold,
                              Set<LLMMatcher.EntityType> entityTypes) {
        this.embeddingModel = embeddingModel;
        this.concurrency    = concurrency;
        this.threshold      = threshold;
        this.entityTypes    = entityTypes;
    }

    @Override
    public void run() {
        try {
            Set<OntResource> sourceResources = new LinkedHashSet<>();
            Set<OntResource> targetResources = new LinkedHashSet<>();

            if (entityTypes.contains(LLMMatcher.EntityType.CLASS)) {
                sourceResources.addAll(state.getSourceClassesUnmatched());
                targetResources.addAll(state.getTargetClassesUnmatched());
            }
            if (entityTypes.contains(LLMMatcher.EntityType.PROPERTY)) {
                sourceResources.addAll(state.getSourcePropertiesUnmatched());
                targetResources.addAll(state.getTargetPropertiesUnmatched());
            }
            if (entityTypes.contains(LLMMatcher.EntityType.INSTANCE)) {
                sourceResources.addAll(state.getSourceInstancesUnmatched());
                targetResources.addAll(state.getTargetInstancesUnmatched());
            }

            LOG.info("SimilarityMatcher: " + sourceResources.size() + " source, " + targetResources.size() + " target resources");

            DogTagEmbeddPipeline pipeline = new DogTagEmbeddPipeline(
                    embeddingModel, embeddingModel, concurrency, false);

            Map<String, EmbeddedDogTag> sourceEmbeddings = pipeline.processResources(sourceResources);
            Map<String, EmbeddedDogTag> targetEmbeddings = pipeline.processResources(targetResources);

            LOG.info("SimilarityMatcher: embedded " + sourceEmbeddings.size() + " source, " + targetEmbeddings.size() + " target");

            Map<String, List<Candidate>> candidates = new CandidateRetriever(CANDIDATE_POOL)
                    .getCandidates(sourceEmbeddings, targetEmbeddings);

            List<String[]> matches = resolve11(candidates);

            LOG.info("SimilarityMatcher: " + matches.size() + " matches found");

            for (String[] match : matches) {
                OntResource source = state.getSourceOntology().getOntResource(match[0]);
                OntResource target = state.getTargetOntology().getOntResource(match[1]);
                if (source != null && target != null) {
                    createMatch(source, target);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("SimilarityMatcher failed", e);
        }
    }

    /**
     * Resolves 1:1 matches from top-1 candidates above the threshold.
     * When two sources claim the same target, the higher-scoring source wins;
     * the loser is re-evaluated with its next best candidate (obtained by fetching k=2).
     */
    private List<String[]> resolve11(Map<String, List<Candidate>> candidates) {
        Map<String, String[]> allMatches = new LinkedHashMap<>();
        Map<String, String[]> matchedTargets = new LinkedHashMap<>();

        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, List<Candidate>> entry : candidates.entrySet()) {
            Candidate top = entry.getValue().isEmpty() ? null : entry.getValue().get(0);
            if (top != null && top.score >= threshold) {
                queue.add(entry.getKey());
            }
        }

        Map<String, List<Candidate>> remaining = new LinkedHashMap<>();
        candidates.forEach((k, v) -> remaining.put(k, new ArrayList<>(v)));

        while (!queue.isEmpty()) {
            String srcUri = queue.poll();

            List<Candidate> srcCandidates = remaining.getOrDefault(srcUri, List.of());
            Candidate best = srcCandidates.isEmpty() ? null : srcCandidates.get(0);

            if (best == null || best.score < threshold) continue;

            String tgtUri = best.targetUri;

            if (!matchedTargets.containsKey(tgtUri)) {
                allMatches.put(srcUri, new String[]{tgtUri, String.valueOf(best.score)});
                matchedTargets.put(tgtUri, new String[]{srcUri, String.valueOf(best.score)});
            } else {
                String[] prev      = matchedTargets.get(tgtUri);
                double   prevScore = Double.parseDouble(prev[1]);

                if (best.score > prevScore) {
                    LOG.info("Conflict: " + srcUri + " (" + best.score + ") steals " + tgtUri + " from " + prev[0] + " (" + prevScore + ")");
                    allMatches.remove(prev[0]);
                    allMatches.put(srcUri, new String[]{tgtUri, String.valueOf(best.score)});
                    matchedTargets.put(tgtUri, new String[]{srcUri, String.valueOf(best.score)});

                    String loser = prev[0];
                    remaining.computeIfPresent(loser, (k, v) ->
                            new ArrayList<>(v.stream().filter(c -> !c.targetUri.equals(tgtUri)).toList()));
                    List<Candidate> loserNext = remaining.getOrDefault(loser, List.of());
                    if (!loserNext.isEmpty() && loserNext.get(0).score >= threshold) {
                        queue.add(loser);
                    }
                } else {
                    LOG.info("Conflict: " + srcUri + " (" + best.score + ") loses to " + prev[0] + " (" + prevScore + ") for " + tgtUri);
                    remaining.computeIfPresent(srcUri, (k, v) ->
                            new ArrayList<>(v.stream().filter(c -> !c.targetUri.equals(tgtUri)).toList()));
                    List<Candidate> nextCandidates = remaining.getOrDefault(srcUri, List.of());
                    if (!nextCandidates.isEmpty() && nextCandidates.get(0).score >= threshold) {
                        queue.add(srcUri);
                    }
                }
            }
        }

        return allMatches.entrySet().stream()
                .map(e -> new String[]{e.getKey(), e.getValue()[0]})
                .toList();
    }
}
