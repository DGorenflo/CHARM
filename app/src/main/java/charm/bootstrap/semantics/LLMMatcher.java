package charm.bootstrap.semantics;

import org.apache.jena.ontology.OntResource;
import charm.bootstrap.BootstrapMatcher;
import charm.bootstrap.semantics.model.Candidate;
import charm.bootstrap.semantics.model.EmbeddedDogTag;
import charm.bootstrap.semantics.pipeline.CandidateRetriever;
import charm.bootstrap.semantics.pipeline.DogTagEmbeddPipeline;
import charm.bootstrap.semantics.pipeline.LlmDecider;

import java.util.*;
import java.util.logging.Logger;

public class LLMMatcher extends BootstrapMatcher {

    public enum EntityType { CLASS, PROPERTY, INSTANCE }

    private static final Logger LOG = Logger.getLogger(LLMMatcher.class.getName());

    private final String dogTagLlmModel;
    private final String llmModel;
    private final String embeddingModel;
    private final int concurrency;
    private final int llmMatchingConcurrency;
    private final int knn;
    private final boolean createDogTags;
    private final List<Double> thresholds;
    private final Set<EntityType> entityTypes;

    public LLMMatcher() {
        this("gpt-5-mini", "gpt-5-mini", "text-embedding-ada-002",
                100, 50, 10, true,
                List.of(0.96, 0.91, 0.87),
                EnumSet.of(EntityType.CLASS));
    }

    @Override
    protected double confidenceScore() { return 0.821; }

    public LLMMatcher(String dogTagLlmModel, String llmModel, String embeddingModel,
                      int concurrency, int llmMatchingConcurrency, int knn, boolean createDogTags,
                      List<Double> thresholds, Set<EntityType> entityTypes) {
        this.dogTagLlmModel          = dogTagLlmModel;
        this.llmModel                = llmModel;
        this.embeddingModel          = embeddingModel;
        this.concurrency             = concurrency;
        this.llmMatchingConcurrency  = llmMatchingConcurrency;
        this.knn                     = knn;
        this.createDogTags           = createDogTags;
        this.thresholds              = thresholds;
        this.entityTypes             = entityTypes;
    }

    @Override
    public void run() {
        try {
            Set<OntResource> sourceResources = new LinkedHashSet<>();
            Set<OntResource> targetResources = new LinkedHashSet<>();

            if (entityTypes.contains(EntityType.CLASS)) {
                sourceResources.addAll(state.getSourceClassesUnmatched());
                targetResources.addAll(state.getTargetClassesUnmatched());
            }
            if (entityTypes.contains(EntityType.PROPERTY)) {
                sourceResources.addAll(state.getSourcePropertiesUnmatched());
                targetResources.addAll(state.getTargetPropertiesUnmatched());
            }
            if (entityTypes.contains(EntityType.INSTANCE)) {
                sourceResources.addAll(state.getSourceInstancesUnmatched());
                targetResources.addAll(state.getTargetInstancesUnmatched());
            }

            LOG.info("LLMMatcher: " + sourceResources.size() + " source, " + targetResources.size() + " target resources");

            // Keep the correctly-typed resources (OntClass / OntProperty / OntResource) keyed by
            // URI. createMatch() branches on instanceof OntClass/OntProperty, so we must reuse
            // these typed objects — re-resolving via getOntResource(uri) yields an untyped
            // OntResource whose instanceof checks fail, silently routing class/property matches
            // into the instance branch where they get discarded.
            Map<String, OntResource> sourceByUri = new HashMap<>();
            for (OntResource r : sourceResources) sourceByUri.put(r.getURI(), r);
            Map<String, OntResource> targetByUri = new HashMap<>();
            for (OntResource r : targetResources) targetByUri.put(r.getURI(), r);

            DogTagEmbeddPipeline pipeline = new DogTagEmbeddPipeline(
                    dogTagLlmModel, embeddingModel, concurrency, createDogTags);

            Map<String, EmbeddedDogTag> sourceEmbeddings = pipeline.processResources(sourceResources);
            Map<String, EmbeddedDogTag> targetEmbeddings = pipeline.processResources(targetResources);

            LOG.info("LLMMatcher: embedded " + sourceEmbeddings.size() + " source, " + targetEmbeddings.size() + " target");

            Map<String, List<Candidate>> candidates = new CandidateRetriever(knn)
                    .getCandidates(sourceEmbeddings, targetEmbeddings);

            List<String[]> matches = new LlmDecider(getName(), llmModel, llmMatchingConcurrency, thresholds)
                    .decide11(sourceEmbeddings, targetEmbeddings, candidates);

            LOG.info("LLMMatcher: " + matches.size() + " matches found");

            for (String[] match : matches) {
                OntResource source = sourceByUri.get(match[0]);
                OntResource target = targetByUri.get(match[1]);
                if (source != null && target != null) {
                    createMatch(source, target);
                } else {
                    LOG.warning("LLMMatcher: could not resolve typed resource for match "
                            + match[0] + " -> " + match[1]);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("LLMMatcher failed", e);
        }
    }
}
