package com.chat.p2p.controller;

import com.chat.p2p.service.P2PNetworkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * Настройки текущего пира (имя и т.д.).
 */
@RestController
@RequestMapping("/api")
public class SettingsController {
    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);
    private static final int MAX_REQUESTS_PER_MINUTE = 60;
    private final java.util.Map<String, SettingsController.RateLimitEntry> rateLimitMap = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    private P2PNetworkService networkService;

    /**
     * Установить имя текущего пира.
     */
    @PostMapping("/set-name")
    public void setName(@RequestParam String name,
                        @RequestHeader(value = "X-Client-Id", required = false) String clientId) {
        if (clientId != null && !checkRateLimit(clientId)) {
            log.warn("Rate limit exceeded for client: {}", clientId);
            return;
        }

        networkService.setPeerName(name);
        log.info("Peer name set to: {}", name);
    }

    private boolean checkRateLimit(String clientId) {
        long now = System.currentTimeMillis();
        RateLimitEntry entry = rateLimitMap.compute(clientId, (k, v) -> {
            if (v == null || now - v.windowStart > 60000) {
                return new RateLimitEntry(now);
            }
            return v;
        });

        synchronized (entry) {
            if (now - entry.windowStart > 60000) {
                entry.windowStart = now;
                entry.count = 0;
            }
            entry.count++;
            return entry.count <= MAX_REQUESTS_PER_MINUTE;
        }
    }

    private static class RateLimitEntry {
        int count;
        long windowStart;

        RateLimitEntry(long windowStart) {
            this.count = 0;
            this.windowStart = windowStart;
        }
    }
}
