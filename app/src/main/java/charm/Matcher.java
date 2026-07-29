package charm;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.jena.ontology.OntClass;
import org.apache.jena.ontology.OntProperty;
import org.apache.jena.ontology.OntResource;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.util.iterator.ExtendedIterator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

public abstract class Matcher implements IMatcher {

    protected MappingState state;
    protected IMatcher parent;

    @Override
    public void init(IMatcher parent, MappingState state) {
        this.state = state;
        this.parent = parent;
    }

    @Override
    public void beforeRun() {

    }

    @Override
    public void reset() {
        this.parent = null;
        this.state = null;
    }

    @Override
    public void afterRun() {

    }

    public static final String STRING_SEPARATOR = "------------";
    public static final String URI_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
    public static final String URI_ALT_LABEL = "http://www.w3.org/2004/02/skos/core#altLabel";

    // Guardrail against cartesian-product explosion in findPairsWithEqualStringFunction:
    // a single string shared by more than this many source instances is treated as noise
    // (empty labels collapsing to a common URI end, boilerplate altLabel, etc.) and dropped.
    public static final int MAX_COLLISIONS_PER_STRING = 100;

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    protected String lowercaseExceptNULL(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase();
    }

    protected static List<String> splitIntoWords(String input) {
        if (input == null) {
            return List.of();
        }

        //Split camelcase
        input = input.replaceAll(
                "(?<=[a-z])(?=[A-Z])", " "
        );

        String[] parts = input.split("[ ,_\\-/:;]|\\. |\\? |! ");
        return Arrays.asList(parts);
    }

    public static double jaccardListOverlap(Collection<String> list1, Collection<String> list2) {
        Set<String> set1 = new HashSet<>(list1);
        Set<String> set2 = new HashSet<>(list2);

        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);
        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);

        if (union.isEmpty()) {
            return 0.0;
        }

        return (double) intersection.size() / union.size();
    }

    protected Stream<Pair<? extends OntResource, ? extends OntResource>> findEquivalentLabelStream(Set<? extends OntResource> source, Set<? extends OntResource> target) {
        return findPairsWithEqualStringFunction(source, target, this::createStringRepresentationBasedOnLabelAndAltLabel);
    }

    protected Stream<Pair<? extends OntResource, ? extends OntResource>> findEquivalentLabelStream(Stream<? extends OntResource> source, Stream<? extends OntResource> target) {
        return findPairsWithEqualStringFunction(source, target, this::createStringRepresentationBasedOnLabelAndAltLabel);
    }


    protected String createStringRepresentationBasedOnLabelAndAltLabel(Resource resource) {
        String label = getLabelOrUriEndIfNoLabelPresent(resource);
        String altLabel = getAltLabel(resource);
        return lowercaseExceptNULL(label) + STRING_SEPARATOR + lowercaseExceptNULL(altLabel);
    }

    public static Stream<Pair<? extends OntResource, ? extends OntResource>> findPairsWithEqualStringFunction(Collection<? extends OntResource> source, Collection<? extends OntResource> target
            , Function<OntResource, String> resourceToStringFunction) {
        return findPairsWithEqualStringFunction(source.stream(), target.stream(), resourceToStringFunction, resourceToStringFunction);
    }

    public static Stream<Pair<? extends OntResource, ? extends OntResource>> findPairsWithEqualStringFunction(Stream<? extends OntResource> source, Stream<? extends OntResource> target
            , Function<OntResource, String> resourceToStringFunction) {
        return findPairsWithEqualStringFunction(source, target, resourceToStringFunction, resourceToStringFunction);
    }

    protected static Stream<Pair<? extends OntResource, ? extends OntResource>> findPairsWithEqualStringFunction(Stream<? extends OntResource> source, Stream<? extends OntResource> target
            , Function<OntResource, String> resourceToStringFunctionForSources, Function<OntResource, String> resourceToStringFunctionForTargets) {
        Map<String, Set<OntResource>> stringMap = new ConcurrentHashMap<>();
        source.parallel().forEach(sourceThing -> {
            String attribute = resourceToStringFunctionForSources.apply(sourceThing);
            if (attribute != null && !attribute.equals(STRING_SEPARATOR)) {
                stringMap.computeIfAbsent(attribute, k -> ConcurrentHashMap.newKeySet()).add(sourceThing);
            }
        });

        // Drop buckets whose cartesian product would blow up memory. A shared string
        // matched by hundreds of source instances is almost always noise
        // (missing labels collapsing to a common URI end, boilerplate altLabel, etc.)
        // and it materialises size(source) * size(target) pairs downstream.
        int removed = 0;
        int totalDroppedSources = 0;
        Iterator<Map.Entry<String, Set<OntResource>>> it = stringMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Set<OntResource>> e = it.next();
            if (e.getValue().size() > MAX_COLLISIONS_PER_STRING) {
                totalDroppedSources += e.getValue().size();
                removed++;
                it.remove();
            }
        }
        if (removed > 0) {
            System.out.println("findPairsWithEqualStringFunction: dropped " + removed
                    + " string bucket(s) exceeding " + MAX_COLLISIONS_PER_STRING
                    + " source collisions (" + totalDroppedSources + " source instances ignored)");
        }

        return target.parallel().filter(targetThing -> {
                    String attribute = resourceToStringFunctionForTargets.apply(targetThing);
                    if (attribute == null) {
                        return false;
                    }
                    return stringMap.containsKey(attribute);
                })
                .flatMap(targetThing -> {
                    String attribute = resourceToStringFunctionForTargets.apply(targetThing);
                    Set<OntResource> ontResources = stringMap.get(attribute);
                    return ontResources.stream().map(ontResource -> Pair.of(ontResource, targetThing));
                });
    }

    public static Stream<Pair<Statement, Statement>> findStatementPairsWithEqualStringFunction(Stream<Statement> source, Stream<Statement> target
            , Function<Statement, String> resourceToStringFunction) {
        return findStatementPairsWithEqualStringFunction(source, target, resourceToStringFunction, resourceToStringFunction);
    }

    protected static Stream<Pair<Statement, Statement>> findStatementPairsWithEqualStringFunction(Stream<Statement> source, Stream<Statement> target
            , Function<Statement, String> resourceToStringFunctionForSources, Function<Statement, String> resourceToStringFunctionForTargets) {
        Map<String, Set<Statement>> stringMap = new ConcurrentHashMap<>();
        source.parallel().forEach(sourceThing -> {
            String attribute = resourceToStringFunctionForSources.apply(sourceThing);
            if (attribute != null) {
                stringMap.computeIfAbsent(attribute, k -> ConcurrentHashMap.newKeySet()).add(sourceThing);
            }
        });

        return target.parallel().filter(targetThing -> {
                    String attribute = resourceToStringFunctionForTargets.apply(targetThing);
                    if (attribute == null) {
                        return false;
                    }
                    return stringMap.containsKey(attribute);
                })
                .flatMap(targetThing -> {
                    String attribute = resourceToStringFunctionForTargets.apply(targetThing);
                    Set<Statement> ontResources = stringMap.get(attribute);
                    return ontResources.stream().map(ontResource -> Pair.of(ontResource, targetThing));
                });
    }


    protected Stream<Pair<? extends OntResource, ? extends OntResource>> findPairsWithEqualStringFunction(Set<? extends OntResource> source, Set<? extends OntResource> target
            , Function<OntResource, String> resourceToStringFunction) {
        return findPairsWithEqualStringFunction(source.stream(), target.stream(), resourceToStringFunction);
    }

    protected Stream<Pair<? extends OntResource, ? extends OntResource>> findPairsWithEqualStringFunction(Set<? extends OntResource> source, Set<? extends OntResource> target
            , Function<OntResource, String> resourceToStringFunctionForSources, Function<OntResource, String> resourceToStringFunctionForTargets) {
        return findPairsWithEqualStringFunction(source.stream(), target.stream(), resourceToStringFunctionForSources, resourceToStringFunctionForTargets);
    }

    protected static String getLabelOrUriEndIfNoLabelPresent(Resource ontResource) {
        Model model = ontResource.getModel();
        Property property = model.getProperty(URI_LABEL);
        ExtendedIterator<String> iterator = ontResource.listProperties(property).mapWith(statement -> statement.getObject().asLiteral().getLexicalForm());
        if (iterator.hasNext()) {
            return iterator.next();
        }
        if (ontResource.getURI() == null) {
            return null;
        }
        String[] uriParts = ontResource.getURI().split("[./#]");
        if (uriParts.length > 0) {
            return uriParts[uriParts.length - 1];
        }
        return null;
    }

    protected static String getAltLabel(Resource ontResource) {
        Model model = ontResource.getModel();
        Property property = model.getProperty(URI_ALT_LABEL);
        ExtendedIterator<String> iterator = ontResource.listProperties(property).mapWith(statement -> statement.getObject().asLiteral().getLexicalForm());
        if (iterator.hasNext()) {
            return iterator.next();
        }
        return null;
    }

    protected double confidenceScore() {
        return 1.0;
    }

    public void createMatch(OntResource source, OntResource target) {
        createMatch(source, target, confidenceScore());
    }

    public void createMatch(OntResource source, OntResource target, double confidence) {
        Pair<? extends OntResource, ? extends OntResource> pair = Pair.of(source, target);
        createMatch(pair, confidence);
    }

    protected void createMatch(Pair<? extends OntResource, ? extends OntResource> pair) {
        createMatch(pair, confidenceScore());
    }

    @SuppressWarnings("unchecked")
    protected void createMatch(Pair<? extends OntResource, ? extends OntResource> pair, double confidence) {
        OntResource source = pair.getLeft();
        OntResource target = pair.getRight();
        if (source instanceof OntClass && target instanceof OntClass) {
            synchronized (this) {
                if (state.isSourceClassStillUnmatched((OntClass) source) && state.isTargetClassStillUnmatched((OntClass) target)) {
                    state.matchClasses(source.asClass(), target.asClass(), confidence);
                    passUpClassMatch((Pair<OntClass, OntClass>) pair);
                }
            }
        } else if (source instanceof OntProperty && target instanceof OntProperty) {
            synchronized (this) {
                if (state.isSourcePropertyStillUnmatched((OntProperty) source) && state.isTargetPropertyStillUnmatched((OntProperty) target)) {
                    state.matchProperties(source.asProperty(), target.asProperty(), confidence);
                    passUpPropertyMatch((Pair<OntProperty, OntProperty>) pair);
                }
            }
        } else {
            synchronized (this) {
                if (state.isSourceInstanceStillUnmatched(source) && state.isTargetInstanceStillUnmatched(target)) {
                    state.matchIndividuals(source, target, confidence);
                    passUpInstanceMatch((Pair<OntResource, OntResource>) pair);
                }
            }
        }
    }

    public static void runAsyncAndWaitForCompletion(Runnable... tasks) {
        Arrays.stream(tasks).parallel().forEach(Runnable::run);
    }

    @Override
    public void passUpClassMatch(Pair<OntClass, OntClass> pair) {
        if (parent != null) {
            parent.passUpClassMatch(pair);
        }
    }

    @Override
    public void passUpPropertyMatch(Pair<OntProperty, OntProperty> pair) {
        if (parent != null) {
            parent.passUpPropertyMatch(pair);
        }
    }

    @Override
    public void passUpInstanceMatch(Pair<OntResource, OntResource> pair) {
        if (parent != null) {
            parent.passUpInstanceMatch(pair);
        }
    }

}
