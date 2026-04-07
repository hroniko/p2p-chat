package com.chat.p2p.controller;

import com.chat.p2p.service.P2PNetworkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Аутентификация и управление доверием между пирами.
 *
 * Использует ECDH key exchange (type 7/8) для безопасного обмена ключами.
 * Legacy auth (type 5/6) помечена @deprecated.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private P2PNetworkService networkService;

    /**
     * Запросить аутентификацию с пиром через ECDH key exchange.
     *
     * Вместо передачи shared secret в открытом виде,
     * пиры обмениваются ECDH public keys и вычисляют shared secret локально.
     */
    @PostMapping("/request")
    public Map<String, Object> requestAuth(@RequestBody Map<String, String> request) {
        String targetPeerId = request.get("peerId");

        if (targetPeerId == null || targetPeerId.isBlank()) {
            return Map.of("error", "Peer ID is required");
        }

        String token = UUID.randomUUID().toString();
        networkService.requestAuthECDH(targetPeerId, token);

        log.info("ECDH auth request sent to {}", targetPeerId);
        return Map.of("status", "ecdH_key_exchange_sent", "token", token);
    }

    /**
     * Ответить на запрос аутентификации.
     *
     * @deprecated ECDH auth не требует respondAuth — shared secret вычисляется автоматически.
     * Оставлено для обратной совместимости с legacy клиентами.
     */
    @Deprecated
    @PostMapping("/respond")
    public Map<String, Object> respondAuth(@RequestBody Map<String, String> request) {
        String peerId = request.get("peerId");
        String token = request.get("token");
        boolean approved = Boolean.parseBoolean(request.get("approved"));

        networkService.respondAuth(peerId, token, approved);

        return Map.of("status", approved ? "trusted" : "rejected");
    }
}
