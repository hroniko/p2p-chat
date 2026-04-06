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
├── service/
│   ├── P2PNetworkService.java  # Ядро P2P сети
│   ├── DatabaseService.java     # Работа с БД
│   ├── ParallelFileTransferService.java # Параллельная передача файлов
│   ├── EncryptionService.java   # E2E шифрование
│   ├── OfflineMessageService.java # Оффлайн сообщения
│   ├── GroupService.java       # Групповые чаты
│   └── CallService.java        # Voice/Video звонки
├── model/
│   ├── P2PMessage.java         # Модель сообщения
│   ├── Peer.java                # Модель пира
│   ├── Group.java               # Модель группы
│   └── DiscoveryMessage.java    # Сообщение для discovery
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

### 2. Сообщения
- Real-time доставка через WebSocket
- Подтверждение доставки (msg type 2)
- Индикация "печатает..." (msg type 3, 4)
- **E2E шифрование** - AES-256-GCM для trusted пиров
- **Оффлайн сообщения** - сохраняются и отправляются при возвращении пира

### 3. Файловая передача
- **Параллельная передача** (файлы >10MB):
  - 8 параллельных TCP потоков
  - Direct ByteBuffer (off-heap память)
  - 2MB буферы
  - Producer-Consumer архитектура
- **Возобновление** - при разрыве можно продолжить с места остановки

### 4. Безопасность
- TLS/SSL (HTTPS) - самоподписанный сертификат
- Аутентификация пиров через shared secret (msg type 5, 6)
- Rate limiting (60 запросов/мин)
- E2E шифрование сообщений

### 5. Групповые чаты
- Создание групп
- Добавление/удаление участников
- P2P ретрансляция сообщений

### 6. Voice/Video звонки
- WebRTC совместимая сигнализация
- SDP и ICE candidates через P2P сеть

### 7. REST API
- `GET /api/info` - информация о пире
- `GET /api/peers` - список пиров
- `POST /api/send` - отправить сообщение
- `GET /api/pending-messages` - новые сообщения
- `POST /api/send-file` - отправить файл
- `POST /api/auth/request` - запрос авторизации
- `POST /api/auth/respond` - ответ на авторизацию
- `GET /api/search` - поиск по сообщениям
- `POST /api/groups/create` - создать группу
- `GET /api/groups` - список групп

### 8. Протокол сообщений (TCP)
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

spring:
  datasource:
    url: jdbc:sqlite:chat.db

chat:
  discovery:
    port: 45678
  p2p:
    port: 9090
  transfer:
    streams: 8
    buffer-size: 2097152
    min-file-size: 10485760
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

- [x] Сквозное шифрование сообщений
- [x] История сообщений для оффлайн пиров
- [x] Возобновляемые передачи файлов при разрыве
- [x] Поддержка групповых чатов
- [x] Voice/video calls

## Версия

Проект постоянно развивается. Последнее обновление: 2026-04-07