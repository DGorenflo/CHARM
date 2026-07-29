# Matcher Confidence Scores

Each matcher writes a unique fixed confidence score into the alignment output.
This allows post-hoc attribution of every correspondence to its source matcher.

## Bootstrap — Equivalence (0.80x)

| Score | Matcher |
|-------|---------|
| 0.801 | `EquivalentURIPropertyLabelMatcher` |
| 0.802 | `EquivalentClassLabelMatcher` |
| 0.803 | `EquivalentPropertyLabelAndAltLabelMatcher` |
| 0.804 | `EquivalentLabelAndAltLabelInstanceMatcher` |
| 0.805 | `UniqueSharedStatementInstanceMatcher` |
| 0.806 | `UniqueWordInAnyLiteralInstanceMatcher` |

## Bootstrap — Percentage (0.81x)

| Score | Matcher |
|-------|---------|
| 0.811 | `HighestCommonWordPercentageInClassLabelMatcher` |
| 0.812 | `HighestCommonWordPercentageInRelationLabelMatcher` |
| 0.813 | `JaccardWordOverlapInstanceMatcher` |

## Bootstrap — Semantic / LLM (0.82x)

| Score | Matcher |
|-------|---------|
| 0.821 | `LLMMatcher` |

## Iterative — Instance (0.90x–0.91x)

| Score | Matcher |
|-------|---------|
| 0.901 | `EquivalentInstanceLabelMatcherForSameClass` |
| 0.902 | `RelatedToExistingMatchAndEquivalentLabelInstanceMatcher` |
| 0.903 | `EquivalentLabelAndAltLabelAndTwoSharedStatementsInstanceMatcher` |
| 0.904 | `EquivalentLabelAndTwoSharedStatementsInstanceMatcher` |
| 0.905 | `EquivalentAltLabelAndTwoSharedStatementsInstanceMatcher` |
| 0.906 | `EquivalentCrossLabelAndAltLabelAndTwoSharedStatementsInstanceMatcher` |
| 0.907 | `EquivalentCrossAltLabelAndLabelAndTwoSharedStatementsInstanceMatcher` |
| 0.908 | `EquivalentLabelAndAltLabelAndOneSharedStatementInstanceMatcher` |
| 0.909 | `EquivalentLabelAndOneSharedStatementInstanceMatcher` |
| 0.910 | `EquivalentAltLabelAndOneSharedStatementInstanceMatcher` |
| 0.911 | `EquivalentCrossLabelAndAltLabelAndOneSharedStatementInstanceMatcher` |
| 0.912 | `EquivalentCrossAltLabelAndLabelAndOneSharedStatementInstanceMatcher` |
| 0.913 | `InstancesWithCommonUniqueStatementMatcher` |

## Iterative — Property (0.92x)

| Score | Matcher |
|-------|---------|
| 0.921 | `MatchPropertiesBasedOnUniqueSubjectObjectCombinations` |

## Iterative — Class (0.93x)

| Score | Matcher |
|-------|---------|
| 0.931 | `NewClassMatcherBasedOnMatchedIndividuals` |
