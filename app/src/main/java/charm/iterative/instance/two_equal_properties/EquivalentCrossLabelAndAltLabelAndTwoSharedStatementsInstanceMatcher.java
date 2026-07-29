package charm.iterative.instance.two_equal_properties;

import charm.Matcher;
import charm.iterative.instance.EquivalentStringFunctionAndXSharedPropertyInstanceMatcher;
import org.apache.commons.lang3.tuple.Pair;

public class EquivalentCrossLabelAndAltLabelAndTwoSharedStatementsInstanceMatcher extends EquivalentStringFunctionAndXSharedPropertyInstanceMatcher {

    @Override
    protected double confidenceScore() { return 0.906; }

    public EquivalentCrossLabelAndAltLabelAndTwoSharedStatementsInstanceMatcher() {
        super(2, Pair.of(Matcher::getLabelOrUriEndIfNoLabelPresent, Matcher::getAltLabel));
    }
}
