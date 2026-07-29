package charm.iterative.instance;

import org.apache.jena.ontology.OntProperty;
import charm.iterative.templates.IterativeInstanceAndPropertyMatchListenerMatcher;
import charm.iterative.templates.IterativeInstanceMatchListenerMatcher;
import charm.util.JenaStreamUtils;
import org.apache.jena.ontology.OntResource;
import org.apache.jena.rdf.model.Statement;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class RelatedToExistingMatchAndEquivalentLabelInstanceMatcher extends IterativeInstanceMatchListenerMatcher {

    @Override
    protected double confidenceScore() { return 0.902; }

    @Override
    public void runIteration() {
        getInstancesMatchedSinceLastIteration().stream().forEach(pair -> { //O(matches)
            OntResource matchedInstanceSource = pair.getLeft();
            OntResource matchedInstanceTarget = pair.getRight();

            Stream<Statement> sourceStream = JenaStreamUtils.toStream(matchedInstanceSource.listProperties()) //O(statements for this instance)
                    .filter(statement -> state.isSourceObjectInstanceAndStillUnmatched(statement.getObject()));
            Stream<Statement> targetStream = JenaStreamUtils.toStream(matchedInstanceTarget.listProperties()) //O(statements for this instance)
                    .filter(statement -> state.isTargetObjectInstanceAndStillUnmatched(statement.getObject()));

            findStatementPairsWithEqualStringFunction(sourceStream, targetStream, statement ->
                state.translateIfPossibleElseReturnOriginal(statement.getPredicate().getURI()) + STRING_SEPARATOR + createStringRepresentationBasedOnLabelAndAltLabel(statement.getObject().asResource())
            ).forEach(pairOfStatements -> {
                Statement sourceStatement = pairOfStatements.getLeft();
                Statement targetStatement = pairOfStatements.getRight();
                OntResource source = sourceStatement.getObject().asResource().as(OntResource.class);
                OntResource target = targetStatement.getObject().asResource().as(OntResource.class);
                createMatch(source, target);
            });
        });

    }
}
