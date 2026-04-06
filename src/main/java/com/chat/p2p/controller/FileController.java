package com.chat.p2p.controller;

import com.chat.p2p.entity.FileEntity;
import com.chat.p2p.repository.FileRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final String FILES_DIR = "files";
    private static final String THUMBS_DIR = "files/thumbs";
    private static final int CHUNK_SIZE = 65536;
    private static final int TRANSFER_THREADS = 8;

    @Autowired
    private FileRepository fileRepository;

    private final ExecutorService transferExecutor = Executors.newFixedThreadPool(TRANSFER_THREADS);
    private final ConcurrentHashMap<String, TransferState> transfers = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(Paths.get(FILES_DIR));
            Files.createDirectories(Paths.get(THUMBS_DIR));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @PostMapping("/upload")
    public Map<String, Object> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("peerId") String peerId,
            @RequestParam("senderId") String senderId) throws IOException {
        
        String fileId = UUID.randomUUID().toString();
        String originalName = file.getOriginalFilename();
        String extension = originalName != null && originalName.contains(".") ? 
            originalName.substring(originalName.lastIndexOf(".")) : "";
        String storedName = fileId + extension;
        
        Path filePath = Paths.get(FILES_DIR, storedName);
        Files.write(filePath, file.getBytes());

        String thumbnailPath = null;
        String contentType = file.getContentType();
        if (contentType != null && contentType.startsWith("image/")) {
            thumbnailPath = generateThumbnail(fileId, extension, file.getBytes());
        }

        FileEntity entity = new FileEntity();
        entity.setId(fileId);
        entity.setPeerId(peerId);
        entity.setSenderId(senderId);
        entity.setFileName(originalName);
        entity.setFileType(contentType);
        entity.setFileSize(file.getSize());
        entity.setFilePath(storedName);
        entity.setThumbnailPath(thumbnailPath);
        entity.setTimestamp(LocalDateTime.now());
        fileRepository.save(entity);

        Map<String, Object> response = new HashMap<>();
        response.put("fileId", fileId);
        response.put("fileName", originalName);
        response.put("fileType", contentType);
        response.put("fileSize", file.getSize());
        return response;
    }

    @GetMapping("/{fileId}/info")
    public Map<String, Object> getFileInfo(@PathVariable String fileId) {
        var entity = fileRepository.findById(fileId).orElse(null);
        if (entity == null) {
            return Map.of("exists", false);
        }
        return Map.of(
            "exists", true,
            "filePath", entity.getFilePath(),
            "fileName", entity.getFileName(),
            "fileSize", entity.getFileSize()
        );
    }

    private String generateThumbnail(String fileId, String extension, byte[] imageData) throws IOException {
        String thumbName = fileId + "_thumb.jpg";
        Path thumbPath = Paths.get(THUMBS_DIR, thumbName);
        
        ByteArrayInputStream bais = new ByteArrayInputStream(imageData);
        BufferedImage originalImage = javax.imageio.ImageIO.read(bais);
        
        if (originalImage != null) {
            int width = originalImage.getWidth();
            int height = originalImage.getHeight();
            int thumbWidth = width > height ? 150 : 100;
            int thumbHeight = width > height ? 100 : 150;
            
            BufferedImage thumb = new BufferedImage(thumbWidth, thumbHeight, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = thumb.createGraphics();
            g.drawImage(originalImage.getScaledInstance(thumbWidth, thumbHeight, java.awt.Image.SCALE_SMOOTH), 0, 0, null);
            g.dispose();
            
            javax.imageio.ImageIO.write(thumb, "jpg", thumbPath.toFile());
            return thumbName;
        }
        return null;
    }

    @GetMapping("/{fileId}")
    public ResponseEntity<byte[]> getFile(@PathVariable String fileId) {
        try {
            FileEntity entity = fileRepository.findById(fileId).orElse(null);
            if (entity == null) return ResponseEntity.notFound().build();
            
            Path path = Paths.get(FILES_DIR, entity.getFilePath());
            if (!Files.exists(path)) return ResponseEntity.notFound().build();
            
            byte[] data = Files.readAllBytes(path);
            String contentType = entity.getFileType() != null ? entity.getFileType() : "application/octet-stream";
            
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header("Content-Disposition", "attachment; filename=\"" + entity.getFileName() + "\"")
                    .body(data);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{fileId}/thumbnail")
    public ResponseEntity<byte[]> getThumbnail(@PathVariable String fileId) {
        try {
            FileEntity entity = fileRepository.findById(fileId).orElse(null);
            if (entity == null || entity.getThumbnailPath() == null) return ResponseEntity.notFound().build();
            
            Path path = Paths.get(THUMBS_DIR, entity.getThumbnailPath());
            if (!Files.exists(path)) return ResponseEntity.notFound().build();
            
            byte[] data = Files.readAllBytes(path);
            return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(data);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private static class TransferState {
        String fileId;
        long totalSize;
        long received;
        String senderId;

        TransferState(String fileId, long totalSize, String senderId) {
            this.fileId = fileId;
            this.totalSize = totalSize;
            this.received = 0;
            this.senderId = senderId;
        }
    }
}
