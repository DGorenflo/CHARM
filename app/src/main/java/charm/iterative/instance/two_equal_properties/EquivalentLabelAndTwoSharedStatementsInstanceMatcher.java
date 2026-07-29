package charm.iterative.instance.two_equal_properties;

import charm.Matcher;
import charm.iterative.instance.EquivalentStringFunctionAndXSharedPropertyInstanceMatcher;

public class EquivalentLabelAndTwoSharedStatementsInstanceMatcher extends EquivalentStringFunctionAndXSharedPropertyInstanceMatcher {

    @Override
    protected double confidenceScore() { return 0.904; }

    public EquivalentLabelAndTwoSharedStatementsInstanceMatcher() {
        super(2, Matcher::getLabelOrUriEndIfNoLabelPresent);
    }
}
