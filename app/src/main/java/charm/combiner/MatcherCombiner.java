package charm.combiner;

import charm.iterative.IIterativeMatcher;
import charm.IMatcher;
import charm.iterative.IterativeMatcher;
import charm.MappingState;
import charm.util.ConsoleColors;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.jena.ontology.OntClass;
import org.apache.jena.ontology.OntProperty;
import org.apache.jena.ontology.OntResource;

import java.util.List;

public abstract class MatcherCombiner extends IterativeMatcher {

    protected final List<IMatcher> matchers;
    private boolean initialized = false;

    public MatcherCombiner(List<IMatcher> matchers) {
        this.matchers = matchers;
    }

    public MatcherCombiner(IMatcher... matchers) {
        this.matchers = List.of(matchers);
    }


    protected void runMatcher(IMatcher matcher) {
            System.out.println(ConsoleColors.GREEN + "Starting matcher: " + matcher.getName() + ConsoleColors.RESET);
            int sc = computeClassMatches(state);
            int sp = computePropertyMatches(state);
            int si = computeInstanceMatches(state);

            matcher.beforeRun();
            matcher.run();
            matcher.afterRun();
            int mc = computeClassMatches(state) - sc;
            int mp = computePropertyMatches(state) - sp;
            int mi = computeInstanceMatches(state) - si;

            System.out.println(ConsoleColors.YELLOW + matcher.getName() + " matched " + mc + " classes; " + mp + " properties; " + mi + " instances" + ConsoleColors.RESET);
    }

    protected int computeTotalMatches(MappingState state) {
        return computeInstanceMatches(state)
                + computePropertyMatches(state)
                + computeClassMatches(state);
    }

    private static int computeClassMatches(MappingState state) {
        return state.getMatchedClasses().size();
    }

    private static int computePropertyMatches(MappingState state) {
        return state.getMatchedProperties().size();
    }

    private static int computeInstanceMatches(MappingState state) {
        return state.getMatchedInstances().size();
    }

    @Override
    public void init(IMatcher parent, MappingState state) {
        if(!initialized) {
            super.init(parent, state);
            matchers.stream().parallel().forEach(matcher ->  matcher.init(this, state));
        }
        initialized = true;
    }


    @Override
    public void reset() {
        if(initialized) {
            super.reset();
            matchers.stream().parallel().forEach(IMatcher::reset);
        }
        initialized = false;
    }

    @Override
    public void passUpClassMatch(Pair<OntClass, OntClass> pair) {
        if(parent != null){
            parent.passUpClassMatch(pair);
        }else{
            this.onClassMatch(pair);
        }
    }

    @Override
    public void passUpPropertyMatch(Pair<OntProperty, OntProperty> pair) {
        if(parent != null){
            parent.passUpPropertyMatch(pair);
        }else{
            this.onPropertyMatch(pair);
        }
    }

    @Override
    public void passUpInstanceMatch(Pair<OntResource, OntResource> pair) {
        if(parent != null){
            parent.passUpInstanceMatch(pair);
        }else{
            this.onInstanceMatch(pair);
        }
    }

    @Override
    public void onPropertyMatch(Pair<OntProperty, OntProperty> pair) {
        matchers.stream().filter(matcher -> matcher instanceof IIterativeMatcher).forEach(matcher -> ((IIterativeMatcher) matcher).onPropertyMatch(pair));
    }

    @Override
    public void onClassMatch(Pair<OntClass, OntClass> pair) {
        matchers.stream().filter(matcher -> matcher instanceof IIterativeMatcher).forEach(matcher -> ((IIterativeMatcher) matcher).onClassMatch(pair));
    }

    @Override
    public void onInstanceMatch(Pair<OntResource, OntResource> pair) {
        matchers.stream().filter(matcher -> matcher instanceof IIterativeMatcher).forEach(matcher -> ((IIterativeMatcher) matcher).onInstanceMatch(pair));
    }
}
