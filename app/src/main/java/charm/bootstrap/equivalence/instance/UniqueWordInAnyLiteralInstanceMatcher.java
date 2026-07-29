package charm.bootstrap.equivalence.instance;

import org.apache.jena.ontology.OntResource;
import charm.bootstrap.BootstrapMatcher;
import charm.util.JenaStreamUtils;

import java.util.*;

public class UniqueWordInAnyLiteralInstanceMatcher extends BootstrapMatcher {

    @Override
    protected double confidenceScore() { return 0.806; }

    private static String sanitizeString(String s) {
        s = s.replaceAll("\\R", " ");  // \R matches any line break (cross-platform)

        s = s.replaceAll("\\s{2,}", " ");

        // 2. Remove words starting with lowercase
        // \b[a-z]\w* matches a word boundary, lowercase letter, and rest of word
        s = s.replaceAll("\\b[a-z]\\w*", "");

        // 3. Remove all non-alphanumeric and non-space characters
        s = s.replaceAll("[^A-Za-z0-9 ]", "");

        // 4. Clean up extra spaces introduced
        s = s.replaceAll("\\s{2,}", " ").trim();
        return s;
    }

    //O(#total number of words in literals)
    @Override
    public void run() {
        Set<OntResource> sourceInstancesUnmatched = state.getSourceInstancesUnmatched();
        Set<OntResource> targetInstancesUnmatched = state.getTargetInstancesUnmatched();

        Map<String, Set<OntResource>> wordOccurrencesSource = new HashMap<>();
        Map<String, Set<OntResource>> wordOccurrencesTarget = new HashMap<>();

        runAsyncAndWaitForCompletion(
                () -> sourceInstancesUnmatched.stream().forEach(sourceInstance -> {
                    JenaStreamUtils.toStream(sourceInstance.listProperties())
                            .filter(statement -> statement.getObject().isLiteral())
                            .map(statement -> statement.getObject().asLiteral().getLexicalForm())
                            .map(UniqueWordInAnyLiteralInstanceMatcher::sanitizeString)
                            .forEach(word -> {
                                wordOccurrencesSource.computeIfAbsent(word, k -> new HashSet<>()).add(sourceInstance);
                            });
                }), () -> targetInstancesUnmatched.stream().forEach(sourceInstance -> {
                    JenaStreamUtils.toStream(sourceInstance.listProperties())
                            .filter(statement -> statement.getObject().isLiteral())
                            .map(statement -> statement.getObject().asLiteral().getLexicalForm())
                            .map(UniqueWordInAnyLiteralInstanceMatcher::sanitizeString)
                            .forEach(word -> {
                                wordOccurrencesTarget.computeIfAbsent(word, k -> new HashSet<>()).add(sourceInstance);
                            });
                })
        );
        wordOccurrencesSource.entrySet().stream()
                .filter(stringSetEntry -> stringSetEntry.getValue().size() == 1)
                .filter(stringSetEntry -> wordOccurrencesTarget.getOrDefault(stringSetEntry.getKey(), Set.of()).size() == 1)
                .forEach(stringSetEntry -> {
                    OntResource sourceInstance = stringSetEntry.getValue().stream().findFirst().get();
                    OntResource targetInstance = wordOccurrencesTarget.get(stringSetEntry.getKey()).stream().findFirst().get();
                    createMatch(sourceInstance, targetInstance);
                });


    }

}
