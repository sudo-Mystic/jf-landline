package com.jio.calling.telecom;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.telecom.CallAudioState;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.telecom.TelecomManager;
import android.util.Log;

import com.jio.calling.sip.JioCallListener;
import com.jio.calling.sip.JioSipManager;

/**
 * Telecom Connection managing call state, audio, hold, and DTMF during a call handled by Telecom.
 */
@TargetApi(Build.VERSION_CODES.M)
public class JioConnection extends Connection implements JioCallListener {
    private static final String TAG = "JioConnection";

    private final Context context;
    private final String phoneNumber;
    private final boolean isIncoming;
    private final JioSipManager sipManager;

    public JioConnection(Context context, String phoneNumber, boolean isIncoming) {
        this.context = context;
        this.phoneNumber = phoneNumber;
        this.isIncoming = isIncoming;
        this.sipManager = JioSipManager.getInstance(context);

        setConnectionCapabilities(CAPABILITY_MUTE | CAPABILITY_SUPPORT_HOLD);
        setAudioModeIsVoip(true);
        if (phoneNumber != null && !phoneNumber.isEmpty()) {
            setAddress(Uri.fromParts("tel", phoneNumber, null), TelecomManager.PRESENTATION_ALLOWED);
        }

        sipManager.addCallListener(this);
    }

    @Override
    public void onAnswer() {
        Log.i(TAG, "Telecom onAnswer called");
        sipManager.answerCall();
        // setActive() occurs when the real SIP call is confirmed.
    }

    @Override
    public void onReject() {
        Log.i(TAG, "Telecom onReject called");
        sipManager.hangupCall();
        setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
        destroy();
    }

    @Override
    public void onDisconnect() {
        Log.i(TAG, "Telecom onDisconnect called");
        sipManager.hangupCall();
        setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
        destroy();
    }

    @Override
    public void onAbort() {
        Log.i(TAG, "Telecom onAbort called");
        sipManager.hangupCall();
        setDisconnected(new DisconnectCause(DisconnectCause.CANCELED));
        destroy();
    }

    @Override
    public void onCallAudioStateChanged(CallAudioState state) {
        super.onCallAudioStateChanged(state);
        if (state != null && sipManager.isCallActive()) {
            sipManager.getCallAudioManager().setMute(state.isMuted());
            // Telecom owns the route; do not call AudioManager.setSpeakerphoneOn here.
            sipManager.setSpeakerphoneActive(state.getRoute() == CallAudioState.ROUTE_SPEAKER);
        }
    }

    @Override
    public void onPlayDtmfTone(char c) {
        Log.i(TAG, "Telecom onPlayDtmfTone: " + c);
        sipManager.sendDtmf(String.valueOf(c));
    }

    // --- JioCallListener callbacks ---

    @Override
    public void onCallConnecting() {
        if (!isIncoming) {
            setDialing();
        }
    }

    @Override
    public void onCallRinging() {
        // Early ringing state
    }

    @Override
    public void onCallEstablished() {
        setActive();
    }

    @Override
    public void onCallTerminated(String reason) {
        Log.i(TAG, "Telecom onCallTerminated: " + reason);
        setDisconnected(new DisconnectCause(DisconnectCause.REMOTE, reason));
        destroy();
    }

    @Override
    public void onCallIncoming(String remoteNumber, String remoteDisplayName) {}

    @Override
    public void onStateChanged(int state) {
        super.onStateChanged(state);
        if (state == STATE_DISCONNECTED) {
            sipManager.removeCallListener(this);
        }
    }
}
