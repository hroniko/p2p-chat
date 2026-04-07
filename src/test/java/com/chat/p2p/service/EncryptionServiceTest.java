package com.chat.p2p.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit-тесты для EncryptionService.
 * Проверяем корректность AES-256-GCM шифрования/дешифрования.
 */
class EncryptionServiceTest {

    private EncryptionService encryptionService;

    @BeforeEach
    void setUp() {
        encryptionService = new EncryptionService();
    }

    @Test
    void generateKey_shouldProduceNonNullKey() {
        var key = encryptionService.generateKey("test-secret");
        assertThat(key).isNotNull();
        assertThat(key.getEncoded()).hasSize(32); // SHA-256 = 32 bytes
    }

    @Test
    void generateKey_sameSecretProducesSameKey() {
        var key1 = encryptionService.generateKey("my-secret");
        var key2 = encryptionService.generateKey("my-secret");
        assertThat(key1.getEncoded()).containsExactly(key2.getEncoded());
    }

    @Test
    void generateKey_differentSecretsProduceDifferentKeys() {
        var key1 = encryptionService.generateKey("secret-one");
        var key2 = encryptionService.generateKey("secret-two");
        assertThat(key1.getEncoded()).isNotEqualTo(key2.getEncoded());
    }

    @Test
    void encryptAndDecrypt_roundTrip_success() {
        String secret = "test-password";
        String peerId = "peer-123";
        String plaintext = "Hello, P2P World!";

        encryptionService.setPeerKey(peerId, secret);

        byte[] encrypted = encryptionService.encrypt(peerId, plaintext);
        assertThat(encrypted).isNotNull();
        // Формат: 12 bytes IV + ciphertext
        assertThat(encrypted.length).isGreaterThan(12);

        String decrypted = encryptionService.decrypt(peerId, encrypted);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void encryptAndDecrypt_wrongKey_returnsNull() {
        String peerId = "peer-456";
        String plaintext = "Secret message";

        encryptionService.setPeerKey(peerId, "correct-secret");
        byte[] encrypted = encryptionService.encrypt(peerId, plaintext);

        // Подменяем ключ — дешифровка вернёт null при неверном GCM tag
        encryptionService.setPeerKey(peerId, "wrong-secret");

        //decrypt возвращает null при ошибке (catch exception внутри)
        String result = encryptionService.decrypt(peerId, encrypted);
        assertThat(result).isNull();
    }

    @Test
    void encrypt_withoutPeerKey_returnsNull() {
        assertThat(encryptionService.encrypt("unknown-peer", "data")).isNull();
    }

    @Test
    void decrypt_withoutPeerKey_returnsNull() {
        assertThat(encryptionService.decrypt("unknown-peer", new byte[20])).isNull();
    }

    @Test
    void encryptWithKeyAndDecryptWithKey_roundTrip() {
        String secret = "shared-secret";
        String plaintext = "Test message with explicit key";

        byte[] encrypted = encryptionService.encryptWithKey(secret, plaintext);
        assertThat(encrypted).isNotNull();

        String decrypted = encryptionService.decryptWithKey(secret, encrypted);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void encryptToBase64AndDecryptFromBase64_roundTrip() {
        String peerId = "peer-b64";
        String plaintext = "Base64 encoded message";

        encryptionService.setPeerKey(peerId, "b64-secret");

        String encryptedBase64 = encryptionService.encryptToBase64(peerId, plaintext);
        assertThat(encryptedBase64).isNotNull();
        assertThat(encryptedBase64).isNotBlank();

        String decrypted = encryptionService.decryptFromBase64(peerId, encryptedBase64);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void hasKey_returnsTrueAfterSetPeerKey() {
        assertThat(encryptionService.hasKey("peer-789")).isFalse();

        encryptionService.setPeerKey("peer-789", "secret");
        assertThat(encryptionService.hasKey("peer-789")).isTrue();
    }

    @Test
    void removePeerKey_removesKey() {
        encryptionService.setPeerKey("peer-temp", "secret");
        assertThat(encryptionService.hasKey("peer-temp")).isTrue();

        encryptionService.removePeerKey("peer-temp");
        assertThat(encryptionService.hasKey("peer-temp")).isFalse();
    }

    @Test
    void encrypt_producesDifferentCiphertextForSamePlaintext() {
        // Уникальный IV гарантирует разные шифротексты
        encryptionService.setPeerKey("peer-iv", "secret");
        String plaintext = "Same text twice";

        byte[] enc1 = encryptionService.encrypt("peer-iv", plaintext);
        byte[] enc2 = encryptionService.encrypt("peer-iv", plaintext);

        assertThat(enc1).isNotNull().isNotEqualTo(enc2);
    }
}
