package charm.iterative.instance.two_equal_properties;

import charm.Matcher;
import charm.iterative.instance.EquivalentStringFunctionAndXSharedPropertyInstanceMatcher;

public class EquivalentAltLabelAndTwoSharedStatementsInstanceMatcher extends EquivalentStringFunctionAndXSharedPropertyInstanceMatcher {

    @Override
    protected double confidenceScore() { return 0.905; }

    public EquivalentAltLabelAndTwoSharedStatementsInstanceMatcher() {
        super(2, Matcher::getAltLabel);
    }
}
