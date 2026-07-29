package charm.bootstrap.equivalence.property;

import org.apache.jena.rdf.model.Resource;
import charm.bootstrap.BootstrapMatcher;

import java.util.Set;

public class EquivalentURIPropertyLabelMatcher extends BootstrapMatcher {

    @Override
    protected double confidenceScore() { return 0.801; }

    @Override
    public void run() {
        findPairsWithEqualStringFunction(Set.copyOf(state.getSourcePropertiesUnmatched()), Set.copyOf(state.getTargetPropertiesUnmatched()), Resource::getURI).forEach(this::createMatch);
    }
}
