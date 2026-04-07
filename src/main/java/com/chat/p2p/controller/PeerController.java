package com.chat.p2p.controller;

import com.chat.p2p.model.Peer;
import com.chat.p2p.service.MdnsDiscoveryService;
import com.chat.p2p.service.P2PNetworkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Управление пирами: список, добавление, доверенные пиры.
 */
@RestController
@RequestMapping("/api")
public class PeerController {
    private static final Logger log = LoggerFactory.getLogger(PeerController.class);

    @Autowired
    private P2PNetworkService networkService;

    @Autowired
    private MdnsDiscoveryService mdnsDiscovery;

    /**
     * Получить все обнаруженные пиры (UDP broadcast + mDNS).
     */
    @GetMapping("/peers")
    public Map<String, Peer> getPeers() {
        Map<String, Peer> allPeers = new HashMap<>(networkService.getPeers());
        allPeers.putAll(mdnsDiscovery.getDiscoveredPeers());
        return allPeers;
    }

    /**
     * Получить доверенные пиры.
     */
    @GetMapping("/peers/trusted")
    public Map<String, Peer> getTrustedPeers() {
        return networkService.getTrustedPeers();
    }

    /**
     * Добавить пира вручную по адресу и порту.
     * Полезно когда пиры за разными роутерами и broadcast/mDNS не работают.
     */
    @PostMapping("/peers/add")
    public Map<String, Object> addPeer(@RequestBody Map<String, String> request) {
        String address = request.get("address");
        String portStr = request.get("port");

        if (address == null || address.isBlank()) {
            return Map.of("error", "Address is required");
        }
        if (portStr == null) {
            return Map.of("error", "Port is required");
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) {
                return Map.of("error", "Invalid port number");
            }
        } catch (NumberFormatException e) {
            return Map.of("error", "Invalid port number: " + portStr);
        }

        networkService.addManualPeer(address, port);
        log.info("Manual peer added: {}:{}", address, port);

        return Map.of("status", "peer_added", "address", address, "port", port);
    }

    /**
     * Установить статус текущего пира.
     * Доступные статусы: online, away, busy, offline.
     */
    @PostMapping("/status")
    public Map<String, Object> setStatus(@RequestBody Map<String, String> request) {
        String status = request.get("status");
        String message = request.get("message");

        if (status == null || !status.matches("online|away|busy|offline")) {
            return Map.of("error", "Invalid status. Use: online, away, busy, offline");
        }

        networkService.setStatus(status);
        if (message != null && !message.isBlank()) {
            networkService.setStatusMessage(message);
        }

        log.info("Status set to: {} ({})", status, message);
        return Map.of("status", "updated", "newStatus", status);
    }

    /**
     * Обновить bio (о себе).
     */
    @PostMapping("/bio")
    public Map<String, Object> setBio(@RequestBody Map<String, String> request) {
        String bio = request.get("bio");
        if (bio != null && bio.length() > 200) {
            return Map.of("error", "Bio too long (max 200 chars)");
        }

        networkService.setBio(bio);
        return Map.of("status", "updated", "bio", networkService.getBio());
    }
}
