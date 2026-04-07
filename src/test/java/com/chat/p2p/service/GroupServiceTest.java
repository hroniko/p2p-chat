package com.chat.p2p.service;

import com.chat.p2p.model.Group;
import com.chat.p2p.model.P2PMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit-тесты для GroupService.
 */
class GroupServiceTest {

    private GroupService groupService;

    @BeforeEach
    void setUp() {
        groupService = new GroupService();
    }

    @Test
    void createGroup_addsCreatorAsMember() {
        Group group = groupService.createGroup("Test Group", "creator-1");

        assertThat(group).isNotNull();
        assertThat(group.getName()).isEqualTo("Test Group");
        assertThat(group.getCreatorId()).isEqualTo("creator-1");
        assertThat(group.getMembers()).contains("creator-1");
        assertThat(group.isLocal()).isTrue();
    }

    @Test
    void getGroup_returnsCorrectGroup() {
        Group created = groupService.createGroup("Group A", "user-1");
        Group retrieved = groupService.getGroup(created.getId());

        assertThat(retrieved).isEqualTo(created);
    }

    @Test
    void getGroup_nonExistent_returnsNull() {
        assertThat(groupService.getGroup("non-existent")).isNull();
    }

    @Test
    void getLocalGroups_returnsAllGroups() {
        groupService.createGroup("Group 1", "user-1");
        groupService.createGroup("Group 2", "user-2");

        List<Group> groups = groupService.getLocalGroups();
        assertThat(groups).hasSize(2);
    }

    @Test
    void addMember_addsToGroup() {
        Group group = groupService.createGroup("Dev Team", "admin");
        groupService.addMember(group.getId(), "developer-1");

        assertThat(group.getMembers()).contains("admin", "developer-1");
    }

    @Test
    void addMember_nonExistentGroup_doesNothing() {
        assertThatCode(() -> groupService.addMember("fake-id", "user"))
                .doesNotThrowAnyException();
    }

    @Test
    void removeMember_removesFromGroup() {
        Group group = groupService.createGroup("Team", "admin");
        groupService.addMember(group.getId(), "member-1");

        groupService.removeMember(group.getId(), "member-1");
        assertThat(group.getMembers()).doesNotContain("member-1");
        assertThat(group.getMembers()).contains("admin");
    }

    @Test
    void removeMember_lastMember_deletesGroup() {
        Group group = groupService.createGroup("Solo", "solo-user");
        String groupId = group.getId();

        groupService.removeMember(groupId, "solo-user");
        assertThat(groupService.getGroup(groupId)).isNull();
    }

    @Test
    void getGroupsForMember_returnsCorrectGroups() {
        Group g1 = groupService.createGroup("Group A", "user-1");
        groupService.createGroup("Group B", "user-2");

        groupService.addMember(g1.getId(), "common-user");

        List<Group> userGroups = groupService.getGroupsForMember("common-user");
        assertThat(userGroups).hasSize(1);
        assertThat(userGroups.get(0).getName()).isEqualTo("Group A");
    }

    @Test
    void joinGroup_addsNonLocalGroup() {
        Group remoteGroup = new Group("Remote Group", "remote-creator");
        remoteGroup.setLocal(false);

        groupService.joinGroup(remoteGroup);

        Group retrieved = groupService.getGroup(remoteGroup.getId());
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.isLocal()).isFalse();
    }

    @Test
    void broadcastToGroup_excludesSender() {
        Group group = groupService.createGroup("Broadcast Test", "sender");
        groupService.addMember(group.getId(), "receiver-1");
        groupService.addMember(group.getId(), "receiver-2");

        final var receivedMessages = new java.util.ArrayList<String>();

        groupService.addListener(new GroupService.GroupListener() {
            @Override
            public void onGroupMessage(String groupId, String targetPeerId, P2PMessage message) {
                receivedMessages.add(targetPeerId);
            }
        });

        P2PMessage msg = new P2PMessage("MESSAGE", "sender", "Sender", "Hello");
        groupService.broadcastToGroup(group.getId(), msg, "sender");

        assertThat(receivedMessages).containsExactlyInAnyOrder("receiver-1", "receiver-2");
        assertThat(receivedMessages).doesNotContain("sender");
    }

    @Test
    void broadcastToGroup_nonExistentGroup_doesNothing() {
        assertThatCode(() -> {
            groupService.broadcastToGroup("fake", new P2PMessage(), "sender");
        }).doesNotThrowAnyException();
    }

    @Test
    void group_isMember_worksCorrectly() {
        Group group = new Group("Test", "creator");
        group.addMember("member-1");

        assertThat(group.isMember("creator")).isTrue();
        assertThat(group.isMember("member-1")).isTrue();
        assertThat(group.isMember("stranger")).isFalse();
    }

    @Test
    void group_removeNonExistentMember_doesNothing() {
        Group group = new Group("Test", "creator");
        assertThatCode(() -> group.removeMember("non-existent"))
                .doesNotThrowAnyException();
        assertThat(group.getMembers()).contains("creator");
    }
}
