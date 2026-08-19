package charm.bootstrap.semantics.pipeline;

import charm.bootstrap.semantics.model.DogTagConfig;
import charm.bootstrap.semantics.model.EmbeddedDogTag;

import org.apache.jena.ontology.OntResource;

import java.util.*;
import java.util.logging.Logger;

/**
 * Orchestrates: NodeContextFormatter -> (optional) LLM summarization -> embedding.
 */
public class DogTagEmbeddPipeline {

    private static final Logger LOG = Logger.getLogger(DogTagEmbeddPipeline.class.getName());

    private final String embeddingModelName;
    private final String llmModelName;
    private final int concurrency;
    private final boolean createDogTags;
    private final DogTagConfig config;
    private final NodeContextFormatter formatter;

    public DogTagEmbeddPipeline(String llmModel, String embeddingModel, int concurrency, boolean createDogTags) {
        this.llmModelName       = llmModel;
        this.embeddingModelName = embeddingModel;
        this.concurrency        = concurrency;
        this.createDogTags      = createDogTags;
        this.config             = new DogTagConfig();
        this.formatter          = new NodeContextFormatter(config);
    }

    public Map<String, EmbeddedDogTag> processResources(Collection<OntResource> resources) throws Exception {
        Map<String, String> contexts = formatter.formatNodes(resources);
        if (contexts.isEmpty()) return Map.of();

        Map<String, String> textsToEmbed = createDogTags ? generateSummaries(contexts) : contexts;
        if (textsToEmbed.isEmpty()) return Map.of();

        Map<String, List<Float>> vectors = embedTexts(textsToEmbed);
        Map<String, EmbeddedDogTag> result = new LinkedHashMap<>();
        for (String uri : vectors.keySet()) {
            result.put(uri, new EmbeddedDogTag(vectors.get(uri), textsToEmbed.get(uri)));
        }
        return result;
    }

    private Map<String, String> generateSummaries(Map<String, String> contexts) throws InterruptedException {
        return LlmDecider.generateSummaries(contexts, llmModelName, concurrency, config);
    }

    private Map<String, List<Float>> embedTexts(Map<String, String> texts) throws Exception {
        List<String> uris     = new ArrayList<>(texts.keySet());
        List<String> textList = new ArrayList<>();
        for (String uri : uris) textList.add(texts.get(uri));

        int total = uris.size();
        Map<String, List<Float>> results = new LinkedHashMap<>();

        for (int i = 0; i < total; i += config.embeddingBatchSize) {
            List<String> batchUris  = uris.subList(i, Math.min(i + config.embeddingBatchSize, total));
            List<String> batchTexts = textList.subList(i, Math.min(i + config.embeddingBatchSize, total));

            List<float[]> vectors = LocalAiClient.embed(embeddingModelName, batchTexts);

            for (int j = 0; j < batchUris.size(); j++) {
                float[] v = vectors.get(j);
                List<Float> vec = new ArrayList<>(v.length);
                for (float f : v) vec.add(f);
                results.put(batchUris.get(j), vec);
            }
            int embedded = Math.min(i + config.embeddingBatchSize, total);
            LOG.info("Embedded " + embedded + "/" + total + " (" + (embedded * 100 / total) + "%)");
        }
        return results;
    }
}
