package com.lifeorganizer.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue("DURATION_QUOTA")
public class DurationQuotaEntity extends ConstraintEntity {

    @Column(name = "minutes_per_week")
    private Integer minutesPerWeek;

    @Column(name = "category_id")
    private String categoryId;

    protected DurationQuotaEntity() {
    }

    public DurationQuotaEntity(String label, int position, int minutesPerWeek, String categoryId) {
        super(label, position);
        this.minutesPerWeek = minutesPerWeek;
        this.categoryId = categoryId;
    }

    public Integer getMinutesPerWeek() {
        return minutesPerWeek;
    }

    public String getCategoryId() {
        return categoryId;
    }
}
