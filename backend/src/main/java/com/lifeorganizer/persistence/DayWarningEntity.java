package com.lifeorganizer.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(name = "day_warning")
public class DayWarningEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warning_date")
    private LocalDate date;

    private int position;

    private String message;

    protected DayWarningEntity() {
    }

    public DayWarningEntity(LocalDate date, int position, String message) {
        this.date = date;
        this.position = position;
        this.message = message;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getDate() {
        return date;
    }

    public int getPosition() {
        return position;
    }

    public String getMessage() {
        return message;
    }
}
