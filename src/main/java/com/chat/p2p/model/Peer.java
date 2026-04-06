package com.chat.p2p.model;

public class Peer {
    private String id;
    private String name;
    private String address;
    private int port;
    private long lastSeen;
    private boolean connected;

    public Peer() {}

    public Peer(String id, String name, String address, int port) {
        this.id = id;
        this.name = name;
        this.address = address;
        this.port = port;
        this.lastSeen = System.currentTimeMillis();
        this.connected = false;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public long getLastSeen() { return lastSeen; }
    public void setLastSeen(long lastSeen) { this.lastSeen = lastSeen; }

    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }

    public String getWsUrl() {
        return "ws://" + address + ":" + port + "/ws";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Peer peer = (Peer) o;
        return id != null && id.equals(peer.id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
}
