package com.lifeorganizer.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConstraintRepository extends JpaRepository<ConstraintEntity, Long> {

    List<ConstraintEntity> findAllByOrderByPositionAsc();
}
