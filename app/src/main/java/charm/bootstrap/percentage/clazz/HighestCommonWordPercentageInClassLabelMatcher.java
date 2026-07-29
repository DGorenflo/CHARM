package charm.bootstrap.percentage.clazz;

import charm.bootstrap.percentage.HighestCommonLabelOverlapMatcher;

import java.util.*;

public class HighestCommonWordPercentageInClassLabelMatcher extends HighestCommonLabelOverlapMatcher {

    @Override
    protected double confidenceScore() { return 0.811; }

    @Override
    public void run() {
        run(Set.copyOf(state.getSourceClassesUnmatched()), Set.copyOf(state.getTargetClassesUnmatched()));
    }
}
