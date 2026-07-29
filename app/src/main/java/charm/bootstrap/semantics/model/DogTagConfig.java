package charm.bootstrap.semantics.model;

import java.util.Map;
import java.util.Set;

public class DogTagConfig {

    public final Set<String> discardablePredicates = Set.of(
            "http://www.w3.org/2000/01/rdf-schema#comment",
            "http://xmlns.com/foaf/0.1/depiction",
            "http://purl.org/dc/elements/1.1/rights",
            "http://xmlns.com/foaf/0.1/thumbnail"
    );

    public final Map<String, Integer> typePriority = Map.of(
            "http://www.w3.org/2000/01/rdf-schema#label",       0,
            "http://www.w3.org/2004/02/skos/core#prefLabel",    0,
            "http://www.w3.org/2004/02/skos/core#altLabel",     1,
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",  2,
            "http://dbkwik.webdatacommons.org/ontology/abstract", 3
    );

    public final int maxNeighborsPerType = 20;
    public final int embeddingBatchSize  = 50;

    public final String summarizationPromptTemplate =
            "TASK: Given a graph node and its relations, generate a concise and coherent paragraph summarizing the key information about the node.\n" +
            "Input:\n" +
            "Textual representation of a graph node, including its attributes and all connected relations.\n" +
            "{node_context}\n" +
            "Output:\n" +
            "A well-written, concise paragraph that clearly and accurately summarizes the node based on the given information.\n" +
            "Answer:";
}
