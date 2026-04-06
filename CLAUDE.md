# P2P Chat - Серверный чат для локальной сети

## Общее описание

P2P чат для локальной сети без центрального сервера. Позволяет обмениваться сообщениями и файлами между компьютерами в одной сети.

## Архитектура

### Технологический стек
- Java 21 + Spring Boot 3.2.0
- SQLite + Hibernate (база данных)
- WebSocket (real-time коммуникация)
- UDP Broadcast (discovery пиров)
- TCP (P2P сообщения и файлы)

### Структура проекта

```
src/main/java/com/chat/p2p/
├── P2PChatApplication.java      # Главный класс приложения
├── config/
│   ├── SQLiteDialect.java       # Hibernate dialect для SQLite
│   ├── WebSocketConfig.java     # WebSocket конфигурация
│   └── ChatWebSocketHandler.java # Обработчик WebSocket
├── controller/
│   ├── ChatController.java      # REST API контроллер
│   ├── FileController.java     # API для файлов
│   └── ClientController.java
├── service/
│   ├── P2PNetworkService.java  # Ядро P2P сети
│   ├── DatabaseService.java     # Работа с БД
│   └── ParallelFileTransferService.java # Параллельная передача файлов
├── model/
│   ├── P2PMessage.java         # Модель сообщения
│   ├── Peer.java                # Модель пира
│   └── DiscoveryMessage.java     # Сообщение для discovery
├── entity/
│   ├── MessageEntity.java       # Сущность сообщения в БД
│   └── FileEntity.java          # Сущность файла в БД
└── repository/
    ├── MessageRepository.java
    └── FileRepository.java
```

## Ключевые функции

### 1. P2P Networking
- **UDP Broadcast** (порт 45678) - рассылка "я здесь" каждые 3 секунды
- **TCP Server** (порт 9090) - приём входящих соединений
- **Peer Discovery** - автоматическое обнаружение пиров в сети
- **Connection Management** - управление TCP соединениями

### 2. Сообщения
- Real-time доставка через WebSocket
- Подтверждение доставки (msg type 2)
- Индикация "печатает..." (msg type 3, 4)
- ID сообщения для трекинга

### 3. Файловая передача
- **Параллельная передача** (файлы >10MB):
  - 8 параллельных TCP потоков
  - Direct ByteBuffer (off-heap память)
  - 2MB буферы
  - Producer-Consumer архитектура
- **Однопоточная передача** (файлы <10MB)
- Прогресс-бар в реальном времени

### 4. Безопасность
- TLS/SSL (HTTPS) - самоподписанный сертификат
- Аутентификация пиров через shared secret (msg type 5, 6)
- Rate limiting (60 запросов/мин)

### 5. REST API
- `GET /api/info` - информация о пире
- `GET /api/peers` - список пиров
- `POST /api/send` - отправить сообщение
- `GET /api/pending-messages` - новые сообщения
- `POST /api/send-file` - отправить файл
- `POST /api/auth/request` - запрос авторизации
- `POST /api/auth/respond` - ответ на авторизацию
- `GET /api/search` - поиск по сообщениям

### 6. Протокол сообщений (TCP)
| Type | Назначение |
|------|------------|
| 0 | Текстовое сообщение (JSON) |
| 1 | Чанк файла |
| 2 | Подтверждение доставки |
| 3 | "Печатает..." |
| 4 | Перестал печатать |
| 5 | Запрос авторизации |
| 6 | Ответ авторизации |

## Конфигурация

### application.yml
```yaml
server:
  port: 8089
  ssl:
    enabled: false  # Отключен для разработки

spring:
  datasource:
    url: jdbc:sqlite:chat.db
  jpa:
    hibernate:
      ddl-auto: update

chat:
  discovery:
    port: 45678
  p2p:
    port: 9090
  transfer:
    streams: 8          # Количество TCP потоков
    buffer-size: 2097152 # 2MB буфер
    min-file-size: 10485760 # 10MB порог
```

## Запуск

```bash
mvn spring-boot:run
```

Открыть: http://localhost:8089

## Frontend

Статический HTML в `src/main/resources/static/client.html`:
- Тёмная тема
- Список пиров
- Чат с сообщениями
- Индикация онлайн/офлайн
- Поиск по сообщениям
- Модальные окна для передачи файлов и авторизации

## Комментарии в коде

Код содержит подробные комментарии на русском языке с юмором, объясняющие логику работы.

## TODO/Улучшения

- [ ] Сквозное шифрование сообщений
- [ ] История сообщений для оффлайн пиров
- [ ] Возобновляемые передачи файлов при разрыве
- [ ] Поддержка групповых чатов
- [ ] voice/video calls
