package com.chat.p2p.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit-тесты для CallService.
 */
class CallServiceTest {

    private CallService callService;

    @BeforeEach
    void setUp() {
        callService = new CallService();
    }

    @Test
    void initiateCall_createsCallState() {
        var call = callService.initiateCall("caller-1", "Alice", "callee-2", "voice");

        assertThat(call.callId).isNotBlank();
        assertThat(call.callerId).isEqualTo("caller-1");
        assertThat(call.callerName).isEqualTo("Alice");
        assertThat(call.calleeId).isEqualTo("callee-2");
        assertThat(call.type).isEqualTo("voice");
        assertThat(call.status).isEqualTo("pending");
    }

    @Test
    void acceptCall_changesStatusToActive() {
        var call = callService.initiateCall("caller", "Alice", "callee", "video");

        callService.acceptCall(call.callId, "v=0\r\no=- ...");

        var updated = callService.getCall(call.callId);
        assertThat(updated.status).isEqualTo("active");
        assertThat(updated.sdp).contains("v=0");
    }

    @Test
    void rejectCall_changesStatusToRejectedAndRemoves() {
        var call = callService.initiateCall("caller", "Alice", "callee", "voice");
        String callId = call.callId;

        callService.rejectCall(callId);

        assertThat(callService.getCall(callId)).isNull();
    }

    @Test
    void endCall_changesStatusToEndedAndRemoves() {
        var call = callService.initiateCall("caller", "Alice", "callee", "voice");
        String callId = call.callId;

        callService.endCall(callId);

        assertThat(callService.getCall(callId)).isNull();
    }

    @Test
    void handleSdp_storesSdpInfo_whenTargetIsCaller() {
        var call = callService.initiateCall("caller-1", "Alice", "callee-2", "video");

        // SDP от callee -> caller (targetPeerId = caller-1)
        callService.handleSdp(call.callId, "v=0\r\no=- ...", "caller-1");

        var updated = callService.getCall(call.callId);
        assertThat(updated.sdp).contains("v=0");
        assertThat(updated.status).isEqualTo("active");
    }

    @Test
    void handleIceCandidate_invokesListener() {
        var call = callService.initiateCall("caller", "Alice", "callee", "voice");

        final var receivedCandidates = new java.util.ArrayList<String>();
        callService.setListener(new CallService.CallListener() {
            public void onIncomingCall(CallService.CallState c) {}
            public void onCallAnswered(String id, String sdp) {}
            public void onCallEnded(String id) {}
            public void onIceCandidate(String id, String peerId, String candidate) {
                receivedCandidates.add(candidate);
            }
        });

        callService.handleIceCandidate(call.callId, "candidate:1 ...", "callee");

        assertThat(receivedCandidates).contains("candidate:1 ...");
    }

    @Test
    void getCall_nonExistent_returnsNull() {
        assertThat(callService.getCall("fake-call")).isNull();
    }

    @Test
    void getCallForPeer_returnsCallWhenPeerIsInvolved() {
        var call = callService.initiateCall("caller-1", "Alice", "callee-2", "voice");

        var result = callService.getCallForPeer("caller-1");
        assertThat(result).isEqualTo(call);

        var result2 = callService.getCallForPeer("callee-2");
        assertThat(result2).isEqualTo(call);
    }

    @Test
    void getCallForPeer_returnsNullWhenPeerNotInCall() {
        callService.initiateCall("caller-1", "Alice", "callee-2", "voice");

        assertThat(callService.getCallForPeer("bystander")).isNull();
    }
}
