package com.lifeorganizer.memory.notes;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NoteRepository extends JpaRepository<NoteEntity, Long> {

    List<NoteEntity> findAllByOrderByUpdatedAtDescIdDesc();
}
