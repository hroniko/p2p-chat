package com.chat.p2p.service;

import com.chat.p2p.model.DiscoveryMessage;
import com.chat.p2p.model.Peer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UdpDiscoveryService {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final long PEER_TIMEOUT = 10000;

    @Value("${chat.discovery.port:45678}")
    private int discoveryPort;

    private DatagramSocket socket;
    private boolean running = false;
    private String peerId;
    private String peerName = "Unknown";
    private int wsPort = 0;

    private final Map<String, Peer> discoveredPeers = new ConcurrentHashMap<>();

    @Value("${server.port:8089}")
    private int serverPort;

    @PostConstruct
    public void init() {
        this.peerId = UUID.randomUUID().toString().substring(0, 8);
        this.wsPort = serverPort;
        startDiscovery();
    }

    public void setPeerName(String name) {
        this.peerName = name;
    }

    public String getPeerId() {
        return peerId;
    }

    public int getWsPort() {
        return wsPort;
    }

    public Map<String, Peer> getPeers() {
        return new ConcurrentHashMap<>(discoveredPeers);
    }

    private void startDiscovery() {
        running = true;
        Thread receiverThread = new Thread(this::receiveLoop);
        receiverThread.setDaemon(true);
        receiverThread.start();

        System.out.println("[" + LocalDateTime.now().format(formatter) + "] P2P Discovery started on UDP port " + discoveryPort);
        System.out.println("[" + LocalDateTime.now().format(formatter) + "] WebSocket server will use port " + wsPort);
    }

    @Scheduled(fixedRate = 3000)
    public void broadcast() {
        if (!running) return;

        try {
            DiscoveryMessage msg = new DiscoveryMessage(peerId, peerName, wsPort);
            String json = objectMapper.writeValueAsString(msg);

            InetAddress broadcastAddr = InetAddress.getByName("255.255.255.255");
            DatagramPacket packet = new DatagramPacket(
                json.getBytes(),
                json.length(),
                broadcastAddr,
                discoveryPort
            );

            if (socket == null || socket.isClosed()) {
                socket = new DatagramSocket();
                socket.setBroadcast(true);
                socket.setReuseAddress(true);
            }

            socket.send(packet);
        } catch (Exception e) {
            // Ignore broadcast errors
        }
    }

    private void receiveLoop() {
        try {
            socket = new DatagramSocket(discoveryPort);
            socket.setBroadcast(true);
            socket.setReuseAddress(true);
            socket.setSoTimeout(1000);

            byte[] buffer = new byte[1024];

            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    String json = new String(packet.getData(), 0, packet.getLength());
                    DiscoveryMessage msg = objectMapper.readValue(json, DiscoveryMessage.class);

                    if (msg.getPeerId() != null && !msg.getPeerId().equals(peerId)) {
                        String address = packet.getAddress().getHostAddress();
                        Peer peer = discoveredPeers.computeIfAbsent(msg.getPeerId(), 
                            id -> new Peer(id, msg.getPeerName(), address, msg.getWsPort()));
                        peer.setName(msg.getPeerName());
                        peer.setAddress(address);
                        peer.setPort(msg.getWsPort());
                        peer.setLastSeen(System.currentTimeMillis());
                    }
                } catch (SocketTimeoutException e) {
                    // Normal timeout, continue
                }
            }
        } catch (Exception e) {
            // Socket issues
        }
    }

    @Scheduled(fixedRate = 1000)
    public void cleanupStalePeers() {
        long now = System.currentTimeMillis();
        discoveredPeers.entrySet().removeIf(entry -> now - entry.getValue().getLastSeen() > PEER_TIMEOUT);
        discoveredPeers.values().forEach(p -> p.setLastSeen(System.currentTimeMillis()));
    }

    public void removePeer(String peerId) {
        discoveredPeers.remove(peerId);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}
