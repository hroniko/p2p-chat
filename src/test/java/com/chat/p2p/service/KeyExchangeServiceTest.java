package com.chat.p2p.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit-тесты для KeyExchangeService (ECDH).
 */
class KeyExchangeServiceTest {

    private KeyExchangeService keyExchange;

    @BeforeEach
    void setUp() {
        keyExchange = new KeyExchangeService();
    }

    @Test
    void getLocalPublicKeyBase64_returnsNonEmpty() {
        String publicKey = keyExchange.getLocalPublicKeyBase64();
        assertThat(publicKey).isNotBlank();
    }

    @Test
    void computeSharedSecret_bothPeersGetSameSecret() {
        // Моделируем обмен ключами между двумя пирами
        KeyExchangeService peerA = new KeyExchangeService();
        KeyExchangeService peerB = new KeyExchangeService();

        // Peer A получает public key от B
        String secretA = peerA.computeSharedSecret("peerB", peerB.getLocalPublicKeyBase64());
        // Peer B получает public key от A
        String secretB = peerB.computeSharedSecret("peerA", peerA.getLocalPublicKeyBase64());

        // Shared secrets должны совпасть!
        assertThat(secretA).isNotNull().isEqualTo(secretB);
    }

    @Test
    void computeSharedSecret_invalidKey_returnsNull() {
        assertThat(keyExchange.computeSharedSecret("peer", "invalid-base64")).isNull();
    }

    @Test
    void hasSharedSecret_returnsTrueAfterCompute() {
        KeyExchangeService peerB = new KeyExchangeService();

        keyExchange.computeSharedSecret("peer1", peerB.getLocalPublicKeyBase64());

        assertThat(keyExchange.hasSharedSecret("peer1")).isTrue();
        assertThat(keyExchange.hasSharedSecret("unknown")).isFalse();
    }

    @Test
    void getSharedSecret_returnsCorrectValue() {
        KeyExchangeService peerB = new KeyExchangeService();
        String secret = keyExchange.computeSharedSecret("peer1", peerB.getLocalPublicKeyBase64());

        assertThat(keyExchange.getSharedSecret("peer1")).isEqualTo(secret);
    }

    @Test
    void removeSharedSecret_clearsSecret() {
        KeyExchangeService peerB = new KeyExchangeService();
        keyExchange.computeSharedSecret("peer1", peerB.getLocalPublicKeyBase64());
        assertThat(keyExchange.hasSharedSecret("peer1")).isTrue();

        keyExchange.removeSharedSecret("peer1");

        assertThat(keyExchange.hasSharedSecret("peer1")).isFalse();
    }

    @Test
    void encryptAndDecrypt_roundTrip_success() {
        KeyExchangeService peerA = new KeyExchangeService();
        KeyExchangeService peerB = new KeyExchangeService();

        // Обмен ключами
        peerA.computeSharedSecret("peerB", peerB.getLocalPublicKeyBase64());
        peerB.computeSharedSecret("peerA", peerA.getLocalPublicKeyBase64());

        String plaintext = "Secret ECDH message";

        // A шифрует -> B дешифрует
        byte[] encrypted = peerA.encrypt("peerB", plaintext);
        assertThat(encrypted).isNotNull();
        assertThat(encrypted.length).isGreaterThan(12);

        String decrypted = peerB.decrypt("peerA", encrypted);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void encrypt_withoutSharedSecret_returnsNull() {
        assertThat(keyExchange.encrypt("unknown-peer", "data")).isNull();
    }

    @Test
    void decrypt_withoutSharedSecret_returnsNull() {
        assertThat(keyExchange.decrypt("unknown-peer", new byte[20])).isNull();
    }

    @Test
    void encryptWithSecretAndDecryptWithSecret_roundTrip() {
        // Генерируем fake shared secret (как будто от ECDH)
        KeyExchangeService temp = new KeyExchangeService();
        String secret = temp.computeSharedSecret("x", 
                keyExchange.getLocalPublicKeyBase64());

        String plaintext = "Direct secret message";

        byte[] encrypted = keyExchange.encryptWithSecret(secret, plaintext);
        assertThat(encrypted).isNotNull();

        String decrypted = keyExchange.decryptWithSecret(secret, encrypted);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void differentPeersGetDifferentSecretsWithSameLocalKey() {
        KeyExchangeService peerA = new KeyExchangeService();
        KeyExchangeService peerB = new KeyExchangeService();

        String secretA = keyExchange.computeSharedSecret("peerA", peerA.getLocalPublicKeyBase64());
        String secretB = keyExchange.computeSharedSecret("peerB", peerB.getLocalPublicKeyBase64());

        assertThat(secretA).isNotEqualTo(secretB);
    }
}
