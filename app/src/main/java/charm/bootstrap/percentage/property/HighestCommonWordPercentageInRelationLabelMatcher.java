package charm.bootstrap.percentage.property;

import charm.bootstrap.percentage.HighestCommonLabelOverlapMatcher;

import java.util.Set;

public class HighestCommonWordPercentageInRelationLabelMatcher extends HighestCommonLabelOverlapMatcher {

    @Override
    protected double confidenceScore() { return 0.812; }

    @Override
    public void run() {
        run(Set.copyOf(state.getSourcePropertiesUnmatched()), Set.copyOf(state.getTargetPropertiesUnmatched()));
    }
}
