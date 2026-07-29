package charm.util.debug;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.jena.ontology.OntResource;
import org.apache.jena.rdf.model.Statement;
import charm.bootstrap.BootstrapMatcher;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class Test extends BootstrapMatcher {



    @Override
    public void run() {
//        if(true){
//            return;
//        }
        Set<Pair<String, String>> allComputedMatches = state.getAlignment().getConfidenceOrderedMapping().stream().map(correspondence -> Pair.of(correspondence.getEntityOne(), correspondence.getEntityTwo())).collect(Collectors.toSet());
        for (Pair<OntResource, OntResource> pair : FPChecker.getAllTruePositivePairs().stream()
                .filter(pair -> !allComputedMatches.contains(pair))
                .map(stringStringPair -> Pair.of(state.getSourceOntology().getOntResource(stringStringPair.getLeft()), state.getTargetOntology().getOntResource(stringStringPair.getRight())))
                .filter(pair -> pair.getLeft() != null)
                .filter(pair -> pair.getRight() != null)
                .collect(Collectors.toSet())) {
            OntResource targetThing = pair.getRight();

            OntResource sourceThing = pair.getLeft();
            if(!targetThing.getURI().toLowerCase().contains("swg")){
                continue;
            }
//            if(!sourceThing.getURI().contains("/property/") && !sourceThing.getURI().contains("/class/")){
//                continue;
//            }



            Set<Statement> sourceSet = pair.getLeft().listProperties().toSet();
            Set<Statement> targetSet = pair.getRight().listProperties().toSet();

            Set<Pair<OntResource, OntResource>> sourceIsAlreadyMappedTo = state.getMatchedInstances().stream().filter(p -> p.getLeft().getURI().equals(sourceThing.getURI())).collect(Collectors.toSet());
            Set<Pair<OntResource, OntResource>> targetIsAlreadyMappedTo = state.getMatchedInstances().stream().filter(p -> p.getRight().getURI().equals(targetThing.getURI())).collect(Collectors.toSet());

            Set<String> sourceTranslatedStatements = sourceSet.stream().map(statement -> state.computeStringRepresentationBasedOnPredicateObject(statement)).collect(Collectors.toSet());
            Set<String> targetStatements = targetSet.stream().map(statement -> state.computeStringRepresentationBasedOnPredicateObject(statement)).collect(Collectors.toSet());

            Set<String> commonStatements = new HashSet<>(sourceTranslatedStatements);
            commonStatements.retainAll(targetStatements);



            if(!haveSameLastSegment(sourceThing.getURI(), targetThing.getURI())) { //These are probably erros in goldstandard
                System.out.println("A");
            }
        }

        //TODO: Ideas
        //Shared words in label similar to how it works for classes and properties
        //Amount of shared statements

    }

    public static boolean haveSameLastSegment(String a, String b) {
        if (a == null || b == null) return false;

        int lastSlashA = a.lastIndexOf('/');
        int lastSlashB = b.lastIndexOf('/');

        String lastPartA = (lastSlashA >= 0) ? a.substring(lastSlashA + 1) : a;
        String lastPartB = (lastSlashB >= 0) ? b.substring(lastSlashB + 1) : b;

        return lastPartA.equalsIgnoreCase(lastPartB);
    }
}
