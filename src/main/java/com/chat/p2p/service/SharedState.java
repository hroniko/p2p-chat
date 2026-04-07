package com.chat.p2p.service;

import com.chat.p2p.entity.FileEntity;
import com.chat.p2p.model.P2PMessage;
import com.chat.p2p.repository.FileRepository;
import com.chat.p2p.service.P2PNetworkService.FileTransferListener;
import com.chat.p2p.service.P2PNetworkService.NetworkListener;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Общее состояние приложения.
 *
 * Хранит:
 * - pendingMessages — буфер входящих сообщений
 * - transferProgress — прогресс загрузки файлов
 * - processedFiles — уже обработанные файлы (чтобы не дублировать)
 *
 * Также настраивает слушателей сети и файлового обмена.
 * Раньше всё это было в ChatController — теперь вынесено в отдельный компонент.
 */
@Component
public class SharedState {
    private static final Logger log = LoggerFactory.getLogger(SharedState.class);

    @Autowired
    private P2PNetworkService networkService;

    @Autowired(required = false)
    private FileRepository fileRepository;

    /** Буфер входящих сообщений (читается через /api/pending-messages) */
    private final List<P2PMessage> pendingMessages = Collections.synchronizedList(new ArrayList<>());

    /** Прогресс активных передач файлов */
    private final List<FileTransferProgress> transferProgress = Collections.synchronizedList(new ArrayList<>());

    /** ID уже обработанных файлов (защита от дубликатов) */
    private final Set<String> processedFiles = Collections.synchronizedSet(new HashSet<>());

    @PostConstruct
    public void init() {
        // Слушатель входящих сообщений
        networkService.addListener(new NetworkListener() {
            @Override
            public void onMessageReceived(P2PMessage message) {
                if ("MESSAGE".equals(message.getType())) {
                    log.debug("Received message from {}: {}", message.getSenderId(), message.getContent());
                    pendingMessages.add(message);

                    // Отправляем подтверждение доставки
                    if (message.getId() != null) {
                        networkService.sendDeliveryConfirmation(message.getSenderId(), message.getId());
                    }
                }
            }
        });

        // Слушатель файлового обмена
        networkService.addFileListener(new FileTransferListener() {
            @Override
            public void onProgress(String transferId, long received, long total) {
                synchronized (transferProgress) {
                    transferProgress.removeIf(p -> p.transferId().equals(transferId));
                    transferProgress.add(new FileTransferProgress(transferId, received, total, false));
                }
            }

            @Override
            public void onComplete(String transferId, String fileName, long fileSize) {
                // Передача чанка завершена — ничего не делаем
            }

            @Override
            public void onFileComplete(String transferId, String fileId, String fileName, long fileSize, String senderId) {
                // Переименовываем временный файл
                try {
                    Path src = Paths.get("files", fileName);
                    if (Files.exists(src)) {
                        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf(".")) : "";
                        Path dest = Paths.get("files", fileId + ext);
                        Files.move(src, dest);

                        if (fileRepository != null) {
                            var entity = new FileEntity();
                            entity.setId(fileId);
                            entity.setPeerId(senderId);
                            entity.setSenderId(senderId);
                            entity.setFileName(fileName);
                            entity.setFilePath(dest.getFileName().toString());
                            entity.setFileSize(fileSize);
                            entity.setTimestamp(LocalDateTime.now());
                            fileRepository.save(entity);
                        }
                    }
                } catch (IOException e) {
                    log.error("Error saving received file: {}", e.getMessage());
                }

                // Обновляем прогресс
                synchronized (transferProgress) {
                    transferProgress.removeIf(p -> p.transferId().equals(transferId));
                    transferProgress.add(new FileTransferProgress(transferId, fileSize, fileSize, true));
                }

                // Добавляем сообщение-заглушку о файле
                if (!processedFiles.contains(transferId)) {
                    processedFiles.add(transferId);
                    P2PMessage msg = new P2PMessage("MESSAGE", senderId, "", "[File] " + fileName);
                    msg.setFileId(fileId);
                    msg.setFileName(fileName);
                    msg.setFileSize(fileSize);
                    pendingMessages.add(msg);
                }
            }
        });

        log.info("SharedState initialized with network and file listeners");
    }

    // === Публичные методы доступа ===

    public List<P2PMessage> getPendingMessages() {
        synchronized (pendingMessages) {
            List<P2PMessage> messages = new ArrayList<>(pendingMessages);
            if (!messages.isEmpty()) {
                log.debug("Returning {} pending messages", messages.size());
            }
            pendingMessages.clear();
            return messages;
        }
    }

    public List<FileTransferProgress> getTransferProgress() {
        synchronized (transferProgress) {
            return new ArrayList<>(transferProgress);
        }
    }

    /**
     * Запись о прогрессе передачи файла.
     */
    public record FileTransferProgress(String transferId, long received, long total, boolean complete) {}
}
