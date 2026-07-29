package charm.iterative.instance.one_equal_propertiesy;

import charm.Matcher;
import charm.iterative.instance.EquivalentStringFunctionAndXSharedPropertyInstanceMatcher;

public class EquivalentAltLabelAndOneSharedStatementInstanceMatcher extends EquivalentStringFunctionAndXSharedPropertyInstanceMatcher {

    @Override
    protected double confidenceScore() { return 0.910; }

    public EquivalentAltLabelAndOneSharedStatementInstanceMatcher() {
        super(1, Matcher::getAltLabel);
    }
}
