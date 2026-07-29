package charm.iterative.instance;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.jena.ontology.OntResource;
import charm.IMatcher;
import charm.MappingState;
import charm.iterative.templates.IterativeInstanceAndPropertyMatchListenerMatcher;
import charm.util.JenaStreamUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class InstancesWithCommonUniqueStatementMatcher extends IterativeInstanceAndPropertyMatchListenerMatcher {

    @Override
    protected double confidenceScore() { return 0.913; }

    private final static int minThreshold = 2;
    private Map<String, OntResource> uniqueTargetStatementRepresentationToTheirTargetResource;
    private Map<Pair<OntResource, OntResource>, Integer> commonUniqueStatements;
    private AtomicInteger sumOfCommonUniqueStatementsAtTimeOfMatchingForMatchedInstances;

    @Override
    public void init(IMatcher parent, MappingState state) {
        super.init(parent, state);
        uniqueTargetStatementRepresentationToTheirTargetResource = new HashMap<>();
        commonUniqueStatements = new ConcurrentHashMap<>();
        sumOfCommonUniqueStatementsAtTimeOfMatchingForMatchedInstances = new AtomicInteger(0);
        Map<String, Integer> representationCount = new HashMap<>(); //Counts for each representation how often it occurs
        state.getStatementRepresentationsRTforTargetInstancesMap().forEach((ontResource, representations) -> {
            representations.forEach(representation -> {
                representationCount.merge(representation, 1, Integer::sum);
            });
        });
        state.getStatementRepresentationsRTforTargetInstancesMap().forEach((ontResource, representations) -> {
            representations.forEach(representation -> {
                if (representationCount.get(representation) == 1) {
                    //This is a unique statement
                    uniqueTargetStatementRepresentationToTheirTargetResource.put(representation, ontResource);
                }
            });
        });
    }

    @Override
    public void reset() {
        super.reset();
        uniqueTargetStatementRepresentationToTheirTargetResource = null;
        commonUniqueStatements = null;
        sumOfCommonUniqueStatementsAtTimeOfMatchingForMatchedInstances = null;
    }

    @Override
    public void runIteration() {
        getInstancesMatchedSinceLastIteration().stream()
                .parallel()
                .forEach(matchedPair -> {
                    OntResource sourceInstance = matchedPair.getLeft();
                    OntResource targetInstance = matchedPair.getRight();
                    long commonUniqueCount = JenaStreamUtils.toStream(sourceInstance.listProperties())
                            .map(state::computeStringRepresentationBasedOnPredicateObject)
                            .filter(representation -> uniqueTargetStatementRepresentationToTheirTargetResource.containsKey(representation))
                            .filter(representation -> uniqueTargetStatementRepresentationToTheirTargetResource.get(representation).equals(targetInstance))
                            .count();
                    sumOfCommonUniqueStatementsAtTimeOfMatchingForMatchedInstances.addAndGet((int) commonUniqueCount);
                });
        int sumInt = sumOfCommonUniqueStatementsAtTimeOfMatchingForMatchedInstances.get();
        int countInt = state.getMatchedInstances().size();
        double averageCommonUniqueProperties = (sumInt + 0.0) / (countInt + 0.0);

        getPropertiesMatchedSinceLastIteration().stream() //In total O(properties * statements with this property) = O(statements)
                .map(Pair::getLeft)
                .map(sourceProperty -> state.getStatementsForSourcePropertyMap().getOrDefault(sourceProperty, Set.of()))
                .flatMap(Collection::stream)
                .forEach(statement -> {
                    String representation = state.computeStringRepresentationBasedOnPredicateObject(statement);
                    if (uniqueTargetStatementRepresentationToTheirTargetResource.containsKey(representation)) {
                        OntResource sourceInstance = state.getSourceOntology().getOntResource(statement.getSubject());
                        OntResource targetInstance = uniqueTargetStatementRepresentationToTheirTargetResource.get(representation);
                        checkThresholdAndPossiblyCreateMatch(sourceInstance, targetInstance, averageCommonUniqueProperties);
                    }
                });
        getInstancesMatchedSinceLastIteration().stream()
                .map(Pair::getLeft)
                .flatMap(sourceMatchedInstance -> state.getStatementsWithSourceInstanceAsObjectPropertyMap().getOrDefault(sourceMatchedInstance, Set.of()).stream())
                .forEach(sourceStatementWithNewlyMatchedObject -> {
                    String representation = state.computeStringRepresentationBasedOnPredicateObject(sourceStatementWithNewlyMatchedObject);
                    if (uniqueTargetStatementRepresentationToTheirTargetResource.containsKey(representation)) {
                        OntResource sourceInstance = state.getSourceOntology().getOntResource(sourceStatementWithNewlyMatchedObject.getSubject());
                        OntResource targetInstance = uniqueTargetStatementRepresentationToTheirTargetResource.get(representation);
                        checkThresholdAndPossiblyCreateMatch(sourceInstance, targetInstance, averageCommonUniqueProperties);
                    }
                });

    }

    private void checkThresholdAndPossiblyCreateMatch(OntResource sourceInstance, OntResource targetInstance, double threshold) {
        Pair<OntResource, OntResource> instancePair = Pair.of(sourceInstance, targetInstance);
        int newValue = commonUniqueStatements.merge(instancePair, 1, Integer::sum);
        if (newValue >= threshold && newValue > minThreshold) {
            createMatch(instancePair);
        }
    }

}
