package charm;

import charm.bootstrap.BootstrapMatcher;
import charm.bootstrap.equivalence.clazz.EquivalentClassLabelMatcher;
import charm.bootstrap.equivalence.instance.EquivalentLabelAndAltLabelInstanceMatcher;
import charm.bootstrap.equivalence.instance.UniqueWordInAnyLiteralInstanceMatcher;
import charm.bootstrap.equivalence.property.EquivalentPropertyLabelAndAltLabelMatcher;
import charm.bootstrap.equivalence.property.EquivalentURIPropertyLabelMatcher;
import charm.bootstrap.percentage.clazz.HighestCommonWordPercentageInClassLabelMatcher;
import charm.bootstrap.equivalence.instance.UniqueSharedStatementInstanceMatcher;
import charm.bootstrap.percentage.instance.JaccardWordOverlapInstanceMatcher;
import charm.bootstrap.percentage.property.HighestCommonWordPercentageInRelationLabelMatcher;
import charm.combiner.IterativeTieredMatcherWIthBootstrapMatchersWhenNothingIsFound;
import charm.combiner.SequentialMatcherCombiner;
import charm.iterative.clazz.NewClassMatcherBasedOnMatchedIndividuals;
import charm.iterative.instance.EquivalentInstanceLabelMatcherForSameClass;
import charm.iterative.instance.InstancesWithCommonUniqueStatementMatcher;
import charm.iterative.instance.RelatedToExistingMatchAndEquivalentLabelInstanceMatcher;
import charm.iterative.instance.one_equal_propertiesy.*;
import charm.iterative.instance.two_equal_properties.*;
import charm.iterative.property.MatchPropertiesBasedOnUniqueSubjectObjectCombinations;
import charm.util.MeltMatcherWrapper;
import charm.util.VerifiedMatcher;
import charm.bootstrap.semantics.LLMMatcher;

import java.util.EnumSet;
import java.util.List;

import charm.bootstrap.semantics.LLMMatcher.EntityType;

public class CHARM_Matcher extends MeltMatcherWrapper {

//LLM verifier config
    static final  int VERIFY_BATCH_SIZE = 10;
    static final  int VERIFY_CONCURRENCY = 20;
    static final  String VERIFY_MODEL = "mistralai--mistral-small";
    static final  boolean VERIFY_CREATE_DOGTAGS = false;


//LLM Matcher config
    static final  String LLM_DOGTAG_MODEL = "mistralai--mistral-small";
    static final  String LLM_MODEL = "mistralai--mistral-small";
    static final  String LLM_EMBEDDING_MODEL = "text-embedding-3-large";
    static final  int LLM_CONCURRENCY = 20;
    static final  int LLM_MATCHING_CONCURRENCY = 20;
    static final  int LLM_KNN = 10;
    static final  boolean LLM_CREATE_DOGTAGS = true;
    static final  List<Double> LLM_THRESHOLDS = List.of(0.9, 0.8, 0.0);


    public CHARM_Matcher() {
        super(
                new SequentialMatcherCombiner(new IMatcher[]{
                        new IterativeTieredMatcherWIthBootstrapMatchersWhenNothingIsFound(
                                List.of(
                                         new EquivalentInstanceLabelMatcherForSameClass()
                                       , new RelatedToExistingMatchAndEquivalentLabelInstanceMatcher()
                                       , new EquivalentLabelAndAltLabelAndTwoSharedStatementsInstanceMatcher()
                                       , new EquivalentLabelAndTwoSharedStatementsInstanceMatcher()
                                       , new EquivalentAltLabelAndTwoSharedStatementsInstanceMatcher()
                                       , new EquivalentCrossLabelAndAltLabelAndTwoSharedStatementsInstanceMatcher()
                                       , new EquivalentCrossAltLabelAndLabelAndTwoSharedStatementsInstanceMatcher()
                                       , new EquivalentLabelAndAltLabelAndOneSharedStatementInstanceMatcher()
                                       , new EquivalentLabelAndOneSharedStatementInstanceMatcher()
                                       , new VerifiedMatcher(new EquivalentAltLabelAndOneSharedStatementInstanceMatcher(), VERIFY_MODEL, VERIFY_BATCH_SIZE, VERIFY_CONCURRENCY, VERIFY_CREATE_DOGTAGS)
                                       , new VerifiedMatcher(new EquivalentCrossLabelAndAltLabelAndOneSharedStatementInstanceMatcher(), VERIFY_MODEL, VERIFY_BATCH_SIZE, VERIFY_CONCURRENCY, VERIFY_CREATE_DOGTAGS)
                                       , new VerifiedMatcher(new EquivalentCrossAltLabelAndLabelAndOneSharedStatementInstanceMatcher(), VERIFY_MODEL, VERIFY_BATCH_SIZE, VERIFY_CONCURRENCY, VERIFY_CREATE_DOGTAGS)
                                       , new MatchPropertiesBasedOnUniqueSubjectObjectCombinations()
                                       , new InstancesWithCommonUniqueStatementMatcher()
                                       , new NewClassMatcherBasedOnMatchedIndividuals()
                                ),
                                List.of(
                                         new SequentialMatcherCombiner(
                                              new EquivalentURIPropertyLabelMatcher()
                                            , new EquivalentClassLabelMatcher()
                                            , new EquivalentPropertyLabelAndAltLabelMatcher()
                                         )
                                         , new HighestCommonWordPercentageInClassLabelMatcher()
                                         ,new LLMMatcher(LLM_DOGTAG_MODEL, LLM_MODEL, LLM_EMBEDDING_MODEL,
                                            LLM_CONCURRENCY, LLM_MATCHING_CONCURRENCY, LLM_KNN, LLM_CREATE_DOGTAGS,
                                            LLM_THRESHOLDS,
                                            EnumSet.of(EntityType.CLASS))
                                          ,new HighestCommonWordPercentageInRelationLabelMatcher()
                                          , new LLMMatcher(LLM_DOGTAG_MODEL, LLM_MODEL, LLM_EMBEDDING_MODEL,
                                            LLM_CONCURRENCY, LLM_MATCHING_CONCURRENCY, LLM_KNN, LLM_CREATE_DOGTAGS,
                                            LLM_THRESHOLDS,
                                            EnumSet.of(EntityType.PROPERTY))
                                         , new VerifiedMatcher(new EquivalentLabelAndAltLabelInstanceMatcher(), VERIFY_MODEL, VERIFY_BATCH_SIZE, VERIFY_CONCURRENCY, VERIFY_CREATE_DOGTAGS)
                                         , new VerifiedMatcher(new UniqueSharedStatementInstanceMatcher(), VERIFY_MODEL, VERIFY_BATCH_SIZE, VERIFY_CONCURRENCY, VERIFY_CREATE_DOGTAGS)
                                         , new VerifiedMatcher(new UniqueWordInAnyLiteralInstanceMatcher(), VERIFY_MODEL, VERIFY_BATCH_SIZE, VERIFY_CONCURRENCY, VERIFY_CREATE_DOGTAGS)
                                         , new VerifiedMatcher(new JaccardWordOverlapInstanceMatcher(), VERIFY_MODEL, VERIFY_BATCH_SIZE, VERIFY_CONCURRENCY, VERIFY_CREATE_DOGTAGS)

                                        /*  , new LLMMatcher(LLM_DOGTAG_MODEL, LLM_MODEL, LLM_EMBEDDING_MODEL,
                                            LLM_CONCURRENCY, LLM_MATCHING_CONCURRENCY, LLM_KNN, false,
                                            List.of(0.96, 0.91, 0.87),
                                            EnumSet.of(EntityType.INSTANCE)) */

                                )
                        )
                        ,
                        new BootstrapMatcher() { //Remove w3.org matches that should not be matched in OAEI tracks
                            @Override
                            public void run() {
                                java.util.List<org.apache.commons.lang3.tuple.Pair<org.apache.jena.ontology.OntProperty, org.apache.jena.ontology.OntProperty>> toRemove =
                                    state.getMatchedProperties().stream()
                                        .filter(p -> p.getLeft().getURI().startsWith("http://www.w3.org/")
                                                  || p.getRight().getURI().startsWith("http://www.w3.org/"))
                                        .collect(java.util.stream.Collectors.toList());
                                toRemove.forEach(p -> state.unmatchProperties(p.getLeft(), p.getRight()));
                            }
                        }
                })
        );
    }
}
