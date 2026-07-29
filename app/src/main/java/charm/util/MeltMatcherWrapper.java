package charm.util;

import de.uni_mannheim.informatik.dws.melt.matching_jena.MatcherYAAAJena;
import de.uni_mannheim.informatik.dws.melt.yet_another_alignment_api.Alignment;
import org.apache.jena.ontology.OntModel;
import charm.IMatcher;
import charm.MappingState;

import java.util.Properties;

public class MeltMatcherWrapper extends MatcherYAAAJena {

    private final IMatcher matcher;

    public MeltMatcherWrapper(IMatcher matcher) {
        this.matcher = matcher;
    }

    @Override
    public Alignment match(OntModel source, OntModel target, Alignment inputAlignment, Properties properties) throws Exception {
        MappingState state = new MappingState(source, target, inputAlignment);
        matcher.init(null, state);
        matcher.run();
        matcher.reset();
        return state.getAlignment();
    }
}
