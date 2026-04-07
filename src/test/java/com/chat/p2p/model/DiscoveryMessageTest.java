package com.chat.p2p.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit-тесты для DiscoveryMessage.
 */
class DiscoveryMessageTest {

    @Test
    void constructor_setsFields() {
        DiscoveryMessage msg = new DiscoveryMessage("peer-1", "Alice", 9090);

        assertThat(msg.getType()).isEqualTo("ANNOUNCE");
        assertThat(msg.getPeerId()).isEqualTo("peer-1");
        assertThat(msg.getPeerName()).isEqualTo("Alice");
        assertThat(msg.getP2pPort()).isEqualTo(9090);
    }

    @Test
    void setters_workCorrectly() {
        DiscoveryMessage msg = new DiscoveryMessage();
        msg.setType("ANNOUNCE");
        msg.setPeerId("peer-2");
        msg.setPeerName("Bob");
        msg.setP2pPort(8080);

        assertThat(msg.getType()).isEqualTo("ANNOUNCE");
        assertThat(msg.getPeerId()).isEqualTo("peer-2");
        assertThat(msg.getPeerName()).isEqualTo("Bob");
        assertThat(msg.getP2pPort()).isEqualTo(8080);
    }
}
