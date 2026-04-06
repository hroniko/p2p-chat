package com.chat.p2p.service;

import com.chat.p2p.model.Group;
import com.chat.p2p.model.P2PMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Сервис групповых чатов.
 * 
 * P2P подход:
 * - Нет центрального сервера групп
 * - Каждый участник хранит свою копию группы
 * - Создатель рассылает приглашения
 * - Сообщения ретранслируются участниками
 */
@Service
public class GroupService {
    private static final Logger log = LoggerFactory.getLogger(GroupService.class);

    private final Map<String, Group> localGroups = new ConcurrentHashMap<>();
    private final List<GroupListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Создать группу.
     */
    public Group createGroup(String name, String creatorId) {
        Group group = new Group(name, creatorId);
        localGroups.put(group.getId(), group);
        log.info("Group created: {} by {}", name, creatorId);
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
     * Добавить участника в группу.
     */
    public void addMember(String groupId, String peerId) {
        Group group = localGroups.get(groupId);
        if (group != null) {
            group.addMember(peerId);
            log.info("Added {} to group {}", peerId, groupId);
        }
    }

    /**
     * Удалить участника из группы.
     */
    public void removeMember(String groupId, String peerId) {
        Group group = localGroups.get(groupId);
        if (group != null) {
            group.removeMember(peerId);
            log.info("Removed {} from group {}", peerId, groupId);
            
            // Если группа пуста - удаляем
            if (group.getMembers().isEmpty()) {
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
            group.setLocal(false); // Это чужая группа
            localGroups.put(group.getId(), group);
            log.info("Joined group: {}", group.getName());
        }
    }

    /**
     * Рассылка сообщения всем участникам группы.
     * Вызывается при получении группового сообщения.
     */
    public void broadcastToGroup(String groupId, P2PMessage message, String excludeSender) {
        Group group = localGroups.get(groupId);
        if (group == null) {
            log.warn("Group not found: {}", groupId);
            return;
        }

        for (String memberId : group.getMembers()) {
            if (!memberId.equals(excludeSender)) {
                // Уведомляем слушателей - нужно отправить этому участнику
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