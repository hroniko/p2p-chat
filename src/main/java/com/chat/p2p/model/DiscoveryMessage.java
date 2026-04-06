package com.chat.p2p.model;

public class DiscoveryMessage {
    private String type = "ANNOUNCE";
    private String peerId;
    private String peerName;
    private int p2pPort;

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
