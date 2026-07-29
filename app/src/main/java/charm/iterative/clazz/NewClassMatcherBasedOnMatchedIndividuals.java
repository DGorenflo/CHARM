package charm.iterative.clazz;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.jena.ontology.OntClass;
import org.apache.jena.ontology.OntResource;
import charm.IMatcher;
import charm.MappingState;
import charm.iterative.templates.IterativeClassAndInstanceMatchListenerMatcher;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class NewClassMatcherBasedOnMatchedIndividuals extends IterativeClassAndInstanceMatchListenerMatcher {

    @Override
    protected double confidenceScore() { return 0.931; }

    private static final double minimumThreshold = 0.2;
    private Map<OntClass, Integer> targetClassOccurrences;
    private Map<OntClass, Integer> sourceClassOccurrences;
    private Map<Pair<OntClass, OntClass>, Integer> matchIndividualCounter;

    private Set<Pair<OntClass, OntClass>> currentlyIncludedInMean;
    private Map<Pair<OntClass, OntClass>, Double> lastComputedRate;
    private double meanForMatchedClassesApproximation;
    private Queue<Pair<OntClass, OntClass>> nextToUpdateScore;

    private Map<OntClass, Double> highestScores = new HashMap<>();
    private Map<OntClass, Double> secondHighestScores = new HashMap<>();

    @Override
    public void init(IMatcher parent, MappingState state) {
        super.init(parent, state);
        matchIndividualCounter = new ConcurrentHashMap<>();
        targetClassOccurrences = new ConcurrentHashMap<>();
        sourceClassOccurrences = new ConcurrentHashMap<>();
        currentlyIncludedInMean = new HashSet<>();
        lastComputedRate = new HashMap<>(); //This must not be concurrent
        meanForMatchedClassesApproximation = 0;
        nextToUpdateScore = new LinkedList<>();
        highestScores = new HashMap<>();
        secondHighestScores = new HashMap<>();
    }

    @Override
    public void reset() {
        super.reset();
        matchIndividualCounter = null;
        targetClassOccurrences = null;
        sourceClassOccurrences = null;
        currentlyIncludedInMean = null;
        lastComputedRate = null;
        meanForMatchedClassesApproximation = 0;
        nextToUpdateScore = null;
        highestScores = null;
        secondHighestScores = null;
    }

    @Override
    public void runIteration() {
        getInstancesMatchedSinceLastIteration().stream()
                .filter(pair -> pair.getLeft().isIndividual() && pair.getRight().isIndividual())
                .forEach(pair -> {
            OntResource source = pair.getLeft();
            OntResource target = pair.getRight();
            OntClass sourceType = source.asIndividual().getOntClass();
            OntClass targetType = target.asIndividual().getOntClass();
            if (sourceType != null && targetType != null) {
                Pair<OntClass, OntClass> classPair = Pair.of(sourceType, targetType);
                matchIndividualCounter.merge(classPair, 1, Integer::sum);
                targetClassOccurrences.merge(targetType, 1, Integer::sum);
                sourceClassOccurrences.merge(sourceType, 1, Integer::sum);
            }
        });

        getInstancesMatchedSinceLastIteration().stream()
                .filter(pair -> pair.getLeft().isIndividual() && pair.getRight().isIndividual())
                .forEach(pair -> {
            OntResource source = pair.getLeft();
            OntResource target = pair.getRight();
            OntClass sourceType = source.asIndividual().getOntClass();
            OntClass targetType = target.asIndividual().getOntClass();
            //Update this pairs score
            if (sourceType != null && targetType != null) {
                Pair<OntClass, OntClass> classPair = Pair.of(sourceType, targetType);
                if (state.getMatchedClasses().contains(classPair)) {
                    updateRateAndCurrentMeanForAlreadyMatchedClasses(classPair);
                }
            }

            //Update another pairs score
            for (int i = 0; i < 5; i++) { //1 is not enough
                Pair<OntClass, OntClass> classPairToUpdate = nextToUpdateScore.poll();
                if (classPairToUpdate != null) {
                    updateRateAndCurrentMeanForAlreadyMatchedClasses(classPairToUpdate);
                }
            }
        });

        if (meanForMatchedClassesApproximation < minimumThreshold) {
            return;
        }

        //From here on everything is still O(matched instances)
        Set<Pair<Pair<OntClass, OntClass>, Double>> classesOfMatchedPairsWithTheirRate = getInstancesMatchedSinceLastIteration().stream()
                .filter(pair -> state.getClass(pair.getLeft()) != null)
                .filter(pair -> state.getClass(pair.getRight()) != null)
                .map(instancePair -> Pair.of(state.getClass(instancePair.getLeft()), state.getClass(instancePair.getRight())))
                .filter(pair -> state.isSourceClassStillUnmatched(pair.getLeft()))
                .filter(pair -> state.isTargetClassStillUnmatched(pair.getRight()))
                .map(pair -> Pair.of(pair, computeRate(pair)))
                .filter(pairDoublePair -> pairDoublePair.getRight() > meanForMatchedClassesApproximation)
                .collect(Collectors.toSet());

        classesOfMatchedPairsWithTheirRate
                .forEach(pairDoublePair -> {
                    Pair<OntClass, OntClass> clazzPair = pairDoublePair.getLeft();
                    OntClass sourceClass = clazzPair.getLeft();
                    OntClass targetClass = clazzPair.getRight();
                    double rate = pairDoublePair.getRight();
                    double sourceHighestScore = highestScores.getOrDefault(sourceClass, 0.0);
                    double targetHighestScore = highestScores.getOrDefault(targetClass, 0.0);
                    double sourceSecondHighestScore = secondHighestScores.getOrDefault(sourceClass, 0.0);
                    double targetSecondHighestScore = secondHighestScores.getOrDefault(targetClass, 0.0);
                    if(rate > sourceHighestScore){
                        highestScores.put(sourceClass, rate);
                        secondHighestScores.put(sourceClass, sourceHighestScore);
                    }else if (rate > sourceSecondHighestScore){
                        secondHighestScores.put(sourceClass, rate);
                    }
                    if(rate > targetHighestScore){
                        highestScores.put(targetClass, rate);
                        secondHighestScores.put(targetClass, targetHighestScore);
                    }else if (rate > targetSecondHighestScore){
                        secondHighestScores.put(targetClass, rate);
                    }
                });
        classesOfMatchedPairsWithTheirRate
                .forEach(pairDoublePair -> {
                    Pair<OntClass, OntClass> clazzPair = pairDoublePair.getLeft();
                    OntClass sourceClass = clazzPair.getLeft();
                    OntClass targetClass = clazzPair.getRight();
                    double rate = pairDoublePair.getRight();
                    double sourceSecondHighestScore = secondHighestScores.getOrDefault(sourceClass, 0.0);
                    double targetSecondHighestScore = secondHighestScores.getOrDefault(targetClass, 0.0);
                    if(rate > sourceSecondHighestScore && rate > targetSecondHighestScore){
                        createMatch(clazzPair);
                    }
                });


    }

    private void updateRateAndCurrentMeanForAlreadyMatchedClasses(Pair<OntClass, OntClass> pair) {
        double rateOfThisPair = computeRate(pair);
        if (this.lastComputedRate.containsKey(pair) && rateOfThisPair == lastComputedRate.get(pair)) { //In this case the update is skipped as the value does not change; for numerical stability
            if(lastComputedRate.get(pair) >= minimumThreshold) {
                nextToUpdateScore.offer(pair);
            }
            return;
        }

        if (currentlyIncludedInMean.contains(pair)) {
            //Remove influence of this pair
            int classesIncluded = currentlyIncludedInMean.size();
            currentlyIncludedInMean.remove(pair);
            double totalScore = meanForMatchedClassesApproximation * classesIncluded;
            double valueThisClassIsIncludedInWithTheMean = lastComputedRate.getOrDefault(pair, 0.0);
            double totalScoreWithoutThisClassesPreviousValue = totalScore - valueThisClassIsIncludedInWithTheMean;
            if (classesIncluded == 1) {
                meanForMatchedClassesApproximation = 0;
            } else {
                //At least one other class -> division is feasible
                double meanWithoutThisClass = totalScoreWithoutThisClassesPreviousValue / (classesIncluded - 1);
                meanForMatchedClassesApproximation = meanWithoutThisClass;
            }
        }
        this.lastComputedRate.put(pair, rateOfThisPair);
        if (rateOfThisPair > minimumThreshold) { //Discard values under 10%, so do not include this pair
            int classesIncluded = currentlyIncludedInMean.size();
            currentlyIncludedInMean.add(pair);
            double totalScore = meanForMatchedClassesApproximation * classesIncluded;
            double valueThisClassIsIncludedInWithTheMean = totalScore + rateOfThisPair;
            double updatedMean = valueThisClassIsIncludedInWithTheMean / (classesIncluded + 1);
            meanForMatchedClassesApproximation = updatedMean;
            boolean offer = nextToUpdateScore.offer(pair);//Put this in the update queue
        }
    }

    private double computeRate(Pair<OntClass, OntClass> pair) {
        if (!sourceClassOccurrences.containsKey(pair.getLeft()) || !targetClassOccurrences.containsKey(pair.getRight()) || !matchIndividualCounter.containsKey(pair)) {
            return 0.0;
        }
        int sourceCounter = sourceClassOccurrences.get(pair.getLeft());
        int targetCounter = targetClassOccurrences.get(pair.getRight());
        int sourceAndTargetCounter = matchIndividualCounter.get(pair);
        return computeRate(sourceAndTargetCounter, sourceCounter, targetCounter);
    }

    private static double computeRate(int sourceAndTargetCounter, int sourceCounter, int targetCounter) {
        return (double) (2 * sourceAndTargetCounter) / (sourceCounter + targetCounter);
    }

}
