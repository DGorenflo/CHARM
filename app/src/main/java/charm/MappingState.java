package charm;

import de.uni_mannheim.informatik.dws.melt.yet_another_alignment_api.Alignment;
import org.apache.jena.rdf.model.RDFNode;
import charm.util.ConsoleColors;
import charm.util.JenaStreamUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.jena.ontology.OntClass;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntProperty;
import org.apache.jena.ontology.OntResource;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Statement;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import static charm.Matcher.*;


public class MappingState {

    private final OntModel sourceOntology;
    private final OntModel targetOntology;
    private final Alignment alignment;
    private final Set<OntClass> sourceClassesUnmatched;
    private final Set<OntClass> targetClassesUnmatched;
    private final Set<OntProperty> sourcePropertiesUnmatched;
    private final Set<OntProperty> targetPropertiesUnmatched;
    private final Set<OntResource> sourceInstancesUnmatched;
    private final Set<OntResource> targetInstancesUnmatched;

    private final Set<Pair<OntClass, OntClass>> matchedClasses;
    private final Set<Pair<OntProperty, OntProperty>> matchedProperties;
    private final Set<Pair<OntResource, OntResource>> matchedInstances;

    private final Map<OntResource, OntClass> instancesMappedToTheirClass;
    private final Map<String, Set<String>> sourceToTargetTranslation;

    private final Map<OntResource, Set<String>> statementRepresentationsRTforTargetInstances;
    private final Map<String, Set<Statement>> statementRepresentationsHTforTargetTriples;
    private final Map<OntProperty, Set<Statement>> statementsForSourceProperty;
    private final Map<OntResource, Set<Statement>> statementsWithSourceInstanceAsObjectProperty;

    private boolean fullyInitialized = false;

    public MappingState(OntModel sourceOntology, OntModel targetOntology, Alignment alignment) {
        this.sourceOntology = sourceOntology;
        this.targetOntology = targetOntology;
        this.alignment = alignment;

        // Some ontologies (e.g. Brick) use OWL 2 punning: the same URI is declared as both a
        // class and an individual. In Jena's default strict mode, listIndividuals() then throws
        // ConversionException when it hits such a node. Disabling strict mode makes .as(...) fall
        // back to a lenient conversion instead of failing the whole match.
        sourceOntology.setStrictMode(false);
        targetOntology.setStrictMode(false);

        //This has to be run before initializing allStatementRepresentationsForTargetEntity, statementsForSourceProperty, statementsWithSourceAsObjectProperty
        createOntProperties();
        createOntResources();
        this.sourcePropertiesUnmatched = new HashSet<>(sourceOntology.listAllOntProperties().toSet());
        this.targetPropertiesUnmatched = new HashSet<>(targetOntology.listAllOntProperties().toSet());
        this.sourceClassesUnmatched = new HashSet<>(sourceOntology.listClasses().toSet());
        this.targetClassesUnmatched = new HashSet<>(targetOntology.listClasses().toSet());

        this.sourceInstancesUnmatched = new HashSet<>();
        sourceOntology.listIndividuals().forEach(individual -> {
            if (!individual.isAnon()) {
                this.sourceInstancesUnmatched.add(sourceOntology.getOntResource(individual.getURI()));
            }
        });

        this.targetInstancesUnmatched = new HashSet<>();
        targetOntology.listIndividuals().forEach(individual -> {
            if (!individual.isAnon()) {
                this.targetInstancesUnmatched.add(targetOntology.getOntResource(individual.getURI()));
            }
        });

        this.matchedClasses = new HashSet<>();
        this.matchedProperties = new HashSet<>();
        this.matchedInstances = new HashSet<>();

        this.sourceToTargetTranslation = new HashMap<>();

        this.instancesMappedToTheirClass = new HashMap<>();

        statementRepresentationsRTforTargetInstances = new HashMap<>();
        statementRepresentationsHTforTargetTriples = new HashMap<>();
        statementsForSourceProperty = new HashMap<>();
        statementsWithSourceInstanceAsObjectProperty = new HashMap<>();

        Matcher.runAsyncAndWaitForCompletion(
                () -> {
                    sourceOntology.listClasses().forEach(clazz -> {
                        clazz.listInstances().forEach(instance -> {
                            instancesMappedToTheirClass.put(instance, clazz);
                        });
                    });
                    targetOntology.listClasses().forEach(clazz -> {
                        clazz.listInstances().forEach(instance -> {
                            instancesMappedToTheirClass.put(instance, clazz);
                        });
                    });
                },
                () -> JenaStreamUtils.toStream(sourceOntology.listIndividuals())
                        .forEach(individual -> {
                            JenaStreamUtils.toStream(individual.listProperties())
                                    .filter(statement -> !isLabelOrAltLabel(statement.getPredicate()))
                                    .forEach(statement -> {
                                        if (statement.getObject().isResource()) {
                                            statementsWithSourceInstanceAsObjectProperty.computeIfAbsent(sourceOntology.getOntResource(statement.getObject().asResource()), k -> new HashSet<>()).add(statement);
                                        }
                                    });
                        }),
                () -> JenaStreamUtils.toStream(sourceOntology.listIndividuals())
                        .forEach(individual -> {
                            JenaStreamUtils.toStream(individual.listProperties())
                                    .filter(statement -> !isLabelOrAltLabel(statement.getPredicate()))
                                    .forEach(statement -> {
                                        statementsForSourceProperty.computeIfAbsent(sourceOntology.getOntProperty(statement.getPredicate().getURI()), k -> new HashSet<>()).add(statement);
                                    });
                        }),
                () -> JenaStreamUtils.toStream(targetOntology.listIndividuals())
                        .forEach(individual -> {
                            Set<String> statementRepresentationsForThisIndividual = statementRepresentationsRTforTargetInstances.computeIfAbsent(individual, k -> new HashSet<>());
                            JenaStreamUtils.toStream(individual.listProperties())
                                    .filter(statement -> !isLabelOrAltLabel(statement.getPredicate()))
                                    .forEach(statement -> {
                                        statementRepresentationsForThisIndividual.add(computeStringRepresentationBasedOnPredicateObject(statement));
                                    });
                        }),
                () -> JenaStreamUtils.toStream(targetOntology.listStatements())
                        .forEach(statement -> {
                            statementRepresentationsHTforTargetTriples.computeIfAbsent(
                                computeStatementRepresentationBasedOnSubjectObject(statement), k -> new HashSet<>()).add(statement);
                        })




        );

        fullyInitialized = true;
        System.out.println(ConsoleColors.PURPLE_BOLD_BRIGHT + "MappingState got initialized successfully!" + ConsoleColors.RESET);
    }

    private void createOntProperties() {
        ForkJoinPool forkJoinPool = ForkJoinPool.commonPool();
        CompletableFuture<Void> sourceFuture = CompletableFuture.runAsync(() -> {
            Set<String> propertiesToCreate = JenaStreamUtils.toStream(sourceOntology.listStatements())
                    .parallel()
                    .map(statement -> statement.getPredicate().getURI())
                    .filter(propertyUri -> sourceOntology.getOntProperty(propertyUri) == null)
                    .collect(Collectors.toSet());
            propertiesToCreate.forEach(sourceOntology::createOntProperty);
        }, forkJoinPool);
        CompletableFuture<Void> targetFuture = CompletableFuture.runAsync(() -> {
            Set<String> propertiesToCreate = JenaStreamUtils.toStream(targetOntology.listStatements())
                    .parallel()
                    .map(statement -> statement.getPredicate().getURI())
                    .filter(propertyUri -> targetOntology.getOntProperty(propertyUri) == null)
                    .collect(Collectors.toSet());
            propertiesToCreate.forEach(targetOntology::createOntProperty);
        }, forkJoinPool);
        CompletableFuture.allOf(sourceFuture, targetFuture).join();
    }

    private void createOntResources() {
        ForkJoinPool forkJoinPool = ForkJoinPool.commonPool();
        CompletableFuture<Void> sourceFuture = CompletableFuture.runAsync(() -> {
            Set<String> resourcesToCreate = JenaStreamUtils.toStream(sourceOntology.listStatements())
                    .parallel()
                    .map(statement -> statement.getSubject())
                    .filter(subject -> !subject.isAnon())
                    .map(subject -> subject.getURI())
                    .filter(propertyUri -> sourceOntology.getOntProperty(propertyUri) == null)
                    .collect(Collectors.toSet());
            resourcesToCreate.forEach(sourceOntology::createOntResource);
        }, forkJoinPool);
        CompletableFuture<Void> targetFuture = CompletableFuture.runAsync(() -> {
            Set<String> resourcesToCreate = JenaStreamUtils.toStream(targetOntology.listStatements())
                    .parallel()
                    .map(statement -> statement.getSubject())
                    .filter(subject -> !subject.isAnon())
                    .map(subject -> subject.getURI())
                    .filter(propertyUri -> targetOntology.getOntProperty(propertyUri) == null)
                    .collect(Collectors.toSet());
            resourcesToCreate.forEach(targetOntology::createOntResource);
        }, forkJoinPool);
        CompletableFuture.allOf(sourceFuture, targetFuture).join();

        sourceFuture = CompletableFuture.runAsync(() -> {
            Set<String> resourcesToCreate = JenaStreamUtils.toStream(sourceOntology.listStatements())
                    .parallel()
                    .filter(statement -> statement.getObject().isResource())
                    .map(statement -> statement.getObject().asResource())
                    .filter(resource -> !resource.isAnon())
                    .map(resource -> resource.getURI())
                    .filter(propertyUri -> sourceOntology.getOntProperty(propertyUri) == null)
                    .collect(Collectors.toSet());
            resourcesToCreate.forEach(sourceOntology::createOntResource);
        }, forkJoinPool);
        targetFuture = CompletableFuture.runAsync(() -> {
            Set<String> resourcesToCreate = JenaStreamUtils.toStream(targetOntology.listStatements())
                    .parallel()
                    .filter(statement -> statement.getObject().isResource())
                    .map(statement -> statement.getObject().asResource())
                    .filter(resource -> !resource.isAnon())
                    .map(resource -> resource.getURI())
                    .filter(propertyUri -> targetOntology.getOntProperty(propertyUri) == null)
                    .collect(Collectors.toSet());
            resourcesToCreate.forEach(targetOntology::createOntResource);
        }, forkJoinPool);
        CompletableFuture.allOf(sourceFuture, targetFuture).join();

    }


    public String computeStringRepresentationBasedOnPredicateObject(Statement statement) {
        if (statement.getObject().isResource()) {
            return translateIfPossibleElseReturnOriginal(statement.getPredicate().getURI()) + STRING_SEPARATOR + translateIfPossibleElseReturnOriginal(statement.getObject().asResource().getURI());
        }
        return translateIfPossibleElseReturnOriginal(statement.getPredicate().getURI()) + STRING_SEPARATOR + statement.getObject().asLiteral().getLexicalForm();
    }

    public String computeStatementRepresentationBasedOnSubjectObject(Statement statement) {
        if (statement.getObject().isResource()) {
            return translateIfPossibleElseReturnOriginal(statement.getSubject().getURI()) + STRING_SEPARATOR + translateIfPossibleElseReturnOriginal(statement.getObject().asResource().getURI());
        }
        return translateIfPossibleElseReturnOriginal(statement.getSubject().getURI()) + STRING_SEPARATOR + statement.getObject().asLiteral().getLexicalForm();
    }

    public OntClass getClass(OntResource instance) {
        return instancesMappedToTheirClass.get(instance);
    }

    public void matchClasses(OntClass sourceClass, OntClass targetClass) {
        matchClasses(sourceClass, targetClass, 1.0);
    }

    public void matchClasses(OntClass sourceClass, OntClass targetClass, double confidence) {
        addToAlignment(sourceClass.getURI(), targetClass.getURI(), confidence);
        sourceClassesUnmatched.remove(sourceClass);
        targetClassesUnmatched.remove(targetClass);
        matchedClasses.add(Pair.of(sourceClass, targetClass));
        sourceToTargetTranslation.computeIfAbsent(sourceClass.getURI(), k -> new HashSet<>()).add(targetClass.getURI());
    }

    public void matchProperties(OntProperty sourceProperty, OntProperty targetProperty) {
        matchProperties(sourceProperty, targetProperty, 1.0);
    }

    public void matchProperties(OntProperty sourceProperty, OntProperty targetProperty, double confidence) {
        addToAlignment(sourceProperty.getURI(), targetProperty.getURI(), confidence);
        sourcePropertiesUnmatched.remove(sourceProperty);
        targetPropertiesUnmatched.remove(targetProperty);
        matchedProperties.add(Pair.of(sourceProperty, targetProperty));
        sourceToTargetTranslation.computeIfAbsent(sourceProperty.getURI(), k -> new HashSet<>()).add(targetProperty.getURI());
    }

    public void matchIndividuals(OntResource sourceIndividual, OntResource targetIndividual) {
        matchIndividuals(sourceIndividual, targetIndividual, 1.0);
    }

    public void matchIndividuals(OntResource sourceIndividual, OntResource targetIndividual, double confidence) {
        addToAlignment(sourceIndividual.getURI(), targetIndividual.getURI(), confidence);
        sourceInstancesUnmatched.remove(sourceIndividual);
        targetInstancesUnmatched.remove(targetIndividual);
        matchedInstances.add(Pair.of(sourceIndividual, targetIndividual));
        sourceToTargetTranslation.computeIfAbsent(sourceIndividual.getURI(), k -> new HashSet<>()).add(targetIndividual.getURI());
    }

    public void unmatchClasses(OntClass sourceClass, OntClass targetClass) {
        alignment.removeCorrespondencesSourceTarget(sourceClass.getURI(), targetClass.getURI());
        sourceClassesUnmatched.add(sourceClass);
        targetClassesUnmatched.add(targetClass);
        matchedClasses.remove(Pair.of(sourceClass, targetClass));
        Set<String> targets = sourceToTargetTranslation.get(sourceClass.getURI());
        if (targets != null) targets.remove(targetClass.getURI());
    }

    public void unmatchProperties(OntProperty sourceProperty, OntProperty targetProperty) {
        alignment.removeCorrespondencesSourceTarget(sourceProperty.getURI(), targetProperty.getURI());
        sourcePropertiesUnmatched.add(sourceProperty);
        targetPropertiesUnmatched.add(targetProperty);
        matchedProperties.remove(Pair.of(sourceProperty, targetProperty));
        Set<String> targets = sourceToTargetTranslation.get(sourceProperty.getURI());
        if (targets != null) targets.remove(targetProperty.getURI());
    }

    public void unmatchIndividuals(OntResource sourceIndividual, OntResource targetIndividual) {
        alignment.removeCorrespondencesSourceTarget(sourceIndividual.getURI(), targetIndividual.getURI());
        sourceInstancesUnmatched.add(sourceIndividual);
        targetInstancesUnmatched.add(targetIndividual);
        matchedInstances.remove(Pair.of(sourceIndividual, targetIndividual));
        Set<String> targets = sourceToTargetTranslation.get(sourceIndividual.getURI());
        if (targets != null) targets.remove(targetIndividual.getURI());
    }

    private void addToAlignment(String uri1, String uri2, double confidence) {
        this.alignment.add(uri1, uri2, confidence);
    }


    public String translateIfPossibleElseReturnOriginal(String uri) {
        return sourceToTargetTranslation.getOrDefault(uri, Set.of()).stream().findFirst().orElse(uri);
    }

    public Set<Pair<OntClass, OntClass>> getMatchedClasses() {
        return matchedClasses;
    }

    public Set<Pair<OntResource, OntResource>> getMatchedInstances() {
        return matchedInstances;
    }

    public Set<Pair<OntProperty, OntProperty>> getMatchedProperties() {
        return matchedProperties;
    }

    public Alignment getAlignment() {
        return alignment;
    }

    public Set<OntClass> getSourceClassesUnmatched() {
        return sourceClassesUnmatched;
    }

    public Set<OntClass> getTargetClassesUnmatched() {
        return targetClassesUnmatched;
    }

    public Set<OntProperty> getSourcePropertiesUnmatched() {
        return sourcePropertiesUnmatched;
    }

    public Set<OntProperty> getTargetPropertiesUnmatched() {
        return targetPropertiesUnmatched;
    }

    public Set<OntResource> getSourceInstancesUnmatched() {
        return sourceInstancesUnmatched;
    }

    public Set<OntResource> getTargetInstancesUnmatched() {
        return targetInstancesUnmatched;
    }

    public OntModel getSourceOntology() {
        return sourceOntology;
    }

    public OntModel getTargetOntology() {
        return targetOntology;
    }

    public boolean isSourceClassStillUnmatched(OntClass sourceClass) {
        return sourceClassesUnmatched.contains(sourceClass);
    }

    public boolean isTargetClassStillUnmatched(OntClass targetClass) {
        return targetClassesUnmatched.contains(targetClass);
    }

    public boolean isSourceInstanceStillUnmatched(OntResource sourceInstance) {
        return sourceInstancesUnmatched.contains(sourceInstance);
    }

    public boolean isSourceObjectInstanceAndStillUnmatched(RDFNode object) {
        return object.isResource() && isSourceInstanceStillUnmatched(object.asResource().as(OntResource.class));
    }

    public boolean isTargetInstanceStillUnmatched(OntResource targetInstance) {
        return targetInstancesUnmatched.contains(targetInstance);
    }

    public boolean isTargetObjectInstanceAndStillUnmatched(RDFNode object) {
        return object.isResource() && isTargetInstanceStillUnmatched(object.asResource().as(OntResource.class));
    }
    public boolean isSourcePropertyStillUnmatched(OntProperty sourceProperty) {
        return sourcePropertiesUnmatched.contains(sourceProperty);
    }

    public boolean isTargetPropertyStillUnmatched(OntProperty targetProperty) {
        return targetPropertiesUnmatched.contains(targetProperty);
    }

    public boolean isSourcePropertyStillUnmatched(Property property) {
        return isSourcePropertyStillUnmatched(sourceOntology.getOntProperty(property.getURI()));
    }

    public boolean isTargetPropertyStillUnmatched(Property property) {
        return isTargetPropertyStillUnmatched(targetOntology.getOntProperty(property.getURI()));
    }

    private static boolean isLabelOrAltLabel(Property predicate) {
        return predicate.getURI().equals(URI_LABEL) || predicate.getURI().equals(URI_ALT_LABEL);
    }

    public Map<OntResource, Set<String>> getStatementRepresentationsRTforTargetInstancesMap() {
        return statementRepresentationsRTforTargetInstances;
    }

    public Map<String, Set<Statement>> getStatementRepresentationsHTforTargetTriplesMap() {
        return statementRepresentationsHTforTargetTriples;
    }

    public Map<OntProperty, Set<Statement>> getStatementsForSourcePropertyMap() {
        return statementsForSourceProperty;
    }

    public Map<OntResource, Set<Statement>> getStatementsWithSourceInstanceAsObjectPropertyMap() {
        return statementsWithSourceInstanceAsObjectProperty;
    }
}
