package com.lifeorganizer.setup;

import com.lifeorganizer.domain.Constraint;
import java.util.List;
import java.util.Objects;

public record ConstraintParseResult(List<Constraint> constraints, List<String> warnings) {

    public ConstraintParseResult {
        Objects.requireNonNull(constraints, "constraints");
        Objects.requireNonNull(warnings, "warnings");
        constraints = List.copyOf(constraints);
        warnings = List.copyOf(warnings);
    }
}
