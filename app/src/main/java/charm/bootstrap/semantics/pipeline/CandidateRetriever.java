package charm.bootstrap.semantics.pipeline;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import com.github.jelmerk.knn.DistanceFunctions;
import com.github.jelmerk.knn.Item;
import com.github.jelmerk.knn.SearchResult;
import com.github.jelmerk.knn.hnsw.HnswIndex;

import charm.bootstrap.semantics.model.Candidate;
import charm.bootstrap.semantics.model.EmbeddedDogTag;

/**
 * Retrieves top-k candidate matches using an HNSW approximate-nearest-neighbor index
 * over cosine distance. Building the index is O(n log n); each query is O(log n),
 * so total cost scales roughly linearly with source count instead of source * target.
 * Memory is bounded by the index itself; per-query allocations are O(k) instead of O(target count).
 */
public class CandidateRetriever {

    private static final Logger LOG = Logger.getLogger(CandidateRetriever.class.getName());

    private final int k;

    public CandidateRetriever(int k) {
        this.k = k;
    }

    public Map<String, List<Candidate>> getCandidates(
            Map<String, EmbeddedDogTag> sourceEmbeddings,
            Map<String, EmbeddedDogTag> targetEmbeddings) {

        int sourceCount = sourceEmbeddings.size();
        int targetCount = targetEmbeddings.size();
        if (sourceCount == 0 || targetCount == 0) return Map.of();

        int dim = targetEmbeddings.values().iterator().next().vector.size();
        LOG.info("CandidateRetriever: building HNSW index over " + targetCount + " targets (dim=" + dim + ")");

        HnswIndex<String, float[], VectorItem, Float> index = HnswIndex
                .newBuilder(dim, DistanceFunctions.FLOAT_COSINE_DISTANCE, targetCount)
                .withM(16)
                .withEfConstruction(200)
                .withEf(Math.max(k * 4, 64))
                .build();

        List<VectorItem> items = new ArrayList<>(targetCount);
        for (Map.Entry<String, EmbeddedDogTag> e : targetEmbeddings.entrySet()) {
            items.add(new VectorItem(e.getKey(), toFloatArray(e.getValue().vector)));
        }
        try {
            index.addAll(items);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HNSW index build interrupted", e);
        }
        LOG.info("CandidateRetriever: index built, querying top-" + k + " for " + sourceCount + " sources");

        int logEvery = Math.max(1, sourceCount / 20);
        Map<String, List<Candidate>> results = new ConcurrentHashMap<>(sourceCount);
        AtomicInteger done = new AtomicInteger(0);

        sourceEmbeddings.entrySet().parallelStream().forEach(srcEntry -> {
            float[] srcVec = toFloatArray(srcEntry.getValue().vector);
            List<SearchResult<VectorItem, Float>> hits = index.findNearest(srcVec, k);
            List<Candidate> candidates = new ArrayList<>(hits.size());
            for (SearchResult<VectorItem, Float> hit : hits) {
                // hnswlib returns cosine *distance* in [0, 2]; convert back to similarity in [-1, 1].
                double similarity = 1.0 - hit.distance();
                candidates.add(new Candidate(hit.item().id(), similarity));
            }
            results.put(srcEntry.getKey(), candidates);

            int n = done.incrementAndGet();
            if (n % logEvery == 0 || n == sourceCount)
                LOG.fine("CandidateRetriever: " + n + "/" + sourceCount + " (" + (n * 100 / sourceCount) + "%)");
        });

        return results;
    }

    private static float[] toFloatArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    /** hnswlib Item wrapper. */
    private static final class VectorItem implements Item<String, float[]> {
        private static final long serialVersionUID = 1L;
        private final String id;
        private final float[] vector;

        VectorItem(String id, float[] vector) {
            this.id = id;
            this.vector = vector;
        }

        @Override public String id() { return id; }
        @Override public float[] vector() { return vector; }
        @Override public int dimensions() { return vector.length; }
    }
}
