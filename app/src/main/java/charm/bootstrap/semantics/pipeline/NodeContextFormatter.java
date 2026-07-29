package charm.bootstrap.semantics.pipeline;

import org.apache.jena.ontology.OntResource;

import charm.bootstrap.semantics.model.DogTagConfig;
import charm.util.JenaStreamUtils;

import java.net.URI;
import java.util.*;

public class NodeContextFormatter {

    private final DogTagConfig config;

    public NodeContextFormatter(DogTagConfig config) {
        this.config = config;
    }

    static String cleanUri(String uri) {
        try {
            URI parsed = URI.create(uri);
            String fragment = parsed.getFragment();
            if (fragment != null && !fragment.isEmpty()) return fragment;
            String path = parsed.getPath();
            if (path != null) {
                String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                String[] parts = trimmed.split("/");
                if (parts.length > 0) return parts[parts.length - 1].replace("_", " ");
            }
        } catch (Exception ignored) {}
        return uri;
    }

    private int priorityScore(String predicateUri) {
        if (config.typePriority.containsKey(predicateUri)) return config.typePriority.get(predicateUri);
        if (predicateUri.toLowerCase().contains("wiki")) return 10;
        return 100;
    }

    public String formatNode(OntResource resource) {
        if (resource.getURI() == null) return "";

        Map<String, List<String>> grouped = new LinkedHashMap<>();
        Map<String, Integer> predCount = new HashMap<>();

        JenaStreamUtils.toStream(resource.listProperties())
                .filter(stmt -> stmt.getPredicate().getURI() != null)
                .filter(stmt -> !config.discardablePredicates.contains(stmt.getPredicate().getURI()))
                .forEach(stmt -> {
                    String pred = stmt.getPredicate().getURI();
                    String val;
                    if (stmt.getObject().isLiteral()) {
                        val = stmt.getObject().asLiteral().getLexicalForm();
                    } else if (stmt.getObject().isResource() && stmt.getObject().asResource().getURI() != null) {
                        val = cleanUri(stmt.getObject().asResource().getURI());
                    } else {
                        return;
                    }
                    predCount.merge(pred, 1, Integer::sum);
                    grouped.computeIfAbsent(pred, k -> new ArrayList<>()).add(val);
                });

        if (grouped.isEmpty()) return "";

        List<String> sortedPreds = new ArrayList<>(grouped.keySet());
        sortedPreds.sort(Comparator
                .comparingInt(this::priorityScore)
                .thenComparingInt(p -> -predCount.getOrDefault(p, 0)));

        StringBuilder sb = new StringBuilder("Node: ").append(cleanUri(resource.getURI())).append("\n");
        for (String pred : sortedPreds) {
            List<String> values = grouped.get(pred);
            if (values.size() > config.maxNeighborsPerType) {
                List<String> sampled = new ArrayList<>(values);
                Collections.shuffle(sampled);
                sampled = new ArrayList<>(sampled.subList(0, config.maxNeighborsPerType));
                sampled.add("etc...");
                values = sampled;
            }
            sb.append(cleanUri(pred)).append(": [").append(String.join(", ", values)).append("]\n");
        }
        return sb.toString();
    }

    public Map<String, String> formatNodes(Collection<OntResource> resources) {
        Map<String, String> result = new LinkedHashMap<>();
        for (OntResource resource : resources) {
            if (resource == null || resource.getURI() == null) continue;
            String context = formatNode(resource);
            if (!context.isEmpty()) result.put(resource.getURI(), context);
        }
        return result;
    }
}
