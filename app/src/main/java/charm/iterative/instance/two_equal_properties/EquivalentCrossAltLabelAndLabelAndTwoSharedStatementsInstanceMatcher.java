package charm.iterative.instance.two_equal_properties;

import charm.Matcher;
import charm.iterative.instance.EquivalentStringFunctionAndXSharedPropertyInstanceMatcher;
import org.apache.commons.lang3.tuple.Pair;

public class EquivalentCrossAltLabelAndLabelAndTwoSharedStatementsInstanceMatcher extends EquivalentStringFunctionAndXSharedPropertyInstanceMatcher {

    @Override
    protected double confidenceScore() { return 0.907; }

    public EquivalentCrossAltLabelAndLabelAndTwoSharedStatementsInstanceMatcher() {
        super(2, Pair.of(Matcher::getAltLabel, Matcher::getLabelOrUriEndIfNoLabelPresent));
    }
}
