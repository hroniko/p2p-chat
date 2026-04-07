package com.chat.p2p.repository;

import com.chat.p2p.entity.GroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GroupRepository extends JpaRepository<GroupEntity, String> {
    List<GroupEntity> findByCreatorId(String creatorId);
}
