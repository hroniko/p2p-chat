package com.chat.p2p.entity;

import jakarta.persistence.*;

/**
 * Сущность участника группы в БД.
 * ManyToOne: один участник может быть в нескольких группах.
 */
@Entity
@Table(name = "group_members")
public class GroupMemberEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String groupId;

    @Column(nullable = false)
    private String peerId;

    public GroupMemberEntity() {}

    public GroupMemberEntity(String groupId, String peerId) {
        this.groupId = groupId;
        this.peerId = peerId;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getPeerId() { return peerId; }
    public void setPeerId(String peerId) { this.peerId = peerId; }
}
