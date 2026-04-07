package com.chat.p2p.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сервис обмена ключами через Elliptic Curve Diffie-Hellman (ECDH).
 *
 * Проблема старой схемы: shared secret передавался в открытом виде по сети.
 * Любой, кто перехватит пакет, мог вывести ключ шифрования.
 *
 * Решение: ECDH key exchange.
 * 1. Каждый пир генерирует пару ключей (private + public) на кривой P-256.
 * 2. Пиры обмениваются PUBLIC ключами (их можно перехватывать — это безопасно).
 * 3. Каждая сторона вычисляет shared secret: ownPrivate * otherPublic.
 * 4. Полученный shared secret используется как ключ AES-256 для шифрования.
 *
 * Даже если злоумышленник перехватит public keys, он не сможет
 * вычислить shared secret без private key (проблема дискретного логарифма).
 */
@Service
public class KeyExchangeService {
    private static final Logger log = LoggerFactory.getLogger(KeyExchangeService.class);
    private static final String EC_CURVE = "secp256r1"; // NIST P-256
    private static final String ALGORITHM = "EC";
    private static final String KEY_AGREEMENT = "ECDH";

    /** Локальная пара ключей (генерируется один раз при старте) */
    private KeyPair localKeyPair;

    /** Принятые public keys пиров: peerId -> PublicKey */
    private final Map<String, PublicKey> peerPublicKeys = new ConcurrentHashMap<>();

    /** Вычисленные shared secrets: peerId -> sharedSecret (AES key as Base64) */
    private final Map<String, String> sharedSecrets = new ConcurrentHashMap<>();

    public KeyExchangeService() {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance(ALGORITHM);
            keyGen.initialize(256); // P-256 curve
            localKeyPair = keyGen.generateKeyPair();
            log.info("ECDH key pair generated (P-256 curve)");
        } catch (Exception e) {
            log.error("Failed to generate ECDH key pair: {}", e.getMessage());
        }
    }

    /**
     * Получить наш Base64-encoded public key для отправки пиру.
     */
    public String getLocalPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(localKeyPair.getPublic().getEncoded());
    }

    /**
     * Получить наш private key (для передачи в KeyAgreement).
     * НЕ экспортируется — используется только локально.
     */
    PrivateKey getLocalPrivateKey() {
        return localKeyPair.getPrivate();
    }

    /**
     * Вычислить shared secret из нашего private key и public key пира.
     *
     * @param peerId идентификатор пира
     * @param peerPublicKeyBase64 public key пира в Base64
     * @return shared secret (AES-256 key в Base64) или null при ошибке
     */
    public String computeSharedSecret(String peerId, String peerPublicKeyBase64) {
        try {
            // Восстанавливаем PublicKey пира из Base64
            byte[] keyBytes = Base64.getDecoder().decode(peerPublicKeyBase64);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
            PublicKey peerPublicKey = keyFactory.generatePublic(keySpec);

            // Key Agreement: ourPrivateKey * peerPublicKey -> sharedSecret
            KeyAgreement keyAgreement = KeyAgreement.getInstance(KEY_AGREEMENT);
            keyAgreement.init(localKeyPair.getPrivate());
            keyAgreement.doPhase(peerPublicKey, true);

            byte[] sharedSecretBytes = keyAgreement.generateSecret();
            String sharedSecret = Base64.getEncoder().encodeToString(sharedSecretBytes);

            sharedSecrets.put(peerId, sharedSecret);
            peerPublicKeys.put(peerId, peerPublicKey);

            log.info("Shared secret computed for peer: {}", peerId);
            return sharedSecret;

        } catch (Exception e) {
            log.error("Failed to compute shared secret for peer {}: {}", peerId, e.getMessage());
            return null;
        }
    }

    /**
     * Получить shared secret для пира (после ECDH exchange).
     */
    public String getSharedSecret(String peerId) {
        return sharedSecrets.get(peerId);
    }

    /**
     * Проверить, есть ли shared secret для пира.
     */
    public boolean hasSharedSecret(String peerId) {
        return sharedSecrets.containsKey(peerId);
    }

    /**
     * Удалить shared secret (при отзыве доверия).
     */
    public void removeSharedSecret(String peerId) {
        sharedSecrets.remove(peerId);
        peerPublicKeys.remove(peerId);
        log.info("Removed shared secret for peer: {}", peerId);
    }

    /**
     * Шифрование сообщения с использованием ECDH shared secret.
     * Shared secret -> AES-256-GCM (через EncryptionService-подобную логику).
     */
    public byte[] encrypt(String peerId, String plaintext) {
        String secret = sharedSecrets.get(peerId);
        if (secret == null) {
            log.warn("No shared secret for peer: {}", peerId);
            return null;
        }

        try {
            // Shared secret (Base64) -> AES-256 key
            byte[] secretBytes = Base64.getDecoder().decode(secret);
            SecretKeySpec key = new SecretKeySpec(secretBytes, "AES");

            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);

            cipher.init(Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"));

            // Формат: IV + ciphertext
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(12 + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            return buffer.array();

        } catch (Exception e) {
            log.error("ECDH encryption failed for peer {}: {}", peerId, e.getMessage());
            return null;
        }
    }

    /**
     * Дешифрование сообщения с использованием ECDH shared secret.
     */
    public String decrypt(String peerId, byte[] encryptedData) {
        String secret = sharedSecrets.get(peerId);
        if (secret == null) {
            log.warn("No shared secret for peer: {}", peerId);
            return null;
        }

        try {
            byte[] secretBytes = Base64.getDecoder().decode(secret);
            SecretKeySpec key = new SecretKeySpec(secretBytes, "AES");

            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(encryptedData);
            byte[] iv = new byte[12];
            buffer.get(iv);

            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, iv));

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, "UTF-8");

        } catch (Exception e) {
            log.error("ECDH decryption failed for peer {}: {}", peerId, e.getMessage());
            return null;
        }
    }

    /**
     * Шифрование с явным секретом (для исходящих сообщений без ECDH).
     */
    public byte[] encryptWithSecret(String secret, String plaintext) {
        try {
            byte[] secretBytes = Base64.getDecoder().decode(secret);
            SecretKeySpec key = new SecretKeySpec(secretBytes, "AES");

            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);

            cipher.init(Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"));

            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(12 + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            return buffer.array();

        } catch (Exception e) {
            log.error("Encryption with secret failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Дешифрование с явным секретом.
     */
    public String decryptWithSecret(String secret, byte[] encryptedData) {
        try {
            byte[] secretBytes = Base64.getDecoder().decode(secret);
            SecretKeySpec key = new SecretKeySpec(secretBytes, "AES");

            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(encryptedData);
            byte[] iv = new byte[12];
            buffer.get(iv);

            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, iv));

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, "UTF-8");

        } catch (Exception e) {
            log.error("Decryption with secret failed: {}", e.getMessage());
            return null;
        }
    }
}
