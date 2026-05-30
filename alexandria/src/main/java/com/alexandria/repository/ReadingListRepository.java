package com.alexandria.repository;

import com.alexandria.entity.ReadingList;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReadingListRepository extends JpaRepository<ReadingList, UUID> {

    Page<ReadingList> findByUserId(UUID userId, Pageable pageable);
}
