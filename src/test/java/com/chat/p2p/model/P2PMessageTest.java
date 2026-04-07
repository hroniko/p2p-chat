package com.chat.p2p.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit-тесты для моделей.
 */
class P2PMessageTest {

    @Test
    void constructor_withParameters_setsFields() {
        P2PMessage msg = new P2PMessage("MESSAGE", "sender-1", "Alice", "Hello!");

        assertThat(msg.getType()).isEqualTo("MESSAGE");
        assertThat(msg.getSenderId()).isEqualTo("sender-1");
        assertThat(msg.getSenderName()).isEqualTo("Alice");
        assertThat(msg.getContent()).isEqualTo("Hello!");
    }

    @Test
    void defaultConstructor_generatesId() {
        P2PMessage msg = new P2PMessage();
        assertThat(msg.getId()).isNotBlank();
    }

    @Test
    void defaultConstructor_setsTimestamp() {
        long before = System.currentTimeMillis();
        P2PMessage msg = new P2PMessage();
        long after = System.currentTimeMillis();

        assertThat(msg.getTimestamp()).isBetween(before, after);
    }

    @Test
    void setters_workCorrectly() {
        P2PMessage msg = new P2PMessage();
        msg.setId("custom-id");
        msg.setType("FILE");
        msg.setSenderId("peer-1");
        msg.setSenderName("Bob");
        msg.setTargetId("peer-2");
        msg.setContent("File message");
        msg.setEncryptedContent("encrypted-data");
        msg.setFileId("file-123");
        msg.setFileName("document.pdf");
        msg.setFileType("application/pdf");
        msg.setFileSize(1024);
        msg.setTime("10:30");
        msg.setAuthToken("token-abc");
        msg.setTimestamp(1234567890L);

        assertThat(msg.getId()).isEqualTo("custom-id");
        assertThat(msg.getType()).isEqualTo("FILE");
        assertThat(msg.getSenderId()).isEqualTo("peer-1");
        assertThat(msg.getSenderName()).isEqualTo("Bob");
        assertThat(msg.getTargetId()).isEqualTo("peer-2");
        assertThat(msg.getContent()).isEqualTo("File message");
        assertThat(msg.getEncryptedContent()).isEqualTo("encrypted-data");
        assertThat(msg.getFileId()).isEqualTo("file-123");
        assertThat(msg.getFileName()).isEqualTo("document.pdf");
        assertThat(msg.getFileType()).isEqualTo("application/pdf");
        assertThat(msg.getFileSize()).isEqualTo(1024);
        assertThat(msg.getTime()).isEqualTo("10:30");
        assertThat(msg.getAuthToken()).isEqualTo("token-abc");
        assertThat(msg.getTimestamp()).isEqualTo(1234567890L);
    }
}
