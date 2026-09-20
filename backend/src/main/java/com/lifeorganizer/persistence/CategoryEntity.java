package com.lifeorganizer.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "category")
public class CategoryEntity {

    @Id
    private String id;

    private String name;

    private int position;

    protected CategoryEntity() {
    }

    public CategoryEntity(String id, String name, int position) {
        this.id = id;
        this.name = name;
        this.position = position;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getPosition() {
        return position;
    }
}
