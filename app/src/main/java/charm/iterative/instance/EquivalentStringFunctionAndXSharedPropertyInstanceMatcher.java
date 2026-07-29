package charm.iterative.instance;

import charm.IMatcher;
import charm.iterative.templates.IterativeInstanceAndPropertyMatchListenerMatcher;
import charm.MappingState;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.jena.ontology.OntResource;
import org.apache.jena.rdf.model.Statement;

import java.util.*;
import java.util.function.Function;

public abstract class EquivalentStringFunctionAndXSharedPropertyInstanceMatcher extends IterativeInstanceAndPropertyMatchListenerMatcher {


    private final int desiredSharedPropertyCount;
    private final Pair<Function<OntResource, String>, Function<OntResource, String>> resourceToStringFunction; //Left for source and right for target

    public EquivalentStringFunctionAndXSharedPropertyInstanceMatcher(int desiredSharedPropertyCount, Function<OntResource, String> resourceToStringFunction) {
        this(desiredSharedPropertyCount, Pair.of(resourceToStringFunction, resourceToStringFunction));
    }

    public EquivalentStringFunctionAndXSharedPropertyInstanceMatcher(int desiredSharedPropertyCount, Pair<Function<OntResource, String>, Function<OntResource, String>> resourceToStringFunction) {
        this.desiredSharedPropertyCount = desiredSharedPropertyCount;
        this.resourceToStringFunction = resourceToStringFunction;
    }

    //          Source Instance  TargetInstance  Set of statements matching for source and target
    private Map<OntResource, Map<OntResource, Set<Statement>>> sourceToTargetEquivalentStatementCounter; //Source Instance to Target Instance with same label and a counter

    @Override
    public void init(IMatcher parent, MappingState state) {
        super.init(parent, state);
        sourceToTargetEquivalentStatementCounter = new HashMap<>();
        findPairsWithEqualStringFunction(state.getSourceInstancesUnmatched(), state.getTargetInstancesUnmatched(),
                resourceToStringFunction.getLeft(), resourceToStringFunction.getRight())
                .sequential()
                .forEach(pair -> {
                    sourceToTargetEquivalentStatementCounter.computeIfAbsent(pair.getLeft(), k -> new HashMap<>()).put(pair.getRight(), new HashSet<>());
                });
    }

    @Override
    public void runIteration() {
        //captures all relation+literal matches
        getPropertiesMatchedSinceLastIteration().stream()
                .map(Pair::getLeft)
                .map(sourceProperty -> state.getStatementsForSourcePropertyMap().getOrDefault(sourceProperty, Set.of()))
                .flatMap(Collection::stream)
                .forEach(statement -> {
                    OntResource sourceInstanceToMatch = state.getSourceOntology().getOntResource(statement.getSubject());
                    if (state.isSourceInstanceStillUnmatched(sourceInstanceToMatch)) {
                        analyzeStatementAndAddProbableMatchesToResult(state, statement, sourceInstanceToMatch);
                    }
                });
        //captures all relation+resource matches
        getInstancesMatchedSinceLastIteration().stream()
                .map(Pair::getLeft)
                .flatMap(sourceMatchedInstance -> state.getStatementsWithSourceInstanceAsObjectPropertyMap().getOrDefault(sourceMatchedInstance, Set.of()).stream())
                .forEach(sourceStatementWithNewlyMatchedObject -> {
                    OntResource sourceInstanceToMatch = state.getSourceOntology().getOntResource(sourceStatementWithNewlyMatchedObject.getSubject());
                    if (state.isSourceInstanceStillUnmatched(sourceInstanceToMatch)) {
                        analyzeStatementAndAddProbableMatchesToResult(state, sourceStatementWithNewlyMatchedObject, sourceInstanceToMatch);
                    }
                });
    }

    private void analyzeStatementAndAddProbableMatchesToResult(MappingState state, Statement recentlyMatchedSourceStatement, OntResource sourceInstanceToMatch) {
        String sourceStatementRepresentationTranslatedToTarget = state.computeStringRepresentationBasedOnPredicateObject(recentlyMatchedSourceStatement);
        sourceToTargetEquivalentStatementCounter.getOrDefault(sourceInstanceToMatch, Map.of()).entrySet().stream()
                .forEach(entry -> {
                    OntResource targetIndividual = entry.getKey();
                    Set<Statement> knownEquivalentStatementsForSourceAndTargetIndividual = entry.getValue();
                    Set<String> targetRepresentations = state.getStatementRepresentationsRTforTargetInstancesMap().get(targetIndividual);
                    if (targetRepresentations != null && targetRepresentations.contains(sourceStatementRepresentationTranslatedToTarget)) {
                        synchronized (knownEquivalentStatementsForSourceAndTargetIndividual){
                            if (!knownEquivalentStatementsForSourceAndTargetIndividual.contains(recentlyMatchedSourceStatement)) {
                                if (knownEquivalentStatementsForSourceAndTargetIndividual.size() + 1 == desiredSharedPropertyCount) {
                                    if(state.isTargetInstanceStillUnmatched(targetIndividual)) {
                                        createMatch(sourceInstanceToMatch, targetIndividual);
                                    }
                                } else {
                                    knownEquivalentStatementsForSourceAndTargetIndividual.add(recentlyMatchedSourceStatement);
                                }
                            }
                        }
                    }
                });
    }


    @Override
    public void reset() {
        super.reset();
        sourceToTargetEquivalentStatementCounter = null;
    }
}
