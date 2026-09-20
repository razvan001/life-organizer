package com.lifeorganizer.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue("EVENING_QUOTA")
public class EveningQuotaEntity extends ConstraintEntity {

    @Column(name = "evenings_per_week")
    private Integer eveningsPerWeek;

    // Sibling single-table subclasses may map the same column (DurationQuotaEntity does too) - verified on Hibernate 7.
    @Column(name = "category_id")
    private String categoryId;

    protected EveningQuotaEntity() {
    }

    public EveningQuotaEntity(String label, int position, int eveningsPerWeek, String categoryId) {
        super(label, position);
        this.eveningsPerWeek = eveningsPerWeek;
        this.categoryId = categoryId;
    }

    public Integer getEveningsPerWeek() {
        return eveningsPerWeek;
    }

    public String getCategoryId() {
        return categoryId;
    }
}
