package charm.iterative.templates;

import charm.IMatcher;
import charm.iterative.IterativeMatcher;
import charm.MappingState;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.jena.ontology.OntClass;
import org.apache.jena.ontology.OntProperty;
import org.apache.jena.ontology.OntResource;

import java.util.HashSet;
import java.util.Set;

public abstract class IterativePropertyMatchListenerMatcher extends IterativeMatcher {

    private Set<Pair<OntProperty, OntProperty>> propertiesMatchedSinceLastIteration;
    private Set<Pair<OntProperty, OntProperty>> propertiesMatchedSinceLastIterationBeforeCurrentIteration;

    @Override
    public void init(IMatcher parent, MappingState state) {
        super.init(parent, state);
        propertiesMatchedSinceLastIteration = new HashSet<>(state.getMatchedProperties());
    }

    @Override
    public void onPropertyMatch(Pair<OntProperty, OntProperty> pair) {
        propertiesMatchedSinceLastIteration.add(pair);
    }
    
    @Override
    public void beforeRun() {
        super.beforeRun();
        propertiesMatchedSinceLastIterationBeforeCurrentIteration = Set.copyOf(propertiesMatchedSinceLastIteration);
        propertiesMatchedSinceLastIteration.clear();
    }

    @Override
    public void afterRun() {
        super.afterRun();
        propertiesMatchedSinceLastIterationBeforeCurrentIteration = null;
    }

    @Override
    public void reset() {
        super.reset();
        propertiesMatchedSinceLastIteration = null;
        propertiesMatchedSinceLastIterationBeforeCurrentIteration = null;
    }

    public Set<Pair<OntProperty, OntProperty>> getPropertiesMatchedSinceLastIteration() {
        return propertiesMatchedSinceLastIterationBeforeCurrentIteration;
    }

    @Override
    public void onInstanceMatch(Pair<OntResource, OntResource> pair) {

    }

    @Override
    public void onClassMatch(Pair<OntClass, OntClass> pair) {

    }
}
