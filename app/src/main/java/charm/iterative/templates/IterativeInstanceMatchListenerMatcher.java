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

public abstract class IterativeInstanceMatchListenerMatcher extends IterativeMatcher {

    private Set<Pair<OntResource, OntResource>> instancesMatchedSinceLastIteration;
    private Set<Pair<OntResource, OntResource>> instancesMatchedSinceLastIterationBeforeCurrentIteration;

    @Override
    public void init(IMatcher parent, MappingState state) {
        super.init(parent, state);
        instancesMatchedSinceLastIteration = new HashSet<>(state.getMatchedInstances());
    }

    @Override
    public void onInstanceMatch(Pair<OntResource, OntResource> pair) {
        instancesMatchedSinceLastIteration.add(pair);
    }
    
    @Override
    public void beforeRun() {
        super.beforeRun();
        instancesMatchedSinceLastIterationBeforeCurrentIteration = Set.copyOf(instancesMatchedSinceLastIteration);
        instancesMatchedSinceLastIteration.clear();
    }

    @Override
    public void afterRun() {
        super.afterRun();
        instancesMatchedSinceLastIterationBeforeCurrentIteration = null;
    }

    @Override
    public void reset() {
        super.reset();
        instancesMatchedSinceLastIteration = null;
        instancesMatchedSinceLastIterationBeforeCurrentIteration = null;
    }

    public Set<Pair<OntResource, OntResource>> getInstancesMatchedSinceLastIteration() {
        return instancesMatchedSinceLastIterationBeforeCurrentIteration;
    }

    @Override
    public void onPropertyMatch(Pair<OntProperty, OntProperty> pair) {

    }

    @Override
    public void onClassMatch(Pair<OntClass, OntClass> pair) {

    }
}
