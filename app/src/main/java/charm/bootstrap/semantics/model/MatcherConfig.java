package charm.bootstrap.semantics.model;

import org.apache.jena.sparql.function.library.context;
import org.apache.jena.sparql.pfunction.library.container;

public class MatcherConfig {

    public final String matchingPromptTemplate =
            "TASK: Ontology Matching / Entity Alignment\n" +
            "Decide if the Source Entity represents the EXACT SAME real-world concept as one of the Candidates.\n\n" +
            "SOURCE ENTITY:\n" +
            "{source_context}\n\n" +
            "CANDIDATES:\n" +
            "{candidates_context}\n\n" +
            "INSTRUCTIONS:\n" +
            "- Analyze attributes, types, and relations.\n" +
            "- If a candidate matches perfectly, choose its URI.\n" +
            "- If multiple match, choose the best one.\n" +
            "- If none match sufficiently, choose None.\n\n" +
            "OUTPUT FORMAT (MANDATORY):\n" +
            "- Respond with a SINGLE LINE and NOTHING ELSE.\n" +
            "- That line is EITHER the exact URI of the best matching candidate (copied verbatim, e.g. http://mouse.owl#MA_0000001) OR the single word None.\n" +
            "- Do NOT add any explanation, reasoning, punctuation, markdown, quotes, or extra words.\n" +
            "- Do NOT prefix the answer with labels like \"Answer:\" or \"URI:\".\n\n" +
            "ANSWER:";

    public final String verificationPromptTemplate =
        "TASK: Ontology Match Verification\n" +                                                                                 
        "For each numbered pair below, decide if the SOURCE and TARGET refer to the SAME real-world entity (person, place, organization, item, concept, etc.).\n\n" +                                                                              
        "PAIRS:\n" +                                                                                                            
        "{pairs_context}\n\n" +                                                                                                 
        "DECISION RULES:\n" +                                                                                                   
        "- Two entities are the SAME if they denote the same real-world referent, even when:\n" +                               
        "    * one uses a real name and the other a codename, nickname, alias, or title (e.g. \"Trish Walker\" and \"Hellcat\", \"M'Baku\" and \"Man-Ape\")\n" +                                                                                        
        "    * labels differ in spelling, punctuation, diacritics, abbreviation, or word order (e.g. \"Bogotá\" vs \"Bogota\", \"F.R.I.D.A.Y.\" vs \"Friday\", \"Jim Wilson\" vs \"James Wilson\")\n" +                                                
        "    * one label is a disambiguation page or hub referring to the same entity\n" +                                      
        "    * one description is empty, minimal, or missing — absence of context is NOT evidence against a match\n" +          
        "- Reject ONLY when you have clear positive evidence the entities are different (e.g. different birth year, different franchise universe stated explicitly, incompatible types like person vs. weapon, or clearly different attributes).\n" + 
        "- When evidence is ambiguous or insufficient, answer \"yes\" (favor recall).\n" +                                      
        "- Identical or trivially-normalized labels (case, punctuation, whitespace) should almost always be \"yes\" unless there is explicit contradicting evidence.\n\n" +                                                                             
        "OUTPUT FORMAT:\n" +                                                                                                    
        "- A numbered list, one line per pair, each line containing ONLY \"yes\" or \"no\".\n" +                                
        "- Return exactly one answer per pair, in the given order.\n\n" +                                                       
        "ANSWER:"; 
}
