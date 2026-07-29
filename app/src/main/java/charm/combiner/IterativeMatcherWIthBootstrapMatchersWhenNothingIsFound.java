package charm.combiner;

import charm.IMatcher;
import charm.MappingState;

import java.util.List;

public class IterativeMatcherWIthBootstrapMatchersWhenNothingIsFound extends IterativeMatcherCombiner {


    private final List<IMatcher> bootstrapMatchers;

    public IterativeMatcherWIthBootstrapMatchersWhenNothingIsFound(List<IMatcher> iterativeMatchers, List<IMatcher> bootstrapMatchers) {
        super(iterativeMatchers);
        this.bootstrapMatchers = bootstrapMatchers;
    }

    @Override
    public void init(IMatcher parent, MappingState state) {
        super.init(parent, state);
        bootstrapMatchers.stream().parallel().forEach(bootstrapMatcher -> bootstrapMatcher.init(this, state));
    }

    @Override
    public void reset() {
        super.reset();
        bootstrapMatchers.stream().parallel().forEach(IMatcher::reset);
    }

    @Override
    public void runIteration() {
        for (IMatcher bootstrapMatcher : bootstrapMatchers) {
            System.out.println("Running bootstrap matcher " + bootstrapMatcher);
            runMatcher(bootstrapMatcher);
            super.runIteration();
        }
    }

}
