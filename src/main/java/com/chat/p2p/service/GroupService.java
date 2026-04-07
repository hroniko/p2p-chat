package com.chat.p2p.service;

import com.chat.p2p.entity.GroupEntity;
import com.chat.p2p.entity.GroupMemberEntity;
import com.chat.p2p.model.Group;
import com.chat.p2p.model.P2PMessage;
import com.chat.p2p.repository.GroupMemberRepository;
import com.chat.p2p.repository.GroupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Сервис групповых чатов с сохранением в БД.
 *
 * Изменения:
 * - Группы и участники теперь хранятся в SQLite
 * - При старте загружаются из БД в память
 * - При рестарте данные не теряются
 *
 * P2P подход:
 * - Каждый участник хранит свою копию группы
 * - Создатель рассылает приглашения
 * - Сообщения ретранслируются участниками
 */
@Service
public class GroupService {
    private static final Logger log = LoggerFactory.getLogger(GroupService.class);

    @Autowired(required = false)
    private GroupRepository groupRepository;

    @Autowired(required = false)
    private GroupMemberRepository memberRepository;

    /** Кэш групп в памяти (загружается из БД при старте) */
    private final java.util.Map<String, Group> localGroups = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<GroupListener> listeners = new CopyOnWriteArrayList<>();

    @PostConstruct
    public void init() {
        if (groupRepository != null) {
            loadGroupsFromDb();
        }
        log.info("GroupService initialized with {} groups", localGroups.size());
    }

    /**
     * Загрузить все группы из БД в память.
     */
    private void loadGroupsFromDb() {
        if (groupRepository == null || memberRepository == null) {
            return;
        }
        List<GroupEntity> groups = groupRepository.findAll();
        for (GroupEntity entity : groups) {
            Group group = toModel(entity);
            // Загружаем участников
            List<GroupMemberEntity> members = memberRepository.findByGroupId(entity.getId());
            for (GroupMemberEntity member : members) {
                group.addMember(member.getPeerId());
            }
            localGroups.put(group.getId(), group);
        }
        log.debug("Loaded {} groups from database", groups.size());
    }

    /**
     * Конвертировать Entity -> Model.
     */
    private Group toModel(GroupEntity entity) {
        Group group = new Group();
        group.setId(entity.getId());
        group.setName(entity.getName());
        group.setCreatorId(entity.getCreatorId());
        group.setCreatedAt(entity.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        group.setLocal(true);
        return group;
    }

    /**
     * Конвертировать Model -> Entity.
     */
    private GroupEntity toEntity(Group model) {
        GroupEntity entity = new GroupEntity();
        entity.setId(model.getId());
        entity.setName(model.getName());
        entity.setCreatorId(model.getCreatorId());
        entity.setCreatedAt(java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(model.getCreatedAt()),
                java.time.ZoneId.systemDefault()));
        return entity;
    }

    /**
     * Создать группу (с сохранением в БД).
     */
    public Group createGroup(String name, String creatorId) {
        Group group = new Group(name, creatorId);
        localGroups.put(group.getId(), group);

        // Сохраняем в БД
        if (groupRepository != null) {
            try {
                GroupEntity entity = toEntity(group);
                groupRepository.save(entity);
                if (memberRepository != null) {
                    memberRepository.save(new GroupMemberEntity(group.getId(), creatorId));
                }
                log.info("Group created and saved: {} by {}", name, creatorId);
            } catch (Exception e) {
                log.error("Failed to save group to DB: {}", e.getMessage());
            }
        }

        return group;
    }

    /**
     * Получить группу по ID.
     */
    public Group getGroup(String groupId) {
        return localGroups.get(groupId);
    }

    /**
     * Получить все локальные группы.
     */
    public List<Group> getLocalGroups() {
        return localGroups.values().stream().collect(Collectors.toList());
    }

    /**
     * Добавить участника в группу (с сохранением в БД).
     */
    public void addMember(String groupId, String peerId) {
        Group group = localGroups.get(groupId);
        if (group != null) {
            group.addMember(peerId);
            if (memberRepository != null) {
                try {
                    if (!memberRepository.existsByGroupIdAndPeerId(groupId, peerId)) {
                        memberRepository.save(new GroupMemberEntity(groupId, peerId));
                    }
                } catch (Exception e) {
                    log.error("Failed to save group member to DB: {}", e.getMessage());
                }
            }
            log.info("Added {} to group {}", peerId, groupId);

            for (GroupListener listener : listeners) {
                listener.onMemberJoined(groupId, peerId);
            }
        }
    }

    /**
     * Удалить участника из группы (с удалением из БД).
     */
    public void removeMember(String groupId, String peerId) {
        Group group = localGroups.get(groupId);
        if (group != null) {
            group.removeMember(peerId);
            if (memberRepository != null) {
                try {
                    memberRepository.deleteByGroupIdAndPeerId(groupId, peerId);
                } catch (Exception e) {
                    log.error("Failed to remove group member from DB: {}", e.getMessage());
                }
            }
            log.info("Removed {} from group {}", peerId, groupId);

            for (GroupListener listener : listeners) {
                listener.onMemberLeft(groupId, peerId);
            }

            // Если группа пуста - удаляем из БД и памяти
            if (group.getMembers().isEmpty()) {
                if (memberRepository != null && groupRepository != null) {
                    try {
                        memberRepository.findByGroupId(groupId).forEach(m -> memberRepository.delete(m));
                        groupRepository.deleteById(groupId);
                    } catch (Exception e) {
                        log.error("Failed to delete empty group from DB: {}", e.getMessage());
                    }
                }
                localGroups.remove(groupId);
                log.info("Group {} deleted (empty)", groupId);
            }
        }
    }

    /**
     * Получить группы где состоит пир.
     */
    public List<Group> getGroupsForMember(String peerId) {
        return localGroups.values().stream()
            .filter(g -> g.isMember(peerId))
            .collect(Collectors.toList());
    }

    /**
     * Принять приглашение в группу.
     */
    public void joinGroup(Group group) {
        if (!localGroups.containsKey(group.getId())) {
            group.setLocal(false);
            localGroups.put(group.getId(), group);

            // Сохраняем в БД
            if (groupRepository != null) {
                try {
                    groupRepository.save(toEntity(group));
                    log.info("Joined group and saved to DB: {}", group.getName());
                } catch (Exception e) {
                    log.error("Failed to save joined group to DB: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Получить участников группы.
     */
    public List<String> getGroupMembers(String groupId) {
        Group group = localGroups.get(groupId);
        if (group == null) {
            return List.of();
        }
        return List.copyOf(group.getMembers());
    }

    /**
     * Рассылка сообщения всем участникам группы.
     */
    public void broadcastToGroup(String groupId, P2PMessage message, String excludeSender) {
        Group group = localGroups.get(groupId);
        if (group == null) {
            log.warn("Group not found: {}", groupId);
            return;
        }

        for (String memberId : group.getMembers()) {
            if (!memberId.equals(excludeSender)) {
                for (GroupListener listener : listeners) {
                    listener.onGroupMessage(groupId, memberId, message);
                }
            }
        }
    }

    /**
     * Добавить слушателя групповых событий.
     */
    public void addListener(GroupListener listener) {
        listeners.add(listener);
    }

    /**
     * Интерфейс слушателя групповых событий.
     */
    public interface GroupListener {
        void onGroupMessage(String groupId, String targetPeerId, P2PMessage message);
        default void onMemberJoined(String groupId, String peerId) {}
        default void onMemberLeft(String groupId, String peerId) {}
    }
}
