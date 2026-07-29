package charm.bootstrap.equivalence.instance;

import charm.bootstrap.BootstrapMatcher;

import java.util.Set;

public class EquivalentLabelAndAltLabelInstanceMatcher extends BootstrapMatcher {

    @Override
    protected double confidenceScore() { return 0.804; }

    @Override
    public void run() {
        findEquivalentLabelStream(Set.copyOf(state.getSourceInstancesUnmatched()), Set.copyOf(state.getTargetInstancesUnmatched())).forEach(this::createMatch);
    }
}
