package com.chat.p2p.controller;

import com.chat.p2p.entity.FileEntity;
import com.chat.p2p.repository.FileRepository;
import com.chat.p2p.service.P2PNetworkService;
import com.chat.p2p.service.SharedState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Передача файлов: отправка файлов пирам и отслеживание прогресса.
 */
@RestController
@RequestMapping("/api")
public class FileTransferController {
    private static final Logger log = LoggerFactory.getLogger(FileTransferController.class);

    @Autowired
    private P2PNetworkService networkService;

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private SharedState sharedState;

    /**
     * Получить прогресс активных передач файлов.
     */
    @GetMapping("/transfer-progress")
    public List<SharedState.FileTransferProgress> getTransferProgress() {
        return sharedState.getTransferProgress();
    }

    /**
     * Отправить файл пиру.
     */
    @PostMapping("/send-file")
    public Map<String, Object> sendFile(@RequestBody SendFileRequest request,
                                        @RequestHeader(value = "X-Client-Id", required = false) String clientId) {
        if (request.targetId == null || request.targetId.isBlank()) {
            return Map.of("error", "Target peer ID is required");
        }
        if (request.fileId == null || request.fileId.isBlank()) {
            return Map.of("error", "File ID is required");
        }

        var entity = fileRepository.findById(request.fileId).orElse(null);
        if (entity != null) {
            log.info("Sending file {} to peer {}", request.fileName, request.targetId);
            networkService.sendFile(request.targetId, request.fileId, request.fileName, request.fileSize, entity.getFilePath());
            return Map.of("status", "file_sent", "fileId", request.fileId);
        } else {
            log.warn("File not found in DB: {}", request.fileId);
            return Map.of("error", "File not found: " + request.fileId);
        }
    }

    /**
     * Запрос на отправку файла (legacy DTO).
     */
    public static class SendFileRequest {
        public String targetId;
        public String fileId;
        public String fileName;
        public long fileSize;
        public String filePath;
    }
}
