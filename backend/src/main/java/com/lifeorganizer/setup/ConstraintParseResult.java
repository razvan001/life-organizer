package com.lifeorganizer.setup;

import com.lifeorganizer.domain.Constraint;
import java.util.List;

public record ConstraintParseResult(List<Constraint> constraints, List<String> warnings) {}
