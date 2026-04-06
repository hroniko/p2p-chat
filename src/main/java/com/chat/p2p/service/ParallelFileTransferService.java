package com.chat.p2p.service;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Сервис параллельной передачи файлов.
 * 
 * Вдохновлён FDT (Fast Data Transfer):
 * - Несколько параллельных TCP потоков
 * - Producer-Consumer с BlockingQueue
 * - Буферный пул для эффективного использования памяти
 * - producer читает с диска -> queue -> consumer пишет в сеть
 * 
 * Преимущества:
 * - Полное использование пропускной способности сети
 * - Эффективная работа с большими файлами
 * - Контроль нагрузки на систему
 */
@Service
public class ParallelFileTransferService {
    private static final Logger log = LoggerFactory.getLogger(ParallelFileTransferService.class);

    /** Количество параллельных TCP потоков (по умолчанию = CPU ядра) */
    @Value("${chat.transfer.streams:8}")
    private int numStreams;

    /** Размер буфера в байтах (1MB для сети, побольше чем 64KB) */
    @Value("${chat.transfer.buffer-size:1048576}")
    private int bufferSize;

    /** Размер очереди между producer и consumer */
    @Value("${chat.transfer.queue-size:32}")
    private int queueSize;

    /** Максимальный размер файла для параллельной передачи (100MB) */
    @Value("${chat.transfer.min-file-size:104857600}")
    private long minFileSizeForParallel;

    private final ExecutorService diskReadExecutor;
    private final ExecutorService networkWriteExecutor;
    private final BlockingQueue<Chunk> chunkQueue;
    private final DirectBufferPool bufferPool;

    public ParallelFileTransferService() {
        int processors = Runtime.getRuntime().availableProcessors();
        
        // Thread pool для чтения с диска - 2 потока на ядро
        this.diskReadExecutor = Executors.newFixedThreadPool(
            processors * 2,
            r -> {
                Thread t = new Thread(r, "DiskReader-" + r.hashCode());
                t.setPriority(Thread.MAX_PRIORITY - 1);
                return t;
            }
        );

        // Thread pool для записи в сеть - по потоку на TCP соединение
        this.networkWriteExecutor = Executors.newFixedThreadPool(
            numStreams,
            r -> {
                Thread t = new Thread(r, "NetworkWriter-" + r.hashCode());
                t.setPriority(Thread.MAX_PRIORITY - 2);
                return t;
            }
        );

        // Очередь между producer и consumer
        this.chunkQueue = new ArrayBlockingQueue<>(queueSize);
        
        // Буферный пул для direct ByteBuffer
        this.bufferPool = new DirectBufferPool(numStreams * 4, bufferSize);
    }

    /**
     * Параллельная передача файла несколькими потоками.
     * 
     * Алгоритм:
     * 1. Разбиваем файл на partition (по количеству потоков)
     * 2. Каждый поток читает свою часть с диска
     * 3. Читаемые данные кладутся в BlockingQueue
     * 4. Consumer читает из очереди и пишет в сеть
     * 
     * @param peerAddress IP получателя
     * @param peerPort порт получателя
     * @param filePath путь к файлу
     * @param transferId ID передачи
     * @param fileId ID файла
     * @param fileName имя файла
     * @param totalSize общий размер файла
     * @param progressCallback колбэк для прогресса
     */
    public void transferFile(
            String peerAddress,
            int peerPort,
            String filePath,
            String transferId,
            String fileId,
            String fileName,
            long totalSize,
            ProgressCallback progressCallback
    ) {
        // Для маленьких файлов - используем старую логику
        if (totalSize < minFileSizeForParallel) {
            log.info("File {} too small ({} bytes), using single-stream transfer", fileName, totalSize);
            return;
        }

        log.info("Starting parallel transfer: {} streams, {} bytes", numStreams, totalSize);

        // Вычисляем размер partition для каждого потока
        long partitionSize = totalSize / numStreams;
        
        // Atomic счётчик для отслеживания прогресса
        AtomicLong transferred = new AtomicLong(0);
        AtomicBoolean running = new AtomicBoolean(true);

        // Producer: читаем файл в несколько потоков
        for (int i = 0; i < numStreams; i++) {
            final int streamId = i;
            final long startOffset = i * partitionSize;
            final long endOffset = (i == numStreams - 1) ? totalSize : (i + 1) * partitionSize;

            diskReadExecutor.submit(() -> {
                try {
                    readFilePartition(filePath, startOffset, endOffset, streamId, transferred, totalSize, progressCallback);
                } catch (Exception e) {
                    log.error("Error reading partition {}: {}", streamId, e.getMessage());
                }
            });
        }

        // Consumer: пишем в несколько TCP потоков
        networkWriteExecutor.submit(() -> {
            try {
                writeToParallelSockets(peerAddress, peerPort, transferId, fileId, fileName, totalSize, transferred, running, progressCallback);
            } catch (Exception e) {
                log.error("Error writing to network: {}", e.getMessage());
            }
        });
    }

    /**
     * Producer: читает partition файла и кладёт чанки в очередь
     */
    private void readFilePartition(
            String filePath,
            long startOffset,
            long endOffset,
            int streamId,
            AtomicLong transferred,
            long totalSize,
            ProgressCallback callback
    ) {
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "r");
             FileChannel channel = raf.getChannel()) {

            long offset = startOffset;
            
            while (offset < endOffset && !Thread.currentThread().isInterrupted()) {
                // Берём буфер из пула
                ByteBuffer buffer = bufferPool.take();
                
                // Читаем данные
                channel.position(offset);
                int bytesRead = channel.read(buffer);
                
                if (bytesRead <= 0) {
                    bufferPool.put(buffer);
                    break;
                }

                // Подготавливаем буфер для записи
                buffer.flip();
                
                // Кладём в очередь (блокирующая операция)
                Chunk chunk = new Chunk(streamId, offset, buffer);
                chunkQueue.offer(chunk, 5, TimeUnit.SECONDS);
                
                offset += bytesRead;
                transferred.addAndGet(bytesRead);
                
                // Обновляем прогресс
                long current = transferred.get();
                callback.onProgress(current, totalSize);
            }
        } catch (Exception e) {
            log.error("Error reading file partition {}: {}", streamId, e.getMessage());
        }
    }

    /**
     * Consumer: читает из очереди и пишет в параллельные сокеты
     */
    private void writeToParallelSockets(
            String peerAddress,
            int peerPort,
            String transferId,
            String fileId,
            String fileName,
            long totalSize,
            AtomicLong transferred,
            AtomicBoolean running,
            ProgressCallback callback
    ) {
        SocketChannel[] channels = new SocketChannel[numStreams];

        try {
            // Открываем несколько TCP соединений
            for (int i = 0; i < numStreams; i++) {
                channels[i] = SocketChannel.open();
                channels[i].connect(new java.net.InetSocketAddress(peerAddress, peerPort));
                
                Socket socket = channels[i].socket();
                socket.setSendBufferSize(bufferSize);
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
            }

            // Читаем из очереди и пишем в соответствующий сокет
            long lastProgressUpdate = 0;
            
            while (running.get() && transferred.get() < totalSize) {
                Chunk chunk = chunkQueue.poll(1, TimeUnit.SECONDS);
                
                if (chunk != null) {
                    // Пишем в нужный сокет
                    SocketChannel channel = channels[chunk.streamId];
                    
                    while (chunk.buffer.hasRemaining()) {
                        channel.write(chunk.buffer);
                    }
                    
                    // Возвращаем буфер в пул
                    bufferPool.put(chunk.buffer);
                }
            }

            // Дожидаемся завершения всех данных
            for (SocketChannel ch : channels) {
                if (ch != null) {
                    ch.close();
                }
            }
            
            callback.onComplete(totalSize);
            log.info("Parallel transfer complete: {}", fileName);

        } catch (Exception e) {
            log.error("Error in parallel write: {}", e.getMessage());
        }
    }

    /**
     * Остановка сервиса
     */
    public void shutdown() {
        diskReadExecutor.shutdown();
        networkWriteExecutor.shutdown();
        bufferPool.close();
    }

    /**
     * Интерфейс для колбэка прогресса
     */
    public interface ProgressCallback {
        void onProgress(long transferred, long total);
        default void onComplete(long total) {}
    }

    /**
     * Чанк данных для передачи
     */
    private static class Chunk {
        final int streamId;
        final long offset;
        final ByteBuffer buffer;

        Chunk(int streamId, long offset, ByteBuffer buffer) {
            this.streamId = streamId;
            this.offset = offset;
            this.buffer = buffer;
        }
    }

    /**
     * Пул Direct ByteBuffer - эффективная работа с памятью.
     * 
     * DirectByteBuffer использует нативную память (off-heap),
     * что быстрее для сетевых операций чем heap буферы.
     */
    private static class DirectBufferPool {
        private final int bufferSize;
        private final LinkedBlockingQueue<ByteBuffer> pool;
        private final int maxSize;

        DirectBufferPool(int numBuffers, int bufferSize) {
            this.bufferSize = bufferSize;
            this.maxSize = numBuffers;
            this.pool = new LinkedBlockingQueue<>(numBuffers);

            // Предварительно создаём буферы
            for (int i = 0; i < numBuffers; i++) {
                pool.offer(ByteBuffer.allocateDirect(bufferSize));
            }
        }

        /**
         * Берём буфер из пула. Если пул пуст - создаём новый.
         */
        ByteBuffer take() {
            ByteBuffer buffer = pool.poll();
            if (buffer == null) {
                return ByteBuffer.allocateDirect(bufferSize);
            }
            buffer.clear();
            return buffer;
        }

        /**
         * Возвращаем буфер в пул. Если пул полный - буфер просто теряется (GC).
         */
        void put(ByteBuffer buffer) {
            buffer.clear();
            if (!pool.offer(buffer)) {
                // Пул полный, пусть GC соберёт
            }
        }

        void close() {
            pool.clear();
        }
    }
}
