package charm.combiner;

import charm.IMatcher;

import java.util.List;

public class SequentialMatcherCombiner extends MatcherCombiner{

    public SequentialMatcherCombiner(List<IMatcher> matchers) {
        super(matchers);
    }

    public SequentialMatcherCombiner(IMatcher... matchers) {
        super(matchers);
    }

    @Override
    public void runIteration() {
        super.matchers.forEach(this::runMatcher);
    }
}
