package com.chat.p2p.controller;

import com.chat.p2p.service.P2PNetworkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Информация о текущем пире.
 */
@RestController
public class InfoController {

    @Autowired
    private P2PNetworkService networkService;

    @GetMapping("/api/info")
    public Map<String, Object> getInfo() {
        return Map.of(
            "peerId", networkService.getPeerId(),
            "p2pPort", networkService.getP2pPort()
        );
    }
}
