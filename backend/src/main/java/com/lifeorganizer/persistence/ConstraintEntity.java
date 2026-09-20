package com.lifeorganizer.persistence;

import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;

/**
 * One row per Constraint, all four variants in a single table ({@code plan_constraint}) with a
 * {@code type} discriminator. Mirrors the sealed Phase 1a {@code Constraint} interface.
 */
@Entity
@Table(name = "plan_constraint")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "type", length = 30)
public abstract class ConstraintEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String label;

    private int position;

    protected ConstraintEntity() {
    }

    protected ConstraintEntity(String label, int position) {
        this.label = label;
        this.position = position;
    }

    public Long getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public int getPosition() {
        return position;
    }
}
