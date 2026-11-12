package app.dispofold.model;

import java.util.List;

public record PersonExplanation(PersonStats stats, List<ConstraintHit> hits) {
}
