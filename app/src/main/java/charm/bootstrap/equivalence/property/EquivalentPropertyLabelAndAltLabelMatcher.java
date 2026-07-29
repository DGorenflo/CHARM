package charm.bootstrap.equivalence.property;

import charm.bootstrap.BootstrapMatcher;

import java.util.Set;

public class EquivalentPropertyLabelAndAltLabelMatcher extends BootstrapMatcher {

    @Override
    protected double confidenceScore() { return 0.803; }

    @Override
    public void run() {
        findEquivalentLabelStream(Set.copyOf(state.getSourcePropertiesUnmatched()), Set.copyOf(state.getTargetPropertiesUnmatched())).forEach(this::createMatch);
    }
}
