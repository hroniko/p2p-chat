package com.chat.p2p.model;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Модель группы.
 * 
 * Групповой чат позволяет общаться нескольким пирам вместе.
 * Особенности:
 * - Создатель группы - админ
 * - Участники могут добавлять других
 * - Сообщения рассылаются всем участникам
 * - P2P - нет центрального сервера, каждый участник ретранслирует
 */
public class Group {
    private String id = UUID.randomUUID().toString().substring(0, 8);
    private String name;
    private String creatorId; // Создатель - админ
    private Set<String> members = new HashSet<>();
    private long createdAt = System.currentTimeMillis();
    private boolean isLocal = true; // Создана локально

    public Group() {}

    public Group(String name, String creatorId) {
        this.name = name;
        this.creatorId = creatorId;
        this.members.add(creatorId);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCreatorId() { return creatorId; }
    public void setCreatorId(String creatorId) { this.creatorId = creatorId; }

    public Set<String> getMembers() { return members; }
    public void setMembers(Set<String> members) { this.members = members; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public boolean isLocal() { return isLocal; }
    public void setLocal(boolean local) { isLocal = local; }

    public boolean isMember(String peerId) {
        return members.contains(peerId);
    }

    public void addMember(String peerId) {
        members.add(peerId);
    }

    public void removeMember(String peerId) {
        members.remove(peerId);
    }
}