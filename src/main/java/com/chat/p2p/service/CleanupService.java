package com.chat.p2p.service;

import com.chat.p2p.entity.FileEntity;
import com.chat.p2p.repository.FileRepository;
import com.chat.p2p.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Сервис автоматической очистки старых данных.
 *
 * Предотвращает бесконечный рост БД и дискового пространства.
 * Настраивается через application.yml:
 * - chat.messages.ttl-days — сколько дней хранить сообщения
 * - chat.files.ttl-days — сколько дней хранить файлы
 * - chat.files.max-total-size-mb — максимальный размер всех файлов
 */
@Service
public class CleanupService {
    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);

    @Autowired(required = false)
    private MessageRepository messageRepository;

    @Autowired(required = false)
    private FileRepository fileRepository;

    @Value("${chat.messages.ttl-days:90}")
    private int messageTtlDays;

    @Value("${chat.files.ttl-days:30}")
    private int fileTtlDays;

    @Value("${chat.files.max-total-size-mb:5000}")
    private long maxTotalSizeMb;

    /**
     * Очистка старых сообщений — каждую ночь в 3:00.
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupOldMessages() {
        if (messageRepository == null) {
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(messageTtlDays);
        long count = messageRepository.countByTimestampBefore(cutoff);

        if (count > 0) {
            messageRepository.deleteOlderThan(cutoff);
            log.info("Cleaned up {} messages older than {} days", count, messageTtlDays);
        }
    }

    /**
     * Очистка старых файлов — каждое воскресенье в 4:00.
     */
    @Scheduled(cron = "0 0 4 * * SUN")
    public void cleanupOldFiles() {
        if (fileRepository == null) {
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(fileTtlDays);

        // Находим старые файлы в БД
        List<FileEntity> oldFiles = fileRepository.findAll().stream()
                .filter(f -> f.getTimestamp().isBefore(cutoff))
                .collect(Collectors.toList());

        for (FileEntity file : oldFiles) {
            // Удаляем файл с диска
            try {
                Path filePath = Paths.get(file.getFilePath());
                Files.deleteIfExists(filePath);

                if (file.getThumbnailPath() != null) {
                    Files.deleteIfExists(Paths.get(file.getThumbnailPath()));
                }

                fileRepository.delete(file);
                log.debug("Deleted old file: {}", file.getFileName());
            } catch (IOException e) {
                log.warn("Failed to delete file {}: {}", file.getFileName(), e.getMessage());
            }
        }

        if (!oldFiles.isEmpty()) {
            log.info("Cleaned up {} files older than {} days", oldFiles.size(), fileTtlDays);
        }
    }

    /**
     * Проверка общего размера файлов — каждый день в 5:00.
     * Если превышен лимит — удаляем самые старые файлы.
     */
    @Scheduled(cron = "0 0 5 * * ?")
    public void enforceMaxTotalSize() {
        if (fileRepository == null) {
            return;
        }

        long totalSize = calculateTotalFileSize();
        long maxSizeBytes = maxTotalSizeMb * 1024L * 1024L;

        if (totalSize > maxSizeBytes) {
            log.warn("Total file size ({} MB) exceeds limit ({} MB). Cleaning up oldest files.",
                    totalSize / (1024 * 1024), maxTotalSizeMb);

            List<FileEntity> allFiles = fileRepository.findAll().stream()
                    .sorted((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()))
                    .collect(Collectors.toList());

            for (FileEntity file : allFiles) {
                if (totalSize <= maxSizeBytes) break;

                try {
                    Path filePath = Paths.get(file.getFilePath());
                    long fileSize = Files.size(filePath);
                    Files.deleteIfExists(filePath);

                    if (file.getThumbnailPath() != null) {
                        Files.deleteIfExists(Paths.get(file.getThumbnailPath()));
                    }

                    fileRepository.delete(file);
                    totalSize -= fileSize;
                    log.debug("Deleted file to free space: {} ({} MB)",
                            file.getFileName(), fileSize / (1024 * 1024));
                } catch (IOException e) {
                    log.warn("Failed to delete file {}: {}", file.getFileName(), e.getMessage());
                }
            }
        }
    }

    /**
     * Посчитать общий размер всех файлов.
     */
    private long calculateTotalFileSize() {
        Path filesDir = Paths.get("files");
        if (!Files.exists(filesDir)) {
            return 0;
        }

        try (Stream<Path> walk = Files.walk(filesDir, 1)) {
            return walk
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            log.warn("Failed to calculate total file size: {}", e.getMessage());
            return 0;
        }
    }
}
