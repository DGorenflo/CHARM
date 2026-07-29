package charm.bootstrap.equivalence.clazz;

import charm.bootstrap.BootstrapMatcher;

import java.util.Set;

public class EquivalentClassLabelMatcher extends BootstrapMatcher {

    @Override
    protected double confidenceScore() { return 0.802; }

    @Override
    public void run() {
        findEquivalentLabelStream(Set.copyOf(state.getSourceClassesUnmatched()), Set.copyOf(state.getTargetClassesUnmatched())).forEach(this::createMatch);
    }
}
