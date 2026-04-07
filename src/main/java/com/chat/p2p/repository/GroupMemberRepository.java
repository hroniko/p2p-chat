package com.chat.p2p.repository;

import com.chat.p2p.entity.GroupMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GroupMemberRepository extends JpaRepository<GroupMemberEntity, String> {
    List<GroupMemberEntity> findByGroupId(String groupId);
    List<GroupMemberEntity> findByPeerId(String peerId);
    void deleteByGroupIdAndPeerId(String groupId, String peerId);
    boolean existsByGroupIdAndPeerId(String groupId, String peerId);
}
