package charm.bootstrap.percentage.instance;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.jena.ontology.OntResource;
import charm.Matcher;
import charm.bootstrap.BootstrapMatcher;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Jaccard word-overlap matcher for instances, using an inverted word index
 * instead of an O(n·m) sweep. Runs on all instances regardless of class
 * matching — no class blocking, no dependency on prior class matches.
 *
 * <p>Algorithm (Hotstar-style exact Jaccard via group-count on inverted index):
 * <ol>
 *   <li>Tokenize each unmatched instance's label via
 *       {@link Matcher#splitIntoWords(String)} into a word-set.</li>
 *   <li>Build two inverted indexes: {@code wordToSources} and
 *       {@code wordToTargets}.</li>
 *   <li>Drop words that occur on more than
 *       {@link Matcher#MAX_COLLISIONS_PER_STRING} instances on either side —
 *       boilerplate ("category", "wiki", "template") would otherwise blow up
 *       the candidate set and produce noise.</li>
 *   <li>For each word present on both sides, iterate the small cartesian
 *       {@code wordToSources[w] × wordToTargets[w]} and increment
 *       {@code intersection[(src, tgt)]}. Pairs that share no word are
 *       never visited.</li>
 *   <li>Derive Jaccard algebraically:
 *       {@code jaccard = intersect / (|src| + |tgt| - intersect)}.</li>
 *   <li>Emit a match only when the Jaccard score is ≥ threshold AND is the
 *       strict-unique maximum on both source and target side — same
 *       precision guardrail as {@link charm.bootstrap.percentage.HighestCommonLabelOverlapMatcher}.</li>
 * </ol>
 */
public class JaccardWordOverlapInstanceMatcher extends BootstrapMatcher {

    private static final double THRESHOLD = 0.5;

    @Override
    protected double confidenceScore() { return 0.813; }

    @Override
    public void run() {
        // 1) tokenize
        Map<OntResource, Set<String>> srcWords = tokenize(state.getSourceInstancesUnmatched());
        Map<OntResource, Set<String>> tgtWords = tokenize(state.getTargetInstancesUnmatched());

        // 2) inverted indexes, drop over-cap words per side
        Map<String, Set<OntResource>> wordToSources = invert(srcWords);
        Map<String, Set<OntResource>> wordToTargets = invert(tgtWords);
        dropOverCap(wordToSources);
        dropOverCap(wordToTargets);

        // 3) intersection counts via inverted index — only pairs sharing at
        // least one word are visited.
        Map<Pair<OntResource, OntResource>, Integer> intersect = new HashMap<>();
        for (Map.Entry<String, Set<OntResource>> e : wordToSources.entrySet()) {
            Set<OntResource> tgts = wordToTargets.get(e.getKey());
            if (tgts == null) continue;
            for (OntResource s : e.getValue()) {
                for (OntResource t : tgts) {
                    intersect.merge(Pair.of(s, t), 1, Integer::sum);
                }
            }
        }

        // 4) jaccard + per-side max tracking
        Map<Pair<OntResource, OntResource>, Double> jaccardByPair = new HashMap<>();
        Map<OntResource, Double> maxForSource = new HashMap<>();
        Map<OntResource, Double> maxForTarget = new HashMap<>();
        for (Map.Entry<Pair<OntResource, OntResource>, Integer> e : intersect.entrySet()) {
            OntResource s = e.getKey().getLeft();
            OntResource t = e.getKey().getRight();
            int inter = e.getValue();
            int union = srcWords.get(s).size() + tgtWords.get(t).size() - inter;
            if (union == 0) continue;
            double j = (double) inter / union;
            jaccardByPair.put(e.getKey(), j);
            maxForSource.merge(s, j, Math::max);
            maxForTarget.merge(t, j, Math::max);
        }

        // 5) strict-unique-max filter + threshold
        Map<OntResource, Integer> countMaxSource = new HashMap<>();
        Map<OntResource, Integer> countMaxTarget = new HashMap<>();
        for (Map.Entry<Pair<OntResource, OntResource>, Double> e : jaccardByPair.entrySet()) {
            double v = e.getValue();
            if (Double.compare(v, maxForSource.get(e.getKey().getLeft())) == 0)
                countMaxSource.merge(e.getKey().getLeft(), 1, Integer::sum);
            if (Double.compare(v, maxForTarget.get(e.getKey().getRight())) == 0)
                countMaxTarget.merge(e.getKey().getRight(), 1, Integer::sum);
        }
        for (Map.Entry<Pair<OntResource, OntResource>, Double> e : jaccardByPair.entrySet()) {
            if (e.getValue() < THRESHOLD) continue;
            OntResource s = e.getKey().getLeft();
            OntResource t = e.getKey().getRight();
            boolean uniqSrc = e.getValue().equals(maxForSource.get(s)) && countMaxSource.get(s) == 1;
            boolean uniqTgt = e.getValue().equals(maxForTarget.get(t)) && countMaxTarget.get(t) == 1;
            if (uniqSrc && uniqTgt) createMatch(e.getKey());
        }
    }

    private static Map<OntResource, Set<String>> tokenize(Set<? extends OntResource> instances) {
        Map<OntResource, Set<String>> out = new HashMap<>();
        for (OntResource inst : instances) {
            String label = getLabelOrUriEndIfNoLabelPresent(inst);
            if (label == null) continue;
            Set<String> words = new HashSet<>();
            for (String w : splitIntoWords(label)) {
                if (w == null || w.isEmpty()) continue;
                words.add(w.toLowerCase());
            }
            if (!words.isEmpty()) out.put(inst, words);
        }
        return out;
    }

    private static Map<String, Set<OntResource>> invert(Map<OntResource, Set<String>> forward) {
        Map<String, Set<OntResource>> inv = new HashMap<>();
        for (Map.Entry<OntResource, Set<String>> e : forward.entrySet()) {
            for (String w : e.getValue()) {
                inv.computeIfAbsent(w, k -> new HashSet<>()).add(e.getKey());
            }
        }
        return inv;
    }

    private static void dropOverCap(Map<String, Set<OntResource>> inv) {
        inv.entrySet().removeIf(e -> e.getValue().size() > Matcher.MAX_COLLISIONS_PER_STRING);
    }
}
