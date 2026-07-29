package charm.iterative;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.jena.ontology.OntClass;
import org.apache.jena.ontology.OntProperty;
import org.apache.jena.ontology.OntResource;
import charm.IMatcher;

public interface IIterativeMatcher extends IMatcher {
    void onClassMatch(Pair<OntClass, OntClass> pair);
    void onPropertyMatch(Pair<OntProperty, OntProperty> pair);
    void onInstanceMatch(Pair<OntResource, OntResource> pair);

    void runIteration();
}
