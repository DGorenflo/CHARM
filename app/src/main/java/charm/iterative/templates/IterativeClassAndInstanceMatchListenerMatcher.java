package charm.iterative.templates;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.jena.ontology.OntClass;
import org.apache.jena.ontology.OntProperty;
import org.apache.jena.ontology.OntResource;
import charm.IMatcher;
import charm.MappingState;
import charm.iterative.IterativeMatcher;

import java.util.HashSet;
import java.util.Set;

public abstract class IterativeClassAndInstanceMatchListenerMatcher extends IterativeMatcher {

    private Set<Pair<OntClass, OntClass>> classesMatchedSinceLastIteration;
    private Set<Pair<OntClass, OntClass>> classesMatchedSinceLastIterationBeforeCurrentIteration;
    private Set<Pair<OntResource, OntResource>> instancesMatchedSinceLastIteration;
    private Set<Pair<OntResource, OntResource>> instancesMatchedSinceLastIterationBeforeCurrentIteration;

    @Override
    public void init(IMatcher parent, MappingState state) {
        super.init(parent, state);
        classesMatchedSinceLastIteration = new HashSet<>(state.getMatchedClasses());
        instancesMatchedSinceLastIteration = new HashSet<>(state.getMatchedInstances());
    }

    @Override
    public void onInstanceMatch(Pair<OntResource, OntResource> pair) {
        instancesMatchedSinceLastIteration.add(pair);
    }

    @Override
    public void onClassMatch(Pair<OntClass, OntClass> pair) {
        classesMatchedSinceLastIteration.add(pair);
    }

    @Override
    public void beforeRun() {
        super.beforeRun();
        classesMatchedSinceLastIterationBeforeCurrentIteration = Set.copyOf(classesMatchedSinceLastIteration);
        classesMatchedSinceLastIteration.clear();
        instancesMatchedSinceLastIterationBeforeCurrentIteration = Set.copyOf(instancesMatchedSinceLastIteration);
        instancesMatchedSinceLastIteration.clear();
    }

    @Override
    public void afterRun() {
        super.afterRun();
        classesMatchedSinceLastIterationBeforeCurrentIteration = null;
        instancesMatchedSinceLastIterationBeforeCurrentIteration = null;
    }

    @Override
    public void reset() {
        super.reset();
        classesMatchedSinceLastIteration = null;
        classesMatchedSinceLastIterationBeforeCurrentIteration = null;
        instancesMatchedSinceLastIteration = null;
        instancesMatchedSinceLastIterationBeforeCurrentIteration = null;
    }

    public Set<Pair<OntClass, OntClass>> getClassesMatchedSinceLastIteration() {
        return classesMatchedSinceLastIterationBeforeCurrentIteration;
    }

    public Set<Pair<OntResource, OntResource>> getInstancesMatchedSinceLastIteration() {
        return instancesMatchedSinceLastIterationBeforeCurrentIteration;
    }


    @Override
    public void onPropertyMatch(Pair<OntProperty, OntProperty> pair) {

    }
}
