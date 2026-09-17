package com.lifeorganizer.domain;

public sealed interface Constraint
    permits FixedBlock, FrequencyQuota, DurationQuota, EveningQuota {

    String label();
}
