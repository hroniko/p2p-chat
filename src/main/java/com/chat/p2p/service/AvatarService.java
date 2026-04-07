package com.chat.p2p.service;

import com.chat.p2p.entity.UserAvatar;
import com.chat.p2p.repository.UserAvatarRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Сервис управления аватарами пользователей.
 *
 * Функции:
 * - Загрузка аватара (с генерацией thumbnail)
 * - Получение аватара и thumbnail
 * - Удаление аватара
 */
@Service
public class AvatarService {
    private static final Logger log = LoggerFactory.getLogger(AvatarService.class);
    private static final String AVATAR_DIR = "files/avatars";
    private static final String THUMBS_DIR = "files/avatars/thumbs";
    private static final int AVATAR_SIZE = 200;
    private static final int THUMB_SIZE = 50;

    @Autowired(required = false)
    private UserAvatarRepository avatarRepository;

    public AvatarService() {
        try {
            Files.createDirectories(Paths.get(AVATAR_DIR));
            Files.createDirectories(Paths.get(THUMBS_DIR));
        } catch (IOException e) {
            log.error("Failed to create avatar directories: {}", e.getMessage());
        }
    }

    /**
     * Загрузить аватар для пира.
     * Возвращает UserAvatar с путями к файлам.
     */
    public UserAvatar uploadAvatar(String peerId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        String extension = getExtension(file.getOriginalFilename());
        String avatarName = peerId + extension;
        String thumbName = peerId + "_thumb.jpg";

        try {
            // Сохраняем оригинал
            Path avatarPath = Paths.get(AVATAR_DIR, avatarName);
            Files.write(avatarPath, file.getBytes());

            // Генерируем thumbnail
            String thumbnailPath = generateThumbnail(file.getBytes(), thumbName);

            // Сохраняем в БД
            UserAvatar avatar = new UserAvatar(peerId, avatarName, thumbnailPath);
            if (avatarRepository != null) {
                avatarRepository.save(avatar);
            }

            log.info("Avatar uploaded for peer {}: {}", peerId, avatarName);
            return avatar;

        } catch (IOException e) {
            log.error("Failed to upload avatar for peer {}: {}", peerId, e.getMessage());
            throw new RuntimeException("Failed to upload avatar", e);
        }
    }

    /**
     * Получить аватар пира.
     */
    public UserAvatar getAvatar(String peerId) {
        if (avatarRepository != null) {
            return avatarRepository.findById(peerId).orElse(null);
        }
        // Fallback: проверяем файл
        Path path = Paths.get(AVATAR_DIR, peerId + ".png");
        if (Files.exists(path)) {
            UserAvatar avatar = new UserAvatar(peerId, path.getFileName().toString(), null);
            return avatar;
        }
        return null;
    }

    /**
     * Удалить аватар пира.
     */
    public void deleteAvatar(String peerId) {
        try {
            if (avatarRepository != null) {
                UserAvatar avatar = avatarRepository.findById(peerId).orElse(null);
                if (avatar != null) {
                    Files.deleteIfExists(Paths.get(AVATAR_DIR, avatar.getFilePath()));
                    if (avatar.getThumbnailPath() != null) {
                        Files.deleteIfExists(Paths.get(THUMBS_DIR, avatar.getThumbnailPath()));
                    }
                    avatarRepository.delete(avatar);
                }
            } else {
                // Без БД — просто удаляем файлы
                for (String ext : new String[]{".png", ".jpg", ".jpeg", ".webp"}) {
                    Files.deleteIfExists(Paths.get(AVATAR_DIR, peerId + ext));
                    Files.deleteIfExists(Paths.get(THUMBS_DIR, peerId + "_thumb.jpg"));
                }
            }
            log.info("Avatar deleted for peer: {}", peerId);
        } catch (IOException e) {
            log.warn("Failed to delete avatar for peer {}: {}", peerId, e.getMessage());
        }
    }

    /**
     * Сгенерировать квадратный thumbnail.
     */
    private String generateThumbnail(byte[] imageData, String thumbName) throws IOException {
        Path thumbPath = Paths.get(THUMBS_DIR, thumbName);

        BufferedImage original = ImageIO.read(new ByteArrayInputStream(imageData));
        if (original == null) return null;

        // Crop to square
        int size = Math.min(original.getWidth(), original.getHeight());
        int x = (original.getWidth() - size) / 2;
        int y = (original.getHeight() - size) / 2;

        BufferedImage square = original.getSubimage(x, y, size, size);
        BufferedImage thumb = new BufferedImage(THUMB_SIZE, THUMB_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = thumb.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(square.getScaledInstance(THUMB_SIZE, THUMB_SIZE, Image.SCALE_SMOOTH), 0, 0, null);
        g.dispose();

        ImageIO.write(thumb, "jpg", thumbPath.toFile());
        return thumbName;
    }

    /**
     * Получить расширение файла.
     */
    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return ".png";
        return filename.substring(filename.lastIndexOf(".")).toLowerCase();
    }

    /**
     * Получить путь к файлу аватара для чтения.
     */
    public Path getAvatarPath(String fileName) {
        return Paths.get(AVATAR_DIR, fileName);
    }

    /**
     * Получить путь к thumbnail.
     */
    public Path getThumbPath(String fileName) {
        return Paths.get(THUMBS_DIR, fileName);
    }
}
