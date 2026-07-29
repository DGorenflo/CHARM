package charm.combiner;

import charm.IMatcher;
import charm.MappingState;
import charm.util.ConsoleColors;

import java.util.*;

public class IterativeTieredMatcherWIthBootstrapMatchersWhenNothingIsFound extends IterativeMatcherCombiner {


    private final List<IMatcher> bootstrapMatchers;

    public IterativeTieredMatcherWIthBootstrapMatchersWhenNothingIsFound(List<IMatcher> iterativeMatchers, List<IMatcher> bootstrapMatchers) {
        super(iterativeMatchers);
        this.bootstrapMatchers = bootstrapMatchers;
    }

    @Override
    public void init(IMatcher parent, MappingState state) {
        super.init(parent, state);
        bootstrapMatchers.stream().parallel().forEach(bootstrapMatcher -> bootstrapMatcher.init(this, state));
    }

    @Override
    public void reset() {
        super.reset();
        bootstrapMatchers.stream().parallel().forEach(IMatcher::reset);
    }

    @Override
    public void runIteration() {
        for (IMatcher bootstrapMatcher : bootstrapMatchers) {
            System.out.println("Running bootstrap matcher " + bootstrapMatcher.getName());
            int before = computeTotalMatches(state);
            runMatcher(bootstrapMatcher);
            int after = computeTotalMatches(state);
            if(after > before){
                runTieredIterativeMatchers();
            }
        }
    }

    private void runTieredIterativeMatchers() {
        int iteration = 0;
        int matcherIndex = 0;
        int totalMatchers = super.matchers.size();
        Map<IMatcher, Integer> foundMatchesCounter = new LinkedHashMap<>();

        while (matcherIndex < totalMatchers) {
            iteration++;

            int before = computeTotalMatches(state);
            runMatcher(super.matchers.get(matcherIndex));
            int after = computeTotalMatches(state);
            foundMatchesCounter.putIfAbsent(matchers.get(matcherIndex), 0);
            foundMatchesCounter.put(matchers.get(matcherIndex), foundMatchesCounter.get(matchers.get(matcherIndex)) + (after - before));

            if (after > before) {
                // Found new matches -> restart from first matcher
                System.out.println(ConsoleColors.YELLOW_BRIGHT + this.getName()
                        + " - matcher " + matcherIndex + " added " + (after - before)
                        + " new matches in iteration " + iteration + " -> restart"
                        + ConsoleColors.RESET);

                matcherIndex = 0; // reset (go back to first matcher)
            } else {
                // No new matches -> move to next matcher
                System.out.println(ConsoleColors.YELLOW_BRIGHT + this.getName()
                        + " - matcher " + matcherIndex + " added 0 matches in iteration "
                        + iteration + " -> continue"
                        + ConsoleColors.RESET);

                matcherIndex++;
            }
        }

        System.out.println(ConsoleColors.GREEN_BRIGHT + this.getName()
                + " - no new matches in a full pass -> done"
                + ConsoleColors.RESET);

        foundMatchesCounter.forEach((iMatcher, integer) -> System.out.println(iMatcher.getName() + " contributed " + integer + " matches"));
    }
}
