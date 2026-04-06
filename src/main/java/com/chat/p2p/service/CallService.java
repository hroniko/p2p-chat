package com.chat.p2p.service;

import com.chat.p2p.model.P2PMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сервис звонков (Voice/Video).
 * 
 * Использует WebRTC:
 * - SDP (Session Description Protocol) - описание медиа
 * - ICE candidates - candidates для NAT traversal
 * - P2P через существующие TCP соединения
 * 
 * Сигналинг идёт через P2P сеть, медиа - напрямую между браузерами.
 */
@Service
public class CallService {
    private static final Logger log = LoggerFactory.getLogger(CallService.class);

    /** Активные звонки: callId -> CallState */
    private final Map<String, CallState> activeCalls = new ConcurrentHashMap<>();

    /** Ожидающие входящие звонки: peerId -> CallState */
    private final Map<String, CallState> incomingCalls = new ConcurrentHashMap<>();

    private CallListener listener;

    /**
     * Состояние звонка.
     */
    public static class CallState {
        public String callId;
        public String callerId;
        public String callerName;
        public String calleeId;
        public String type; // "audio" или "video"
        public String sdp; // Session Description
        public String candidate; // ICE candidate
        public String status; // pending, ringing, active, ended
        public long createdAt;

        public CallState(String callId, String callerId, String callerName, String calleeId, String type) {
            this.callId = callId;
            this.callerId = callerId;
            this.callerName = callerName;
            this.calleeId = calleeId;
            this.type = type;
            this.status = "pending";
            this.createdAt = System.currentTimeMillis();
        }
    }

    /**
     * Интерфейс для обработки событий звонков.
     */
    public interface CallListener {
        void onIncomingCall(CallState call);
        void onCallAnswered(String callId, String sdp);
        void onCallEnded(String callId);
        void onIceCandidate(String callId, String peerId, String candidate);
    }

    public void setListener(CallListener listener) {
        this.listener = listener;
    }

    /**
     * Инициировать звонок.
     */
    public CallState initiateCall(String callerId, String callerName, String calleeId, String type) {
        String callId = java.util.UUID.randomUUID().toString().substring(0, 8);
        CallState call = new CallState(callId, callerId, callerName, calleeId, type);
        
        activeCalls.put(callId, call);
        log.info("Initiated {} call {} from {} to {}", type, callId, callerId, calleeId);
        
        return call;
    }

    /**
     * Принять входящий звонок.
     */
    public void acceptCall(String callId, String sdp) {
        CallState call = activeCalls.get(callId);
        if (call != null) {
            call.status = "active";
            call.sdp = sdp;
            log.info("Call {} accepted", callId);
        }
    }

    /**
     * Отклонить звонок.
     */
    public void rejectCall(String callId) {
        CallState call = activeCalls.get(callId);
        if (call != null) {
            call.status = "rejected";
            activeCalls.remove(callId);
            log.info("Call {} rejected", callId);
        }
    }

    /**
     * Завершить звонок.
     */
    public void endCall(String callId) {
        CallState call = activeCalls.remove(callId);
        if (call != null) {
            call.status = "ended";
            log.info("Call {} ended", callId);
            
            if (listener != null) {
                listener.onCallEnded(callId);
            }
        }
    }

    /**
     * Получить SDP от WebRTC и передать пиру.
     */
    public void handleSdp(String callId, String sdp, String targetPeerId) {
        CallState call = activeCalls.get(callId);
        if (call != null) {
            if (call.callerId.equals(targetPeerId)) {
                // Это ответ от callee - отправляем caller
                call.sdp = sdp;
                call.status = "active";
            }
            
            if (listener != null) {
                listener.onCallAnswered(callId, sdp);
            }
        }
    }

    /**
     * Обработать ICE candidate.
     */
    public void handleIceCandidate(String callId, String candidate, String targetPeerId) {
        if (listener != null) {
            listener.onIceCandidate(callId, targetPeerId, candidate);
        }
    }

    /**
     * Получить состояние звонка.
     */
    public CallState getCall(String callId) {
        return activeCalls.get(callId);
    }

    /**
     * Получить активный звонок для пира.
     */
    public CallState getCallForPeer(String peerId) {
        return activeCalls.values().stream()
            .filter(c -> c.callerId.equals(peerId) || c.calleeId.equals(peerId))
            .findFirst()
            .orElse(null);
    }

    /**
     * Уведомление о входящем звонке (из сети).
     */
    public void notifyIncomingCall(CallState call) {
        incomingCalls.put(call.callerId, call);
        
        if (listener != null) {
            listener.onIncomingCall(call);
        }
    }
}