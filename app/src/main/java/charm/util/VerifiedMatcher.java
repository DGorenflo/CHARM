package charm.util;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.jena.ontology.OntClass;
import org.apache.jena.ontology.OntProperty;
import org.apache.jena.ontology.OntResource;

import charm.IMatcher;
import charm.MappingState;
import charm.Matcher;
import charm.bootstrap.semantics.pipeline.LlmDecider;
import charm.iterative.IIterativeMatcher;

import java.util.*;
import java.util.logging.Logger;

/**
 * Wraps another matcher and asks an LLM whether each match the delegate just
 * produced is correct. Matches the LLM rejects are rolled back via the
 * {@code MappingState.unmatch*} API before the outer combiner sees them, so
 * false positives cannot propagate into downstream iterative matchers.
 *
 * <p>Implements IIterativeMatcher so that MatcherCombiner forwards match events
 * to the delegate when it is an iterative matcher — without this, iterative
 * delegates never receive onInstanceMatch/onPropertyMatch/onClassMatch calls and
 * therefore produce zero matches.
 *
 * <p>Fail-open: if the LLM cannot be reached or its response is unparsable,
 * every match is kept — the wrapper never hurts recall on transport failures.
 */
public class VerifiedMatcher extends Matcher implements IIterativeMatcher {

    private static final Logger LOG = Logger.getLogger(VerifiedMatcher.class.getName());

    private final IMatcher delegate;
    private final String llmModel;
    private final int batchSize;
    private final int concurrency;
    private final boolean createDogTags;

    public VerifiedMatcher(IMatcher delegate, String llmModel, int batchSize, int concurrency, boolean createDogTags) {
        this.delegate     = delegate;
        this.llmModel     = llmModel;
        this.batchSize    = batchSize;
        this.concurrency  = concurrency;
        this.createDogTags = createDogTags;
    }

    @Override
    public void init(IMatcher parent, MappingState state) {
        super.init(parent, state);
        delegate.init(this, state);
    }

    @Override
    public void reset() {
        delegate.reset();
        super.reset();
    }

    @Override
    public String getName() {
        return "Verified(" + delegate.getName() + ")";
    }

    @Override
    public void run() {
        // Snapshot pre-existing matches; anything not in this set after the
        // delegate finishes was produced by the delegate and is a verification candidate.
        Set<Pair<OntClass, OntClass>>       beforeClasses    = new HashSet<>(state.getMatchedClasses());
        Set<Pair<OntProperty, OntProperty>> beforeProperties = new HashSet<>(state.getMatchedProperties());
        Set<Pair<OntResource, OntResource>> beforeInstances  = new HashSet<>(state.getMatchedInstances());

        delegate.beforeRun();
        delegate.run();
        delegate.afterRun();

        List<Pair<OntClass, OntClass>>       newClasses    = diff(state.getMatchedClasses(),    beforeClasses);
        List<Pair<OntProperty, OntProperty>> newProperties = diff(state.getMatchedProperties(), beforeProperties);
        List<Pair<OntResource, OntResource>> newInstances  = diff(state.getMatchedInstances(),  beforeInstances);

        int total = newClasses.size() + newProperties.size() + newInstances.size();
        if (total == 0) return;

        List<Pair<OntResource, OntResource>> allPairs = new ArrayList<>(total);
        for (Pair<OntClass, OntClass> p : newClasses)          allPairs.add(Pair.of(p.getLeft(), p.getRight()));
        for (Pair<OntProperty, OntProperty> p : newProperties) allPairs.add(Pair.of(p.getLeft(), p.getRight()));
        allPairs.addAll(newInstances);

        LOG.info(getName() + ": verifying " + total + " new matches (classes=" + newClasses.size()
                + ", properties=" + newProperties.size() + ", instances=" + newInstances.size() + ")");

        List<Boolean> verdicts = new LlmDecider(getName(), llmModel, concurrency, List.of()).verifyBatch(allPairs, batchSize, createDogTags);

        int removed = 0;
        int idx = 0;
        for (Pair<OntClass, OntClass> p : newClasses) {
            if (!verdicts.get(idx++)) { state.unmatchClasses(p.getLeft(), p.getRight()); removed++; }
        }
        for (Pair<OntProperty, OntProperty> p : newProperties) {
            if (!verdicts.get(idx++)) { state.unmatchProperties(p.getLeft(), p.getRight()); removed++; }
        }
        for (Pair<OntResource, OntResource> p : newInstances) {
            if (!verdicts.get(idx++)) { state.unmatchIndividuals(p.getLeft(), p.getRight()); removed++; }
        }

        LOG.info(getName() + ": removed " + removed + "/" + total + " matches after verification");
    }

    private static <T> List<T> diff(Set<T> current, Set<T> before) {
        List<T> out = new ArrayList<>();
        for (T item : current) if (!before.contains(item)) out.add(item);
        return out;
    }

    @Override
    public void runIteration() {
        run();
    }

    @Override
    public void onInstanceMatch(Pair<OntResource, OntResource> pair) {
        if (delegate instanceof IIterativeMatcher)
            ((IIterativeMatcher) delegate).onInstanceMatch(pair);
    }

    @Override
    public void onPropertyMatch(Pair<OntProperty, OntProperty> pair) {
        if (delegate instanceof IIterativeMatcher)
            ((IIterativeMatcher) delegate).onPropertyMatch(pair);
    }

    @Override
    public void onClassMatch(Pair<OntClass, OntClass> pair) {
        if (delegate instanceof IIterativeMatcher)
            ((IIterativeMatcher) delegate).onClassMatch(pair);
    }
}
