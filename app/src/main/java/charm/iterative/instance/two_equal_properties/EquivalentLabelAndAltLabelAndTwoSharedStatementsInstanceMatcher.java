package charm.iterative.instance.two_equal_properties;

import charm.iterative.instance.EquivalentStringFunctionAndXSharedPropertyInstanceMatcher;

public class EquivalentLabelAndAltLabelAndTwoSharedStatementsInstanceMatcher extends EquivalentStringFunctionAndXSharedPropertyInstanceMatcher {

    @Override
    protected double confidenceScore() { return 0.903; }

    public EquivalentLabelAndAltLabelAndTwoSharedStatementsInstanceMatcher() {
        super(2, individual -> {
            String label = getLabelOrUriEndIfNoLabelPresent(individual);
            String altLabel = getAltLabel(individual);
            if (label == null || altLabel == null) return null;
            return label + STRING_SEPARATOR + altLabel;
        });
    }
}
