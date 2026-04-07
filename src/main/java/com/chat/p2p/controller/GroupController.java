package com.chat.p2p.controller;

import com.chat.p2p.model.Group;
import com.chat.p2p.service.GroupService;
import com.chat.p2p.service.P2PNetworkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Управление групповыми чатами.
 */
@RestController
@RequestMapping("/api/groups")
public class GroupController {
    private static final Logger log = LoggerFactory.getLogger(GroupController.class);

    @Autowired
    private GroupService groupService;

    @Autowired
    private P2PNetworkService networkService;

    /**
     * Создать новую группу.
     */
    @PostMapping("/create")
    public Map<String, Object> createGroup(@RequestBody Map<String, String> request) {
        String name = request.get("name");

        if (name == null || name.isBlank()) {
            return Map.of("error", "Group name is required");
        }
        if (name.length() > 100) {
            return Map.of("error", "Group name too long (max 100 chars)");
        }

        String creatorId = networkService.getPeerId();
        Group group = groupService.createGroup(name, creatorId);

        log.info("Group created: {} by {}", group.getName(), creatorId);
        return Map.of(
            "groupId", group.getId(),
            "name", group.getName(),
            "members", group.getMembers()
        );
    }

    /**
     * Получить все группы текущего пира.
     */
    @GetMapping
    public List<Group> getGroups() {
        return groupService.getLocalGroups();
    }

    /**
     * Получить информацию о конкретной группе.
     */
    @GetMapping("/{groupId}")
    public Group getGroup(@PathVariable String groupId) {
        return groupService.getGroup(groupId);
    }

    /**
     * Получить участников группы.
     */
    @GetMapping("/{groupId}/members")
    public List<String> getGroupMembers(@PathVariable String groupId) {
        return groupService.getGroupMembers(groupId);
    }

    /**
     * Присоединиться к группе (принять приглашение).
     */
    @PostMapping("/{groupId}/join")
    public Map<String, Object> joinGroup(@PathVariable String groupId, @RequestBody Group group) {
        group.setId(groupId);
        groupService.joinGroup(group);
        return Map.of("status", "joined");
    }

    /**
     * Добавить участника в группу.
     */
    @PostMapping("/{groupId}/members")
    public Map<String, Object> addMember(@PathVariable String groupId, @RequestBody Map<String, String> request) {
        String peerId = request.get("peerId");

        if (peerId == null || peerId.isBlank()) {
            return Map.of("error", "Peer ID is required");
        }

        Group group = groupService.getGroup(groupId);
        if (group == null) {
            return Map.of("error", "Group not found");
        }

        groupService.addMember(groupId, peerId);
        return Map.of("status", "member_added", "peerId", peerId);
    }

    /**
     * Удалить участника из группы.
     */
    @DeleteMapping("/{groupId}/members/{peerId}")
    public Map<String, Object> removeMember(@PathVariable String groupId, @PathVariable String peerId) {
        Group group = groupService.getGroup(groupId);
        if (group == null) {
            return Map.of("error", "Group not found");
        }

        groupService.removeMember(groupId, peerId);
        return Map.of("status", "member_removed", "peerId", peerId);
    }
}
