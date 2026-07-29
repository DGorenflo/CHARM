package charm.bootstrap.semantics.model;

public class Candidate {
    public final String targetUri;
    public final double score;

    public Candidate(String targetUri, double score) {
        this.targetUri = targetUri;
        this.score = score;
    }
}
