package charm.bootstrap.equivalence.instance;

import org.apache.jena.ontology.OntResource;
import charm.bootstrap.BootstrapMatcher;
import charm.util.JenaStreamUtils;

import java.util.*;

public class UniqueSharedStatementInstanceMatcher extends BootstrapMatcher {

    @Override
    protected double confidenceScore() { return 0.805; }

    @SuppressWarnings("OptionalGetWithoutIsPresent")
    @Override
    public void run() {
        Map<String, Set<OntResource>> statementRepresentationToResourcesSource = new HashMap<>();
        Map<String, Set<OntResource>> statementRepresentationToResourcesTarget = new HashMap<>();

        runAsyncAndWaitForCompletion(
                () -> state.getSourceInstancesUnmatched().stream().forEach(individual -> {
                    JenaStreamUtils.toStream(individual.listProperties())
                            .map(statement -> state.computeStringRepresentationBasedOnPredicateObject(statement))
                            .forEach(statementRepresentation -> {
                                statementRepresentationToResourcesSource.computeIfAbsent(statementRepresentation, k -> new HashSet<>()).add(individual);
                            });
                }),

                () -> state.getTargetInstancesUnmatched().stream().forEach(individual -> {
                    JenaStreamUtils.toStream(individual.listProperties())
                            .map(statement -> state.computeStringRepresentationBasedOnPredicateObject(statement))
                            .forEach(statementRepresentation -> {
                                statementRepresentationToResourcesTarget.computeIfAbsent(statementRepresentation, k -> new HashSet<>()).add(individual);
                            });
                })
        );

        statementRepresentationToResourcesSource.keySet().stream()
                .filter(stringRepresentation -> statementRepresentationToResourcesSource.get(stringRepresentation).size() == 1)
                .filter(statementRepresentationToResourcesTarget::containsKey)
                .filter(stringRepresentation -> statementRepresentationToResourcesTarget.get(stringRepresentation).size() == 1)
                .forEach(stringRepresentation -> {
                    OntResource sourceIndividual = statementRepresentationToResourcesSource.get(stringRepresentation).stream().findFirst().get();
                    OntResource targetIndividual = statementRepresentationToResourcesTarget.get(stringRepresentation).stream().findFirst().get();
                    createMatch(sourceIndividual, targetIndividual);
                });
    }
}
