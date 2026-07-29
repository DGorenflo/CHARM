package charm.combiner;

import charm.IMatcher;
import charm.util.ConsoleColors;

import java.util.List;

public class IterativeMatcherCombiner extends MatcherCombiner{

    public IterativeMatcherCombiner(List<IMatcher> matchers) {
        super(matchers);
    }

    public IterativeMatcherCombiner(IMatcher... matchers) {
        super(matchers);
    }

    @Override
    public void runIteration() {
        long previousMatches = 0;
        long posteriorMatches = computeTotalMatches(state);
        int iteration = 0;
        while (posteriorMatches > previousMatches){
            iteration++;
            previousMatches = posteriorMatches;
            super.matchers.forEach(this::runMatcher);
            posteriorMatches = computeTotalMatches(state);
            System.out.println(ConsoleColors.YELLOW_BRIGHT + this.getName() + " - total matches increases by " +(posteriorMatches - previousMatches) + " after iteration " + iteration + ((posteriorMatches > previousMatches) ? " -> continue" : " -> abort") + ConsoleColors.RESET);
        }
    }
}
