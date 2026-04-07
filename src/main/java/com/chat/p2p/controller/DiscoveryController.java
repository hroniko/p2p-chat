package com.chat.p2p.controller;

import com.chat.p2p.service.MdnsDiscoveryService;
import com.chat.p2p.service.P2PNetworkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Информация о discovery механизмах (mDNS, UDP).
 */
@RestController
@RequestMapping("/api/discovery")
public class DiscoveryController {

    @Autowired
    private MdnsDiscoveryService mdnsDiscovery;

    @Autowired
    private P2PNetworkService networkService;

    /**
     * Получить статус discovery механизмов.
     */
    @GetMapping("/info")
    public Map<String, Object> getDiscoveryInfo() {
        return Map.of(
            "mdnsAvailable", mdnsDiscovery.isAvailable(),
            "mdnsPeers", mdnsDiscovery.getDiscoveredPeers().size(),
            "udpPeers", networkService.getPeers().size()
        );
    }
}
