package charm.bootstrap.semantics.model;

import java.util.List;

public class EmbeddedDogTag {
    public final List<Float> vector;
    public final String text;

    public EmbeddedDogTag(List<Float> vector, String text) {
        this.vector = vector;
        this.text = text;
    }
}
