package charm.iterative.instance.one_equal_propertiesy;

import charm.Matcher;
import charm.iterative.instance.EquivalentStringFunctionAndXSharedPropertyInstanceMatcher;

public class EquivalentLabelAndOneSharedStatementInstanceMatcher extends EquivalentStringFunctionAndXSharedPropertyInstanceMatcher {

    @Override
    protected double confidenceScore() { return 0.909; }

    public EquivalentLabelAndOneSharedStatementInstanceMatcher() {
        super(2, Matcher::getLabelOrUriEndIfNoLabelPresent);
    }
}
