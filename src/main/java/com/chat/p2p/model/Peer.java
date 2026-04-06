package com.chat.p2p.model;

/**
 * Модель пира (участника сети).
 * 
 * Хранит информацию о другом участнике P2P сети:
 * - ID (уникальный, как отпечаток пальца)
 * - Имя (придуманное пользователем, как никнейм)
 * - IP адрес (чтобы подключиться)
 * - Порт (P2P порт для TCP соединений)
 * - Статус доверия (trusted = свой, проверенный)
 * - lastSeen (когда последний раз подавал признаки жизни)
 * 
 * Equals и hashCode переопределены только по ID -
 * два пира с одним ID это один и тот же пир.
 */
public class Peer {
    private String id; // UUID (обрезанный)
    private String name; // Имя из интерфейса
    private String address; // IP адрес
    private int port; // Порт P2P сервера
    private long lastSeen; // Timestamp последнего "крика" в сеть
    private boolean connected; // Есть ли активное TCP соединение
    private boolean trusted; // Доверенный пир (прошёл авторизацию)
    private String authToken; // Токен авторизации

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

    public boolean isTrusted() { return trusted; }
    public void setTrusted(boolean trusted) { this.trusted = trusted; }

    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }

    public String getWsUrl() {
        return "wss://" + address + ":" + port + "/ws";
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
