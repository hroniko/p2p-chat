package com.chat.p2p.service;

import com.chat.p2p.model.DiscoveryMessage;
import com.chat.p2p.model.Peer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.jmdns.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * mDNS (Multicast DNS) discovery сервис.
 *
 * Проблема UDP broadcast (255.255.255.255):
 * - Не проходит через роутеры
 * - Работает только в одном broadcast-домене
 *
 * Решение mDNS:
 * - Использует multicast (224.0.0.251:5353)
 * - Проходит через большинство роутеров в локальной сети
 * - Стандартный протокол (используется Bonjour, Avahi)
 *
 * Каждый пир регистрирует сервис "_p2pchat._tcp.local."
 * и слушает появление других пиров.
 */
@Service
public class MdnsDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(MdnsDiscoveryService.class);
    private static final String SERVICE_TYPE = "_p2pchat._tcp.local.";
    private static final String SERVICE_NAME = "P2P Chat Peer";

    private JmDNS jmdns;
    private ServiceInfo serviceInfo;

    @Value("${chat.p2p.port:9090}")
    private int p2pPort;

    @Value("${server.port:8089}")
    private int serverPort;

    private String peerId;
    private String peerName;

    private final Map<String, Peer> discoveredPeers = new ConcurrentHashMap<>();
    private Consumer<Peer> onPeerDiscovered;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        try {
            // Находим лучший сетевой интерфейс (не loopback)
            InetAddress address = getBestAddress();
            if (address == null) {
                log.warn("No suitable network interface found for mDNS discovery");
                return;
            }

            jmdns = JmDNS.create(address, SERVICE_NAME);
            log.info("mDNS initialized on {}", address.getHostAddress());

            // Регистрируем наш сервис
            peerId = java.util.UUID.randomUUID().toString().substring(0, 8);
            peerName = "Unknown";

            registerService();

            // Слушаем появление других пиров
            jmdns.addServiceListener(SERVICE_TYPE, new ServiceListener() {
                @Override
                public void serviceAdded(ServiceEvent event) {
                    jmdns.requestServiceInfo(event.getType(), event.getName());
                }

                @Override
                public void serviceRemoved(ServiceEvent event) {
                    log.debug("mDNS peer removed: {}", event.getName());
                }

                @Override
                public void serviceResolved(ServiceEvent event) {
                    ServiceInfo info = event.getInfo();
                    handlePeerDiscovered(info);
                }
            });

        } catch (IOException e) {
            log.warn("mDNS initialization failed: {}. UDP broadcast will be used as fallback.", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        if (jmdns != null) {
            try {
                if (serviceInfo != null) {
                    jmdns.unregisterService(serviceInfo);
                }
                jmdns.close();
                log.info("mDNS service closed");
            } catch (IOException e) {
                log.warn("Error closing mDNS: {}", e.getMessage());
            }
        }
    }

    /**
     * Зарегистрировать наш сервис в mDNS.
     */
    private void registerService() {
        try {
            Map<String, String> props = Map.of(
                "peerId", peerId,
                "peerName", peerName,
                "p2pPort", String.valueOf(p2pPort),
                "serverPort", String.valueOf(serverPort)
            );

            serviceInfo = ServiceInfo.create(SERVICE_TYPE, SERVICE_NAME + " (" + peerId + ")",
                    serverPort, 0, 0, false, props);

            jmdns.registerService(serviceInfo);
            log.info("mDNS service registered: {}:{} (P2P:{})", 
                    serviceInfo.getHostAddress(), serverPort, p2pPort);
        } catch (Exception e) {
            log.error("Failed to register mDNS service: {}", e.getMessage());
        }
    }

    /**
     * Обновить информацию о пире (имя).
     */
    public void updatePeerName(String name) {
        this.peerName = name;
        // Перерегистрируем сервис с новым именем
        if (jmdns != null && serviceInfo != null) {
            try {
                jmdns.unregisterService(serviceInfo);
                registerService();
            } catch (Exception e) {
                log.warn("Failed to re-register mDNS service: {}", e.getMessage());
            }
        }
    }

    /**
     * Обработать обнаруженного пира.
     */
    private void handlePeerDiscovered(ServiceInfo info) {
        try {
            String peerIdProp = info.getPropertyString("peerId");
            String peerNameProp = info.getPropertyString("peerName");
            String p2pPortProp = info.getPropertyString("p2pPort");

            if (peerIdProp == null || p2pPortProp == null) {
                return;
            }

            // Пропускаем самих себя
            if (peerIdProp.equals(this.peerId)) {
                return;
            }

            int peerPort = Integer.parseInt(p2pPortProp);
            String address = info.getHostAddress();

            Peer peer = new Peer();
            peer.setId(peerIdProp);
            peer.setName(peerNameProp != null ? peerNameProp : "Unknown");
            peer.setAddress(address);
            peer.setPort(peerPort);
            peer.setConnected(true);
            peer.setLastSeen(System.currentTimeMillis());

            // Новый пир?
            if (!discoveredPeers.containsKey(peerIdProp)) {
                discoveredPeers.put(peerIdProp, peer);
                log.info("mDNS peer discovered: {} ({})", peer.getName(), address);

                if (onPeerDiscovered != null) {
                    onPeerDiscovered.accept(peer);
                }
            } else {
                // Обновляем lastSeen
                discoveredPeers.get(peerIdProp).setLastSeen(System.currentTimeMillis());
            }

        } catch (Exception e) {
            log.warn("Error processing mDNS service: {}", e.getMessage());
        }
    }

    /**
     * Найти лучший сетевой адрес (не loopback).
     */
    private InetAddress getBestAddress() throws IOException {
        // Пробуем localhost как fallback
        InetAddress localhost = InetAddress.getLocalHost();
        if (!localhost.isLoopbackAddress()) {
            return localhost;
        }

        // Ищем не-loopback интерфейс
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (ni.isLoopback() || !ni.isUp() || ni.isVirtual()) {
                continue;
            }

            Enumeration<InetAddress> addresses = ni.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress addr = addresses.nextElement();
                if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                    return addr;
                }
            }
        }

        return localhost; // Fallback
    }

    /**
     * Получить обнаруженных пиров.
     */
    public Map<String, Peer> getDiscoveredPeers() {
        return new ConcurrentHashMap<>(discoveredPeers);
    }

    /**
     * Установить callback при обнаружении пира.
     */
    public void setOnPeerDiscovered(Consumer<Peer> callback) {
        this.onPeerDiscovered = callback;
    }

    /**
     * Получить наш peerId.
     */
    public String getPeerId() {
        return peerId;
    }

    /**
     * Доступен ли mDNS?
     */
    public boolean isAvailable() {
        return jmdns != null;
    }
}
