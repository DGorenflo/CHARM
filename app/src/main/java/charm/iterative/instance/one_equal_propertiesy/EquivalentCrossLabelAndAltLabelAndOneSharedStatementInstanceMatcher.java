package charm.iterative.instance.one_equal_propertiesy;

import org.apache.commons.lang3.tuple.Pair;
import charm.Matcher;
import charm.iterative.instance.EquivalentStringFunctionAndXSharedPropertyInstanceMatcher;

public class EquivalentCrossLabelAndAltLabelAndOneSharedStatementInstanceMatcher extends EquivalentStringFunctionAndXSharedPropertyInstanceMatcher {

    @Override
    protected double confidenceScore() { return 0.911; }

    public EquivalentCrossLabelAndAltLabelAndOneSharedStatementInstanceMatcher() {
        super(1, Pair.of(Matcher::getLabelOrUriEndIfNoLabelPresent, Matcher::getAltLabel));
    }
}
