package charm;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.jena.ontology.OntClass;
import org.apache.jena.ontology.OntProperty;
import org.apache.jena.ontology.OntResource;

public interface IMatcher {

    void init(IMatcher parent, MappingState state);
    void reset();
    void beforeRun();
    void afterRun();
    void run();
    String getName();

    void passUpClassMatch(Pair<OntClass, OntClass> pair);
    void passUpPropertyMatch(Pair<OntProperty, OntProperty> pair);
    void passUpInstanceMatch(Pair<OntResource, OntResource> pair);

}
