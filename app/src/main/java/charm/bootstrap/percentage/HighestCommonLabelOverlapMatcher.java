package charm.bootstrap.percentage;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.jena.ontology.OntResource;
import charm.bootstrap.BootstrapMatcher;

import java.util.*;

public abstract class HighestCommonLabelOverlapMatcher extends BootstrapMatcher {

    private static final double threshold = 0.5;

    public void run(Set<? extends OntResource> sourceUnmatched, Set<? extends OntResource> targetUnmatched) {

        Map<Pair<OntResource, OntResource>, Double> overlapPercentages = new HashMap<>();
        Map<OntResource, Double> maxForSource = new HashMap<>();
        Map<OntResource, Double> maxForTarget = new HashMap<>();

        for (OntResource sourceThing : sourceUnmatched) {
            String sourceLabel = getLabelOrUriEndIfNoLabelPresent(sourceThing);
            if(sourceLabel == null){
                continue;
            }
            List<String> sourceLabelWords = splitIntoWords(sourceLabel);
            sourceLabelWords.replaceAll(String::toLowerCase);

            for (OntResource targetThing : targetUnmatched) {
                String targetLabel = getLabelOrUriEndIfNoLabelPresent(targetThing);
                if(targetLabel == null){
                    continue;
                }
                List<String> targetLabelWords = splitIntoWords(targetLabel);
                targetLabelWords.replaceAll(String::toLowerCase);

                double overlap = jaccardListOverlap(sourceLabelWords, targetLabelWords);

                Pair<OntResource, OntResource> p = Pair.of(sourceThing, targetThing);
                overlapPercentages.put(p, overlap);

                maxForSource.merge(sourceThing, overlap, Math::max);
                maxForTarget.merge(targetThing, overlap, Math::max);
            }
        }

        Map<OntResource, Integer> countMaxForSource = new HashMap<>();
        Map<OntResource, Integer> countMaxForTarget = new HashMap<>();

        for (Map.Entry<Pair<OntResource, OntResource>, Double> e : overlapPercentages.entrySet()) {
            Pair<OntResource, OntResource> p = e.getKey();
            double val = e.getValue();

            if (Double.compare(val, maxForSource.get(p.getKey())) == 0) {
                countMaxForSource.merge(p.getKey(), 1, Integer::sum);
            }
            if (Double.compare(val, maxForTarget.get(p.getValue())) == 0) {
                countMaxForTarget.merge(p.getValue(), 1, Integer::sum);
            }
        }

        for (Map.Entry<Pair<OntResource, OntResource>, Double> e : overlapPercentages.entrySet()) {
            Pair<OntResource, OntResource> p = e.getKey();
            double val = e.getValue();

            if (val < threshold) {
                continue;
            }

            OntResource s = p.getKey();
            OntResource t = p.getValue();

            boolean isStrictlyUniqueForSource = val == maxForSource.get(s) && countMaxForSource.get(s) == 1;
            boolean isStrictlyUniqueForTarget = val == maxForTarget.get(t) && countMaxForTarget.get(t) == 1;

            if (isStrictlyUniqueForSource && isStrictlyUniqueForTarget) {
                createMatch(p);
            }
        }
    }
}
