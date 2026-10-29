package com.lifeorganizer.domain;

public sealed interface Constraint
    permits FixedBlock {

    String label();
}
