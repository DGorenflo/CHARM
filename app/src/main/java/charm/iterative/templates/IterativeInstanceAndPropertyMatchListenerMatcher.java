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

public abstract class IterativeInstanceAndPropertyMatchListenerMatcher extends IterativeMatcher {

    private Set<Pair<OntResource, OntResource>> instancesMatchedSinceLastIteration;
    private Set<Pair<OntResource, OntResource>> instancesMatchedSinceLastIterationBeforeCurrentIteration;
    private Set<Pair<OntProperty, OntProperty>> propertiesMatchedSinceLastIteration;
    private Set<Pair<OntProperty, OntProperty>> propertiesMatchedSinceLastIterationBeforeCurrentIteration;

    @Override
    public void init(IMatcher parent, MappingState state) {
        super.init(parent, state);
        instancesMatchedSinceLastIteration = new HashSet<>(state.getMatchedInstances());
        propertiesMatchedSinceLastIteration = new HashSet<>(state.getMatchedProperties());
    }

    @Override
    public void onInstanceMatch(Pair<OntResource, OntResource> pair) {
        instancesMatchedSinceLastIteration.add(pair);
    }

    @Override
    public void onPropertyMatch(Pair<OntProperty, OntProperty> pair) {
        propertiesMatchedSinceLastIteration.add(pair);
    }

    @Override
    public void beforeRun() {
        super.beforeRun();
        instancesMatchedSinceLastIterationBeforeCurrentIteration = Set.copyOf(instancesMatchedSinceLastIteration);
        instancesMatchedSinceLastIteration.clear();
        propertiesMatchedSinceLastIterationBeforeCurrentIteration = Set.copyOf(propertiesMatchedSinceLastIteration);
        propertiesMatchedSinceLastIteration.clear();
    }

    @Override
    public void afterRun() {
        super.beforeRun();
        instancesMatchedSinceLastIterationBeforeCurrentIteration = null;
        propertiesMatchedSinceLastIterationBeforeCurrentIteration = null;
    }

    @Override
    public void reset() {
        super.reset();
        instancesMatchedSinceLastIteration = null;
        instancesMatchedSinceLastIterationBeforeCurrentIteration = null;
        propertiesMatchedSinceLastIteration = null;
        propertiesMatchedSinceLastIterationBeforeCurrentIteration = null;
    }

    public Set<Pair<OntResource, OntResource>> getInstancesMatchedSinceLastIteration() {
        return instancesMatchedSinceLastIterationBeforeCurrentIteration;
    }

    public Set<Pair<OntProperty, OntProperty>> getPropertiesMatchedSinceLastIteration() {
        return propertiesMatchedSinceLastIterationBeforeCurrentIteration;
    }

    @Override
    public void onClassMatch(Pair<OntClass, OntClass> pair) {

    }
}
