package com.chat.p2p.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сервис сквозного шифрования (E2E).
 * 
 * Использует AES-256-GCM - современный стандарт:
 * - AES-256 для шифрования
 * - GCM (Galois/Counter Mode) для аутентификации
 * - 12-byte IV (Initialization Vector) для рандомизации
 * 
 * Ключ формируется из shared secret пользователей.
 * Каждое сообщение имеет уникальный IV - даже при одинаковом ключе
 * шифротекст будет разный.
 */
@Service
public class EncryptionService {
    private static final Logger log = LoggerFactory.getLogger(EncryptionService.class);
    
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int KEY_SIZE = 256;
    private static final int IV_SIZE = 12;
    private static final int TAG_SIZE = 128; // бит

    private final SecureRandom random = new SecureRandom();
    
    /** Карта ключей для пиров: peerId -> secret -> key */
    private final Map<String, SecretKey> peerKeys = new ConcurrentHashMap<>();

    /**
     * Генерация ключа из shared secret.
     * 
     * Shared secret -> SHA-256 -> 32-byte ключ
     * Это превращает текстовый пароль в криптографический ключ.
     */
    public SecretKey generateKey(String secret) {
        try {
            // SHA-256 хэшируем секрет
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(secret.getBytes("UTF-8"));
            
            SecretKey key = new SecretKeySpec(keyBytes, "AES");
            log.debug("Generated encryption key from secret");
            return key;
        } catch (Exception e) {
            log.error("Failed to generate key: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Сохраняем ключ для пира.
     * Вызывается после успешной авторизации.
     */
    public void setPeerKey(String peerId, String secret) {
        SecretKey key = generateKey(secret);
        if (key != null) {
            peerKeys.put(peerId, key);
            log.info("Stored encryption key for peer: {}", peerId);
        }
    }

    /**
     * Удаляем ключ при отзыве доверия.
     */
    public void removePeerKey(String peerId) {
        peerKeys.remove(peerId);
        log.info("Removed encryption key for peer: {}", peerId);
    }

    /**
     * Проверяем, есть ли ключ для пира.
     */
    public boolean hasKey(String peerId) {
        return peerKeys.containsKey(peerId);
    }

    /**
     * Шифрование сообщения.
     * 
     * Формат вывода: [12 bytes IV][encrypted data + 16 bytes auth tag]
     */
    public byte[] encrypt(String peerId, String plaintext) {
        SecretKey key = peerKeys.get(peerId);
        if (key == null) {
            log.warn("No encryption key for peer: {}", peerId);
            return null;
        }

        try {
            // Генерируем случайный IV
            byte[] iv = new byte[IV_SIZE];
            random.nextBytes(iv);

            // Шифруем
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_SIZE, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);
            
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"));

            // Формат: IV + ciphertext
            ByteBuffer buffer = ByteBuffer.allocate(IV_SIZE + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            
            return buffer.array();
            
        } catch (Exception e) {
            log.error("Encryption failed for peer {}: {}", peerId, e.getMessage());
            return null;
        }
    }

    /**
     * Дешифрование сообщения.
     * 
     * Формат ввода: [12 bytes IV][encrypted data + 16 bytes auth tag]
     */
    public String decrypt(String peerId, byte[] encryptedData) {
        SecretKey key = peerKeys.get(peerId);
        if (key == null) {
            log.warn("No encryption key for peer: {}", peerId);
            return null;
        }

        try {
            // Извлекаем IV
            ByteBuffer buffer = ByteBuffer.wrap(encryptedData);
            byte[] iv = new byte[IV_SIZE];
            buffer.get(iv);
            
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            // Дешифруем
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_SIZE, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, "UTF-8");
            
        } catch (Exception e) {
            log.error("Decryption failed for peer {}: {}", peerId, e.getMessage());
            return null;
        }
    }

    /**
     * Шифрование с явным ключом (для исходящих сообщений).
     */
    public byte[] encryptWithKey(String secret, String plaintext) {
        SecretKey key = generateKey(secret);
        if (key == null) return null;

        try {
            byte[] iv = new byte[IV_SIZE];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_SIZE, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);
            
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"));

            ByteBuffer buffer = ByteBuffer.allocate(IV_SIZE + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            
            return buffer.array();
            
        } catch (Exception e) {
            log.error("Encryption failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Дешифрование с явным ключом (для входящих сообщений).
     */
    public String decryptWithKey(String secret, byte[] encryptedData) {
        SecretKey key = generateKey(secret);
        if (key == null) return null;

        try {
            ByteBuffer buffer = ByteBuffer.wrap(encryptedData);
            byte[] iv = new byte[IV_SIZE];
            buffer.get(iv);
            
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_SIZE, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, "UTF-8");
            
        } catch (Exception e) {
            log.error("Decryption failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Конвертация в Base64 для отправки.
     */
    public String encryptToBase64(String peerId, String plaintext) {
        byte[] encrypted = encrypt(peerId, plaintext);
        if (encrypted == null) return null;
        return Base64.getEncoder().encodeToString(encrypted);
    }

    /**
     * Конвертация из Base64 при получении.
     */
    public String decryptFromBase64(String peerId, String base64Data) {
        try {
            byte[] encrypted = Base64.getDecoder().decode(base64Data);
            return decrypt(peerId, encrypted);
        } catch (Exception e) {
            log.error("Base64 decode failed: {}", e.getMessage());
            return null;
        }
    }
}