package charm.iterative.instance.one_equal_propertiesy;

import org.apache.commons.lang3.tuple.Pair;
import charm.Matcher;
import charm.iterative.instance.EquivalentStringFunctionAndXSharedPropertyInstanceMatcher;

public class EquivalentCrossAltLabelAndLabelAndOneSharedStatementInstanceMatcher extends EquivalentStringFunctionAndXSharedPropertyInstanceMatcher {

    @Override
    protected double confidenceScore() { return 0.912; }

    public EquivalentCrossAltLabelAndLabelAndOneSharedStatementInstanceMatcher() {
        super(1, Pair.of(Matcher::getAltLabel, Matcher::getLabelOrUriEndIfNoLabelPresent));
    }
}
