package charm.util.debug;

import org.apache.commons.lang3.tuple.Pair;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class FPChecker {
    // Map: entity1 -> set(entity2)
    public static Map<String, Set<String>> entity1ToEntity2;

    // Map: entity2 -> set(entity1)
    public static Map<String, Set<String>> entity2ToEntity1;

    static final String BASE_DIR = "/Users/i589060/oaei_track_cache/oaei.webdatacommons.org/knowledgegraph/v4/references";

    static int index = 0;
    static final String[] REFERENCE_FILES = new String[]{
            BASE_DIR + "marvelcinematicuniverse-marvel.rdf",
            BASE_DIR + "memoryalpha-memorybeta.rdf",
            BASE_DIR + "memoryalpha-stexpanded.rdf",
            BASE_DIR + "starwars-swg.rdf",
            BASE_DIR + "starwars-swtor.rdf",
    };

    public static void init() throws Exception {
        entity1ToEntity2 = new HashMap<>();
        entity2ToEntity1 = new HashMap<>();

        File inputFile = new File(REFERENCE_FILES[index]);
        index++;

        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        dbFactory.setNamespaceAware(true);
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(inputFile);
        doc.getDocumentElement().normalize();


        NodeList cells = doc.getElementsByTagName("Cell");
        for (int i = 0; i < cells.getLength(); i++) {
            Element cell = (Element) cells.item(i);

            String uri1 = ((Element) cell.getElementsByTagName("entity1").item(0))
                    .getAttribute("rdf:resource");
            String uri2 = ((Element) cell.getElementsByTagName("entity2").item(0))
                    .getAttribute("rdf:resource");

            // Forward mapping: entity1 -> entity2
            entity1ToEntity2.computeIfAbsent(uri1, k -> new HashSet<>()).add(uri2);

            // Reverse mapping: entity2 -> entity1
            entity2ToEntity1.computeIfAbsent(uri2, k -> new HashSet<>()).add(uri1);
        }

    }

    public static boolean isFalsePositive(String entity1, String candidateEntity2) {
        Set<String> goldTargets = entity1ToEntity2.get(entity1);
        Set<String> goldTargets2 = entity2ToEntity1.get(candidateEntity2);

        if ((goldTargets == null || goldTargets.isEmpty()) && (goldTargets2 == null || goldTargets2.isEmpty())) {
            //Both not in goldstandard
            return false;
        }
        if ((goldTargets == null || goldTargets.isEmpty()) || (goldTargets2 == null || goldTargets2.isEmpty())) {
            //Only one present in goldstandard
            return true;
        }

        // It's trying to map entity1 to the wrong entity2
        return !goldTargets.contains(candidateEntity2); // It’s the true mapping
    }

    public static String getCorrectMappingSource(String sourceEntity) {
        Set<String> mappings = entity1ToEntity2.get(sourceEntity);
        if (mappings == null || mappings.isEmpty()) {
            return null;
        }
        // Under 1:1 assumption, just return the first element
        return mappings.iterator().next();
    }

    public static String getCorrectMappingTarget(String targetEntity) {
        Set<String> mappings = entity2ToEntity1.get(targetEntity);
        if (mappings == null || mappings.isEmpty()) {
            return null;
        }
        return mappings.iterator().next();  // 1:1 mapping assumption
    }

    public static Set<Pair<String, String>> getAllTruePositivePairs() {
        Set<Pair<String, String>> pairs = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : entity1ToEntity2.entrySet()) {
            String e1 = entry.getKey();
            for (String e2 : entry.getValue()) {
                pairs.add(Pair.of(e1, e2));
            }
        }
        return pairs;
    }
}