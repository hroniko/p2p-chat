package com.chat.p2p.model;

/**
 * Сообщение для обнаружения пиров в сети.
 * 
 * Отправляется по UDP broadcast каждые 3 секунды.
 * Получатели видят IP отправителя и узнают о новом пире.
 * 
 * Простой JSON: { "type": "ANNOUNCE", "peerId": "...", "peerName": "...", "p2pPort": ... }
 * 
 * Зачем это нужно? Потому что в P2P нет центрального сервера.
 * Каждый сам кричит о себе. Услышал - запомнил.
 */
public class DiscoveryMessage {
    private String type = "ANNOUNCE"; // Всегда ANNOUNCE - мы объявляемся
    private String peerId; // Уникальный ID - UUID обрезанный
    private String peerName; // Имя, которое пользователь ввёл
    private int p2pPort; // Порт P2P сервера

    public DiscoveryMessage() {}

    public DiscoveryMessage(String peerId, String peerName, int p2pPort) {
        this.peerId = peerId;
        this.peerName = peerName;
        this.p2pPort = p2pPort;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getPeerId() { return peerId; }
    public void setPeerId(String peerId) { this.peerId = peerId; }

    public String getPeerName() { return peerName; }
    public void setPeerName(String peerName) { this.peerName = peerName; }

    public int getP2pPort() { return p2pPort; }
    public void setP2pPort(int p2pPort) { this.p2pPort = p2pPort; }
    
    public int getWsPort() { return p2pPort; }
    public void setWsPort(int port) { this.p2pPort = port; }
}
