package charm.iterative.instance;

import charm.iterative.templates.IterativeClassMatchListenerMatcher;
import charm.util.JenaStreamUtils;
import org.apache.jena.ontology.OntClass;

public class EquivalentInstanceLabelMatcherForSameClass extends IterativeClassMatchListenerMatcher {

    @Override
    protected double confidenceScore() { return 0.901; }

    @Override
    public void runIteration() {
        getClassesMatchedSinceLastIteration().stream().parallel().forEach(matchedClasses -> {
            OntClass sourceClass = matchedClasses.getLeft();
            OntClass targetClass = matchedClasses.getRight();
            findEquivalentLabelStream(JenaStreamUtils.toStream(sourceClass.listInstances()), JenaStreamUtils.toStream(targetClass.listInstances()))
                    .forEach(this::createMatch);
        });
    }

}
