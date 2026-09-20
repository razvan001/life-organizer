package com.lifeorganizer.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface DayWarningRepository extends JpaRepository<DayWarningEntity, Long> {

    List<DayWarningEntity> findByDateOrderByPositionAsc(LocalDate date);

    void deleteByDate(LocalDate date);
}
