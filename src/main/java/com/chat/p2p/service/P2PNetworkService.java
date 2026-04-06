package com.chat.p2p.service;

import com.chat.p2p.model.DiscoveryMessage;
import com.chat.p2p.model.Peer;
import com.chat.p2p.model.P2PMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Сердце приложения. Тут происходит магия P2P.
 * 
 * Качаем файлы, находим пиров, жмём руки (не человеческие, TCP).
 * Один поток принимает соединения, другой - UDPbroadcast- рассылает "я тут".
 * 
 * Важно: всё неблокирующее, иначе пользователь будет смотреть на кружок загрузки.
 */
@Service
public class P2PNetworkService {
    private static final Logger log = LoggerFactory.getLogger(P2PNetworkService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final long PEER_TIMEOUT = 10000;
    private static final int CHUNK_SIZE = 65536;
    private static final int TRANSFER_THREADS = 8;

    @Value("${chat.discovery.port:45678}")
    private int discoveryPort;

    @Value("${chat.p2p.port:9090}")
    private int p2pPort;

    @Value("${server.port:8089}")
    private int serverPort;

    private ServerSocket serverSocket;
    private DatagramSocket discoverySocket;
    private volatile boolean running = false; // Атомарная переменная - не для красоты, а для безопасности
    private String peerId; // Уникальный ID пира. UUID, обрезанный как пицца - по кускам
    private String peerName = "Unknown"; // Имя, которое пользователь сам себе придумал. Верим на слово
    private int wsPort = 0;

    private final Map<String, Peer> discoveredPeers = new ConcurrentHashMap<>(); // Кэш пиров. Кто онлайн - тут
    private final Map<String, Connection> connections = new ConcurrentHashMap<>(); // Активные TCP соединения
    private final Map<String, AuthRequest> pendingAuthRequests = new ConcurrentHashMap<>(); // Ожидающие авторизации
    private final Set<String> trustedPeers = ConcurrentHashMap.newKeySet(); // Белый список. Свой в доску
    private final ExecutorService executor = Executors.newCachedThreadPool(); // Потоковый пул общего назначения
    private final ExecutorService transferExecutor = Executors.newFixedThreadPool(TRANSFER_THREADS); // Пул для файлов - 8 потоков, не больше
    private final CopyOnWriteArrayList<NetworkListener> listeners = new CopyOnWriteArrayList<>(); // Слушатели сообщений
    private final CopyOnWriteArrayList<FileTransferListener> fileListeners = new CopyOnWriteArrayList<>(); // Слушатели файлов

    @PostConstruct
    public void init() {
        this.peerId = UUID.randomUUID().toString().substring(0, 8);
        this.wsPort = serverPort;
        cleanupTempFiles();
        startServer();
        startDiscovery();
        log.info("P2P Node started on port {} with peerId {}", p2pPort, peerId);
    }

    private void cleanupTempFiles() {
        try {
            Path tempDir = Paths.get("files");
            if (Files.exists(tempDir)) {
                try (var stream = Files.list(tempDir)) {
                    stream.filter(p -> p.getFileName().toString().startsWith("temp_"))
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException e) { log.warn("Failed to delete temp file: {}", p); }
                        });
                }
            }
        } catch (IOException e) {
            log.warn("Failed to cleanup temp files: {}", e.getMessage());
        }
    }

    public void addListener(NetworkListener listener) {
        listeners.add(listener);
    }

    public void addFileListener(FileTransferListener listener) {
        fileListeners.add(listener);
    }

    /**
     * Запускаем TCP сервер для приёма входящих соединений.
     * Это наш "приёмный покой" - кто угодно может постучаться.
     * 
     * KeepAlive иTcpNoDelay - чтобы не было задержек и соединение не отваливалось
     */
    private void startServer() {
        try {
            serverSocket = new ServerSocket(p2pPort);
            running = true;
            executor.submit(this::acceptConnections); // Одна задача - принимать, другие - обрабатывать
        } catch (IOException e) {
            log.error("Failed to start P2P server on port {}: {}", p2pPort, e.getMessage());
        }
    }

    /**
     * Бесконечный цикл приёма соединений.
     * Пока работаем и сокет открыт - принимаем. Иначе - спим.
     */
    private void acceptConnections() {
        while (running && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                client.setKeepAlive(true); // Держим соединение живым
                client.setTcpNoDelay(true); // Отключаем Nagle - сразу отправляем данные
                executor.submit(() -> handleClient(client)); // Обрабатываем в отдельном потоке
            } catch (IOException e) {
                if (running) log.warn("Accept error: {}", e.getMessage());
            }
        }
    }

    /**
     * Обработка входящего соединения.
     * Здесь происходит разбор протокола: читаем байт-тип, затем данные.
     * 
     * Типы сообщений:
     * 0 - текстовое сообщение
     * 1 - кусок файла
     * 2 - подтверждение доставки
     * 3 - "печатает..."
     * 4 - перестал печатать
     * 5 - запрос авторизации
     * 6 - ответ авторизации
     * 
     * @param client сокет от клиента
     */
    private void handleClient(Socket client) {
        String peerKey = client.getInetAddress().getHostAddress() + ":" + client.getPort();
        Connection conn;
        try {
            conn = new Connection(client);
            connections.put(peerKey, conn);
        } catch (IOException e) {
            try { client.close(); } catch (IOException ex) {}
            return;
        }
        
        DataInputStream dis;
        ConcurrentHashMap<String, FileReceivingState> receivingFiles = new ConcurrentHashMap<>();
        
        try {
            dis = new DataInputStream(client.getInputStream());
            
            while (!client.isClosed()) {
                int msgType = dis.read();
                if (msgType == -1) break;
                
                if (msgType == 0) {
                    String line = dis.readUTF();
                    P2PMessage msg = objectMapper.readValue(line, P2PMessage.class);
                    log.info("Message received: {} from {}", msg.getType(), msg.getSenderId());
                    for (NetworkListener listener : listeners) {
                        listener.onMessageReceived(msg);
                    }
                } else if (msgType == 1) {
                    String transferId = dis.readUTF();
                    String fileId = dis.readUTF();
                    String senderId = dis.readUTF();
                    String fileName = dis.readUTF();
                    long fileSize = dis.readLong();
                    long offset = dis.readLong();
                    int chunkSize = dis.readInt();
                    byte[] data = new byte[chunkSize];
                    dis.readFully(data);
                    boolean isLast = dis.readBoolean();

                    FileReceivingState state = receivingFiles.computeIfAbsent(transferId, 
                        id -> new FileReceivingState(transferId, fileId, senderId, fileName, fileSize));

                    if (state.receivedChunks.add(offset)) {
                        Path tempPath = Paths.get("files", "temp_" + transferId);
                        Files.createDirectories(tempPath.getParent());
                        RandomAccessFile raf = new RandomAccessFile(tempPath.toFile(), "rw");
                        raf.seek(offset);
                        raf.write(data);
                        raf.close();
                        state.received += chunkSize;
                    }
                    
                    for (FileTransferListener listener : fileListeners) {
                        listener.onProgress(transferId, state.received, state.fileSize);
                    }

                    if (isLast) {
                        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf(".")) : "";
                        Path finalPath = Paths.get("files", fileId + ext);
                        Path tempPath = Paths.get("files", "temp_" + transferId);
                        if (Files.exists(tempPath)) {
                            Files.move(tempPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                        receivingFiles.remove(transferId);
                        log.info("File received: {}", fileName);
                        for (FileTransferListener listener : fileListeners) {
                            listener.onComplete(transferId, fileName, state.fileSize);
                            listener.onFileComplete(transferId, fileId, fileName, state.fileSize, senderId);
                        }
                    }
                } else if (msgType == 2) {
                    String msgId = dis.readUTF();
                    for (NetworkListener listener : listeners) {
                        listener.onMessageDelivered(msgId);
                    }
                } else if (msgType == 3) {
                    String typingPeerId = dis.readUTF();
                    for (NetworkListener listener : listeners) {
                        listener.onTyping(typingPeerId);
                    }
                } else if (msgType == 4) {
                    String typingPeerId = dis.readUTF();
                    for (NetworkListener listener : listeners) {
                        listener.onTypingStopped(typingPeerId);
                    }
                } else if (msgType == 5) {
                    String token = dis.readUTF();
                    String secret = dis.readUTF();
                    String requestingPeerId = dis.readUTF();
                    log.info("Auth request from {} with token {}", requestingPeerId, token);
                    
                    pendingAuthRequests.put(requestingPeerId + ":" + token, new AuthRequest(requestingPeerId, token, secret));
                    for (NetworkListener listener : listeners) {
                        listener.onAuthRequest(requestingPeerId, token, secret);
                    }
                } else if (msgType == 6) {
                    String token = dis.readUTF();
                    boolean approved = dis.readBoolean();
                    String respondingPeerId = dis.readUTF();
                    log.info("Auth response from {}: {} for token {}", respondingPeerId, approved ? "approved" : "rejected", token);
                    
                    if (approved) {
                        trustedPeers.add(respondingPeerId);
                        Peer peer = discoveredPeers.get(respondingPeerId);
                        if (peer != null) {
                            peer.setTrusted(true);
                            peer.setAuthToken(token);
                        }
                    }
                    for (NetworkListener listener : listeners) {
                        listener.onAuthResponse(respondingPeerId, token, approved);
                    }
                }
            }
        } catch (EOFException | SocketException e) {
            log.info("Connection closed");
        } catch (Exception e) {
            log.error("Client handler error: {}", e.getMessage());
        } finally {
            for (FileReceivingState state : receivingFiles.values()) {
                try {
                    Files.deleteIfExists(Paths.get("files", "temp_" + state.transferId));
                } catch (IOException e) { log.warn("Failed to delete temp file: {}", e.getMessage()); }
            }
            connections.remove(peerKey);
            conn.close();
        }
    }

    private void startDiscovery() {
        Thread receiverThread = new Thread(this::receiveLoop);
        receiverThread.setDaemon(true);
        receiverThread.start();
    }

    /**
     * Рассылка широковещательного UDP сообщения.
     * Кричим на всю сеть: "Эй, я здесь! Запомните меня!"
     * 
     * Адрес 255.255.255.255 - это broadcast. Дойдёт до всех в подсети.
     * 
     * @Scheduled запускает это каждые 3 секунды. Не чаще - нечего засорять эфир.
     */
    @Scheduled(fixedRate = 3000)
    public void broadcast() {
        if (!running) return;

        try {
            DiscoveryMessage msg = new DiscoveryMessage(peerId, peerName, wsPort);
            String json = objectMapper.writeValueAsString(msg);

            InetAddress broadcastAddr = InetAddress.getByName("255.255.255.255");
            DatagramPacket packet = new DatagramPacket(json.getBytes(), json.length(), broadcastAddr, discoveryPort);

            if (discoverySocket == null || discoverySocket.isClosed()) {
                discoverySocket = new DatagramSocket();
                discoverySocket.setBroadcast(true);
                discoverySocket.setReuseAddress(true);
            }

            discoverySocket.send(packet);
        } catch (Exception e) {
            log.warn("Broadcast error: {}", e.getMessage());
        }
    }

    /**
     * Приём broadcast сообщений от других пиров.
     * Слушаем UDP сокет и если кто-то крикнул "я тут" - запоминаем.
     * 
     * Timeout 1 секунда - чтобы не висеть вечно в ожидании.
     * Если ничего не пришло - это нормально, значит никто не кричал.
     */
    private void receiveLoop() {
        try (DatagramSocket socket = new DatagramSocket(discoveryPort)) {
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
                    // Normal timeout
                }
            }
        } catch (Exception e) {
            log.error("Discovery socket error: {}", e.getMessage());
        }
    }

    /**
     * Уборка мусора. Удаляем пиров, которые не подавали признаков жизни 10 секунд.
     * Также закрываем мёртвые TCP соединения.
     * 
     * Вызывается каждую секунду. Чистоплотность - наше всё.
     */
    @Scheduled(fixedRate = 1000)
    public void cleanupPeers() {
        long now = System.currentTimeMillis();
        discoveredPeers.entrySet().removeIf(e -> now - e.getValue().getLastSeen() > PEER_TIMEOUT);
        
        connections.entrySet().removeIf(e -> {
            if (e.getValue().socket.isClosed()) return true;
            return false;
        });
    }

    /**
     * Отправка сообщения пиру.
     * 
     * Логика:
     * 1. Ищем пира в кэше
     * 2. Если соединения нет или оно мёртвое - создаём новое
     * 3. Пишем в сокет. Синхронизация нужна, чтобы несколько потоков не писали одновременно
     * 4. Если создали новое соединение - сразу начинаем его слушать
     * 
     * @param targetId ID получателя
     * @param message сообщение для отправки
     */
    public void sendMessage(String targetId, P2PMessage message) {
        Peer peer = discoveredPeers.get(targetId);
        if (peer == null) {
            log.warn("Peer not found: {}", targetId);
            return;
        }

        String key = peer.getAddress() + ":" + peer.getPort();
        
        try {
            Connection conn;
            boolean newConnection = false;
            if (!connections.containsKey(key) || connections.get(key).socket.isClosed()) {
                log.info("Creating new connection to {}", key);
                Socket socket = new Socket(peer.getAddress(), peer.getPort());
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                conn = new Connection(socket);
                connections.put(key, conn);
                newConnection = true;
            } else {
                conn = connections.get(key);
            }

            synchronized (conn.dos) {
                conn.dos.write(0);
                conn.dos.writeUTF(objectMapper.writeValueAsString(message));
                conn.dos.flush();
            }
            log.info("Message sent to {}", targetId);
            
            if (newConnection) {
                executor.submit(() -> handleClient(conn.socket));
            }
        } catch (Exception e) {
            log.error("Send error: {}", e.getMessage());
        }
    }

    public void sendDeliveryConfirmation(String targetId, String messageId) {
        Peer peer = discoveredPeers.get(targetId);
        if (peer == null) return;

        String key = peer.getAddress() + ":" + peer.getPort();
        
        try {
            Connection conn;
            if (!connections.containsKey(key) || connections.get(key).socket.isClosed()) {
                Socket socket = new Socket(peer.getAddress(), peer.getPort());
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                conn = new Connection(socket);
                connections.put(key, conn);
                executor.submit(() -> handleClient(conn.socket));
            } else {
                conn = connections.get(key);
            }

            synchronized (conn.dos) {
                conn.dos.write(2);
                conn.dos.writeUTF(messageId);
                conn.dos.flush();
            }
        } catch (Exception e) {
            log.warn("Delivery confirmation error: {}", e.getMessage());
        }
    }

    public void sendTyping(String targetId) {
        Peer peer = discoveredPeers.get(targetId);
        if (peer == null) return;

        String key = peer.getAddress() + ":" + peer.getPort();
        
        try {
            Connection conn;
            if (!connections.containsKey(key) || connections.get(key).socket.isClosed()) {
                Socket socket = new Socket(peer.getAddress(), peer.getPort());
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                conn = new Connection(socket);
                connections.put(key, conn);
                executor.submit(() -> handleClient(conn.socket));
            } else {
                conn = connections.get(key);
            }

            synchronized (conn.dos) {
                conn.dos.write(3);
                conn.dos.writeUTF(peerId);
                conn.dos.flush();
            }
        } catch (Exception e) {
            log.debug("Typing indicator error: {}", e.getMessage());
        }
    }

    public void sendTypingStopped(String targetId) {
        Peer peer = discoveredPeers.get(targetId);
        if (peer == null) return;

        String key = peer.getAddress() + ":" + peer.getPort();
        
        try {
            Connection conn;
            if (!connections.containsKey(key) || connections.get(key).socket.isClosed()) {
                Socket socket = new Socket(peer.getAddress(), peer.getPort());
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                conn = new Connection(socket);
                connections.put(key, conn);
                executor.submit(() -> handleClient(conn.socket));
            } else {
                conn = connections.get(key);
            }

            synchronized (conn.dos) {
                conn.dos.write(4);
                conn.dos.writeUTF(peerId);
                conn.dos.flush();
            }
        } catch (Exception e) {
            log.debug("Typing stopped error: {}", e.getMessage());
        }
    }

    @Autowired
    private ParallelFileTransferService parallelTransferService;

    public void sendFile(String targetId, String fileId, String fileName, long fileSize, String filePath) {
        Peer peer = discoveredPeers.get(targetId);
        if (peer == null) {
            log.warn("Peer not found: {}", targetId);
            return;
        }

        Path fullPath = Paths.get("files", filePath);
        if (!Files.exists(fullPath)) {
            log.error("File not found: {}", fullPath);
            return;
        }

        String transferId = UUID.randomUUID().toString();
        log.info("Starting file transfer: {} ({} bytes)", filePath, fileSize);

        // Для больших файлов используем параллельную передачу (>10MB)
        if (fileSize >= 10 * 1024 * 1024) {
            parallelTransferService.transferFile(
                peer.getAddress(),
                peer.getPort(),
                fullPath.toString(),
                transferId,
                fileId,
                peerId,
                fileName,
                fileSize,
                new ParallelFileTransferService.ProgressCallback() {
                    @Override
                    public void onProgress(long transferred, long total) {
                        for (FileTransferListener listener : fileListeners) {
                            listener.onProgress(transferId, transferred, total);
                        }
                    }

                    @Override
                    public void onComplete(long total) {
                        for (FileTransferListener listener : fileListeners) {
                            listener.onComplete(transferId, fileName, total);
                        }
                        log.info("PARALLEL file transfer complete: {} ({} bytes)", fileName, total);
                    }

                    @Override
                    public void onError(String error) {
                        log.error("Parallel transfer failed: {}", error);
                    }
                }
            );
            return;
        }

        // Для маленьких файлов - старая однопоточная логика
        executor.submit(() -> {
            RandomAccessFile raf = null;
            Socket socket = null;
            try {
                String myPeerId = this.peerId;
                socket = new Socket(peer.getAddress(), peer.getPort());
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());

                raf = new RandomAccessFile(fullPath.toFile(), "r");
                long offset = 0;
                int numChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);
                
                for (int i = 0; i < numChunks; i++) {
                    dos.write(1);
                    dos.writeUTF(transferId);
                    dos.writeUTF(fileId);
                    dos.writeUTF(myPeerId);
                    dos.writeUTF(fileName);
                    dos.writeLong(fileSize);
                    dos.writeLong(offset);

                    int size = (int) Math.min(CHUNK_SIZE, fileSize - offset);
                    byte[] data = new byte[size];
                    raf.readFully(data);

                    dos.writeInt(size);
                    dos.write(data);
                    dos.writeBoolean(i == numChunks - 1);
                    dos.flush();

                    offset += size;
                    
                    for (FileTransferListener listener : fileListeners) {
                        listener.onProgress(transferId, offset, fileSize);
                    }
                }

                log.info("File transfer complete");
                
            } catch (Exception e) {
                log.error("File transfer error: {}", e.getMessage());
            } finally {
                if (raf != null) try { raf.close(); } catch (IOException e) {}
                if (socket != null) try { socket.close(); } catch (IOException e) {}
            }
        });
    }

    public void setPeerName(String name) {
        this.peerName = name;
    }

    public String getPeerId() {
        return peerId;
    }

    public int getP2pPort() {
        return p2pPort;
    }

    public Map<String, Peer> getPeers() {
        return new ConcurrentHashMap<>(discoveredPeers);
    }

    public Map<String, Peer> getTrustedPeers() {
        Map<String, Peer> trusted = new ConcurrentHashMap<>();
        discoveredPeers.forEach((id, peer) -> {
            if (peer.isTrusted()) {
                trusted.put(id, peer);
            }
        });
        return trusted;
    }

    public void requestAuth(String targetPeerId, String token, String secret) {
        Peer peer = discoveredPeers.get(targetPeerId);
        if (peer == null) {
            log.warn("Peer not found for auth request: {}", targetPeerId);
            return;
        }

        String key = peer.getAddress() + ":" + peer.getPort();
        
        try {
            Connection conn;
            if (!connections.containsKey(key) || connections.get(key).socket.isClosed()) {
                Socket socket = new Socket(peer.getAddress(), peer.getPort());
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                conn = new Connection(socket);
                connections.put(key, conn);
                executor.submit(() -> handleClient(conn.socket));
            } else {
                conn = connections.get(key);
            }

            synchronized (conn.dos) {
                conn.dos.write(5);
                conn.dos.writeUTF(token);
                conn.dos.writeUTF(secret);
                conn.dos.writeUTF(peerId);
                conn.dos.flush();
            }
            log.info("Auth request sent to {}", targetPeerId);
        } catch (Exception e) {
            log.error("Auth request error: {}", e.getMessage());
        }
    }

    public void respondAuth(String peerId, String token, boolean approved) {
        Peer peer = discoveredPeers.get(peerId);
        if (peer == null) {
            log.warn("Peer not found for auth response: {}", peerId);
            return;
        }

        String key = peer.getAddress() + ":" + peer.getPort();
        
        try {
            Connection conn;
            if (!connections.containsKey(key) || connections.get(key).socket.isClosed()) {
                Socket socket = new Socket(peer.getAddress(), peer.getPort());
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                conn = new Connection(socket);
                connections.put(key, conn);
                executor.submit(() -> handleClient(conn.socket));
            } else {
                conn = connections.get(key);
            }

            synchronized (conn.dos) {
                conn.dos.write(6);
                conn.dos.writeUTF(token);
                conn.dos.writeBoolean(approved);
                conn.dos.writeUTF(peerId);
                conn.dos.flush();
            }
            
            if (approved) {
                trustedPeers.add(peerId);
                peer.setTrusted(true);
                peer.setAuthToken(token);
            }
            
            log.info("Auth response sent to {}: {}", peerId, approved ? "approved" : "rejected");
        } catch (Exception e) {
            log.error("Auth response error: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        connections.values().forEach(Connection::close);
        try { 
            if (serverSocket != null) serverSocket.close(); 
        } catch (IOException e) {}
        try { 
            if (discoverySocket != null) discoverySocket.close(); 
        } catch (Exception e) {}
        executor.shutdown();
        transferExecutor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
            transferExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (parallelTransferService != null) {
            parallelTransferService.shutdown();
        }
    }

    public interface NetworkListener {
        void onMessageReceived(P2PMessage message);
        default void onMessageDelivered(String messageId) {}
        default void onTyping(String peerId) {}
        default void onTypingStopped(String peerId) {}
        default void onAuthRequest(String peerId, String token, String secret) {}
        default void onAuthResponse(String peerId, String token, boolean approved) {}
    }

    public interface FileTransferListener {
        void onProgress(String transferId, long received, long total);
        void onComplete(String transferId, String fileName, long fileSize);
        void onFileComplete(String transferId, String fileId, String fileName, long fileSize, String senderId);
    }

    private class Connection {
        Socket socket;
        DataOutputStream dos;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.dos = new DataOutputStream(socket.getOutputStream());
        }

        void close() {
            try {
                socket.close();
            } catch (IOException e) {}
        }
    }

    private static class FileReceivingState {
        final String transferId;
        final String fileId;
        final String senderId;
        final String fileName;
        final long fileSize;
        final Set<Long> receivedChunks = new CopyOnWriteArraySet<>();
        long received;

        FileReceivingState(String transferId, String fileId, String senderId, String fileName, long fileSize) {
            this.transferId = transferId;
            this.fileId = fileId;
            this.senderId = senderId;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.received = 0;
        }
    }

    private static class AuthRequest {
        final String peerId;
        final String token;
        final String secret;
        final long timestamp;

        AuthRequest(String peerId, String token, String secret) {
            this.peerId = peerId;
            this.token = token;
            this.secret = secret;
            this.timestamp = System.currentTimeMillis();
        }
    }
}
