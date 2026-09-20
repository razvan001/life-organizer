package com.lifeorganizer.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GoalRepository extends JpaRepository<GoalEntity, String> {

    List<GoalEntity> findAllByOrderByPositionAsc();
}
