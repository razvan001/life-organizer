package com.lifeorganizer.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface BlockRepository extends JpaRepository<BlockEntity, String> {

    List<BlockEntity> findByDateOrderByStartTimeAscIdAsc(LocalDate date);

    @Query("select distinct b.date from BlockEntity b where b.date between :from and :to order by b.date")
    List<LocalDate> findDistinctDatesBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
