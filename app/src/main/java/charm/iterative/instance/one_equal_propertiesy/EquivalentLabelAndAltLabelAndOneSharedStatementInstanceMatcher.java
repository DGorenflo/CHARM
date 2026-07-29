package charm.iterative.instance.one_equal_propertiesy;

import charm.iterative.instance.EquivalentStringFunctionAndXSharedPropertyInstanceMatcher;

public class EquivalentLabelAndAltLabelAndOneSharedStatementInstanceMatcher extends EquivalentStringFunctionAndXSharedPropertyInstanceMatcher {

    @Override
    protected double confidenceScore() { return 0.908; }

    public EquivalentLabelAndAltLabelAndOneSharedStatementInstanceMatcher() {
        super(1, individual -> {
            String label = getLabelOrUriEndIfNoLabelPresent(individual);
            String altLabel = getAltLabel(individual);
            if (label == null || altLabel == null) return null;
            return label + STRING_SEPARATOR + altLabel;
        });
    }
}
