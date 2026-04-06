package com.chat.p2p.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Сервис параллельной передачи файлов с поддержкой возобновления.
 */
@Service
public class ParallelFileTransferService {
    private static final Logger log = LoggerFactory.getLogger(ParallelFileTransferService.class);

    @Value("${chat.transfer.streams:8}")
    private int numStreams;

    @Value("${chat.transfer.buffer-size:1048576}")
    private int bufferSize;

    @Value("${chat.transfer.min-file-size:10485760}")
    private long minFileSizeForParallel;

    private final ConcurrentHashMap<String, TransferState> activeTransfers = new ConcurrentHashMap<>();
    private ExecutorService executor;
    private DirectBufferPool bufferPool;

    public ParallelFileTransferService() {
    }

    @PostConstruct
    public void init() {
        this.executor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() * 2 + 8,
            r -> {
                Thread t = new Thread(r);
                t.setPriority(Thread.MAX_PRIORITY);
                t.setDaemon(true);
                return t;
            }
        );
        this.bufferPool = new DirectBufferPool(numStreams * 8, bufferSize);
        log.info("ParallelFileTransferService initialized: streams={}, buffer={}", numStreams, bufferSize);
    }

    public static class TransferState {
        public String transferId;
        public long transferred;
        public long lastUpdate;
        public AtomicBoolean running = new AtomicBoolean(true);
        
        public TransferState(String transferId) {
            this.transferId = transferId;
            this.transferred = 0;
            this.lastUpdate = System.currentTimeMillis();
        }
    }

    public void transferFile(
            String peerAddress,
            int peerPort,
            String filePath,
            String transferId,
            String fileId,
            String senderId,
            String fileName,
            long fileSize,
            ProgressCallback callback
    ) {
        if (fileSize < minFileSizeForParallel) {
            return;
        }

        TransferState state = activeTransfers.computeIfAbsent(transferId, TransferState::new);
        long startOffset = state.transferred;
        
        log.info("Starting/resuming transfer {} from {} of {} bytes", transferId, startOffset, fileSize);

        AtomicLong transferred = new AtomicLong(startOffset);
        long remaining = fileSize - startOffset;

        try {
            SocketChannel[] channels = new SocketChannel[numStreams];
            
            for (int i = 0; i < numStreams; i++) {
                SocketChannel sc = SocketChannel.open();
                sc.connect(new InetSocketAddress(peerAddress, peerPort));
                sc.configureBlocking(true);
                
                Socket socket = sc.socket();
                socket.setSendBufferSize(bufferSize * 2);
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.setPerformancePreferences(1, 1, 0);
                
                channels[i] = sc;
            }

            long partitionSize = remaining / numStreams;
            
            for (int i = 0; i < numStreams; i++) {
                final int streamId = i;
                final long streamStart = startOffset + (i * partitionSize);
                final long streamEnd = (i == numStreams - 1) ? fileSize : streamStart + partitionSize;

                executor.submit(() -> {
                    try (RandomAccessFile raf = new RandomAccessFile(filePath, "r");
                         FileChannel fc = raf.getChannel()) {
                        
                        fc.position(streamStart);
                        long offset = streamStart;
                        
                        while (offset < streamEnd && state.running.get()) {
                            ByteBuffer buffer = bufferPool.take();
                            buffer.clear();
                            
                            long toRead = Math.min(buffer.capacity(), streamEnd - offset);
                            int read = fc.read(buffer);
                            
                            if (read <= 0) break;
                            
                            buffer.flip();
                            
                            SocketChannel ch = channels[streamId];
                            while (buffer.hasRemaining() && state.running.get()) {
                                ch.write(buffer);
                            }
                            
                            offset += read;
                            long current = transferred.addAndGet(read);
                            state.transferred = current;
                            state.lastUpdate = System.currentTimeMillis();
                            
                            bufferPool.put(buffer);
                            callback.onProgress(current, fileSize);
                        }
                    } catch (Exception e) {
                        log.error("Stream {} error: {}", streamId, e.getMessage());
                    }
                });
            }

            while (state.running.get() && transferred.get() < fileSize) {
                Thread.sleep(100);
                if (System.currentTimeMillis() - state.lastUpdate > 30000) {
                    log.warn("Transfer paused at {} bytes", transferred.get());
                    break;
                }
            }
            
            for (SocketChannel ch : channels) {
                if (ch != null && ch.isOpen()) try { ch.close(); } catch (IOException e) {}
            }
            
            activeTransfers.remove(transferId);
            
            if (transferred.get() >= fileSize) {
                callback.onComplete(fileSize);
                log.info("Transfer complete: {}", fileName);
            }
            
        } catch (Exception e) {
            log.error("Transfer failed: {}", e.getMessage());
            callback.onError(e.getMessage());
        }
    }

    public TransferState getTransferState(String transferId) {
        return activeTransfers.get(transferId);
    }

    public void cancelTransfer(String transferId) {
        TransferState state = activeTransfers.get(transferId);
        if (state != null) state.running.set(false);
    }

    public void shutdown() {
        activeTransfers.values().forEach(t -> t.running.set(false));
        executor.shutdown();
        if (bufferPool != null) bufferPool.close();
    }

    public interface ProgressCallback {
        void onProgress(long transferred, long total);
        default void onComplete(long total) {}
        default void onError(String error) {}
    }

    private static class DirectBufferPool {
        private final int bufferSize;
        private final LinkedBlockingQueue<ByteBuffer> pool;

        DirectBufferPool(int numBuffers, int bufferSize) {
            this.bufferSize = bufferSize;
            this.pool = new LinkedBlockingQueue<>(numBuffers);
            for (int i = 0; i < numBuffers; i++) {
                pool.offer(ByteBuffer.allocateDirect(bufferSize));
            }
        }

        synchronized ByteBuffer take() {
            ByteBuffer buffer = pool.poll();
            if (buffer == null) return ByteBuffer.allocateDirect(bufferSize);
            buffer.clear();
            return buffer;
        }

        synchronized void put(ByteBuffer buffer) {
            buffer.clear();
            pool.offer(buffer);
        }

        void close() {
            pool.clear();
        }
    }
}