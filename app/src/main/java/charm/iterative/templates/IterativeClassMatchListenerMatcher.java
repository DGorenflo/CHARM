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

public abstract class IterativeClassMatchListenerMatcher extends IterativeMatcher {

    private Set<Pair<OntClass, OntClass>> classesMatchedSinceLastIteration;
    private Set<Pair<OntClass, OntClass>> classesMatchedSinceLastIterationBeforeCurrentIteration;

    @Override
    public void init(IMatcher parent, MappingState state) {
        super.init(parent, state);
        classesMatchedSinceLastIteration = new HashSet<>(state.getMatchedClasses());
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
    }

    @Override
    public void afterRun() {
        super.afterRun();
        classesMatchedSinceLastIterationBeforeCurrentIteration = null;
    }

    @Override
    public void reset() {
        super.reset();
        classesMatchedSinceLastIteration = null;
        classesMatchedSinceLastIterationBeforeCurrentIteration = null;
    }

    public Set<Pair<OntClass, OntClass>> getClassesMatchedSinceLastIteration() {
        return classesMatchedSinceLastIterationBeforeCurrentIteration;
    }

    @Override
    public void onInstanceMatch(Pair<OntResource, OntResource> pair) {

    }

    @Override
    public void onPropertyMatch(Pair<OntProperty, OntProperty> pair) {

    }
}
