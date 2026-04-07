package com.chat.p2p.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit-тесты для модели Peer.
 */
class PeerTest {

    @Test
    void getWsUrl_returnsCorrectUrl() {
        Peer peer = new Peer();
        peer.setAddress("192.168.1.100");
        peer.setPort(9090);

        assertThat(peer.getWsUrl()).isEqualTo("wss://192.168.1.100:9090/ws");
    }

    @Test
    void equals_hashCode_basedOnIdOnly() {
        Peer p1 = new Peer();
        p1.setId("peer-1");
        p1.setName("Alice");
        p1.setAddress("10.0.0.1");

        Peer p2 = new Peer();
        p2.setId("peer-1");
        p2.setName("Bob");
        p2.setAddress("10.0.0.2");

        assertThat(p1).isEqualTo(p2);
        assertThat(p1.hashCode()).isEqualTo(p2.hashCode());
    }

    @Test
    void notEquals_whenDifferentIds() {
        Peer p1 = new Peer();
        p1.setId("peer-1");

        Peer p2 = new Peer();
        p2.setId("peer-2");

        assertThat(p1).isNotEqualTo(p2);
    }

    @Test
    void defaultValues_areCorrect() {
        Peer peer = new Peer();

        assertThat(peer.isConnected()).isFalse();
        assertThat(peer.isTrusted()).isFalse();
        assertThat(peer.getLastSeen()).isEqualTo(0);
    }

    @Test
    void setters_workCorrectly() {
        Peer peer = new Peer();
        peer.setId("id-1");
        peer.setName("TestPeer");
        peer.setAddress("127.0.0.1");
        peer.setPort(8080);
        peer.setConnected(true);
        peer.setTrusted(true);
        peer.setLastSeen(1000L);
        peer.setAuthToken("auth-token");

        assertThat(peer.getId()).isEqualTo("id-1");
        assertThat(peer.getName()).isEqualTo("TestPeer");
        assertThat(peer.getAddress()).isEqualTo("127.0.0.1");
        assertThat(peer.getPort()).isEqualTo(8080);
        assertThat(peer.isConnected()).isTrue();
        assertThat(peer.isTrusted()).isTrue();
        assertThat(peer.getLastSeen()).isEqualTo(1000L);
        assertThat(peer.getAuthToken()).isEqualTo("auth-token");
    }
}
