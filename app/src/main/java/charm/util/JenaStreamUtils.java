package charm.util;

import org.apache.jena.ontology.OntResource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.util.iterator.ExtendedIterator;

import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class JenaStreamUtils {

    public static Stream<Statement> toStream(StmtIterator stmtIterator) {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(stmtIterator, 0), false)
                .onClose(stmtIterator::close);
    }

    public static Stream<? extends OntResource> toStream(ExtendedIterator<? extends OntResource> iterator) {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false)
                .onClose(iterator::close);
    }

}