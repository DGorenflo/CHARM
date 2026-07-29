package charm.iterative.property;

import org.apache.jena.ontology.OntProperty;
import org.apache.jena.ontology.OntResource;
import org.apache.jena.rdf.model.Statement;
import charm.IMatcher;
import charm.MappingState;
import charm.iterative.templates.IterativeInstanceMatchListenerMatcher;
import charm.util.JenaStreamUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MatchPropertiesBasedOnUniqueSubjectObjectCombinations extends IterativeInstanceMatchListenerMatcher {

    @Override
    protected double confidenceScore() { return 0.921; }

    private Map<OntProperty, Set<OntProperty>> counterSourceToTarget;
    private Map<OntProperty, Set<OntProperty>> counterTargetToSource;
    private final Object counterLock = new Object();

    @Override
    public void init(IMatcher parent, MappingState state) {
        super.init(parent, state);
        counterSourceToTarget = new HashMap<>();
        counterTargetToSource = new HashMap<>();
    }

    @Override
    public void reset() {
        super.reset();
        counterSourceToTarget = null;
        counterTargetToSource = null;
    }

    @Override
    public void runIteration() {
        getInstancesMatchedSinceLastIteration().stream().parallel().forEach(match -> {
            OntResource sourceInstanceMatched = match.getLeft();
            handleStatements(JenaStreamUtils.toStream(sourceInstanceMatched.listProperties()));
        });
        getInstancesMatchedSinceLastIteration().stream().parallel().forEach(match -> {
            OntResource sourceInstanceMatched = match.getLeft();
            handleStatements(state.getStatementsWithSourceInstanceAsObjectPropertyMap().getOrDefault(sourceInstanceMatched, Set.of()).stream());
        });

        getInstancesMatchedSinceLastIteration().stream()
                .parallel()
                .flatMap(pair -> JenaStreamUtils.toStream(pair.getLeft().listProperties()))
                .map(statement -> state.getSourceOntology().getOntProperty(statement.getPredicate().getURI()))
                .forEach(sourceProperty -> {
                    Set<OntProperty> counterForThisSource = counterSourceToTarget.getOrDefault(sourceProperty, Set.of());
                    if (counterForThisSource.size() == 1) { //Only if this pair is a clear 1-1 match
                        OntProperty targetProperty = counterForThisSource.stream().findFirst().get();
                        if (counterTargetToSource.getOrDefault(targetProperty, Set.of()).size() == 1) { //Only 1 link back
                            createMatch(sourceProperty, targetProperty);
                        }
                    }
                });
    }

    private void handleStatements(Stream<Statement> statements) {
        statements.filter(statement -> state.isSourcePropertyStillUnmatched(statement.getPredicate()))
                .forEach(statement -> {
                    String statementRepresentationBasedOnSubjectObject = state.computeStatementRepresentationBasedOnSubjectObject(statement);
                    Set<Statement> statementsWithUnmatchedProp = state.getStatementRepresentationsHTforTargetTriplesMap()
                            .getOrDefault(statementRepresentationBasedOnSubjectObject, Set.of())
                            .stream()
                            .filter(targetStatement -> state.isTargetPropertyStillUnmatched(targetStatement.getPredicate()))
                            .collect(Collectors.toSet());
                    if (statementsWithUnmatchedProp.size() == 1) {
                        OntProperty sourceProperty = state.getSourceOntology().getOntProperty(statement.getPredicate().getURI());
                        OntProperty targetProperty = state.getTargetOntology().getOntProperty(statementsWithUnmatchedProp.stream().findFirst().get().getPredicate().getURI());
                        synchronized (counterLock) {
                            counterSourceToTarget.computeIfAbsent(sourceProperty, k -> new HashSet<>()).add(targetProperty);
                            counterTargetToSource.computeIfAbsent(targetProperty, k -> new HashSet<>()).add(sourceProperty);
                        }
                    }
                });

    }

}
