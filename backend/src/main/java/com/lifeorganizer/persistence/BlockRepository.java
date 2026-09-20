package com.lifeorganizer.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface BlockRepository extends JpaRepository<BlockEntity, String> {

    List<BlockEntity> findByDateOrderByStartTimeAscIdAsc(LocalDate date);
}
