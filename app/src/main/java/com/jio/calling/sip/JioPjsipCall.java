package com.jio.calling.sip;

import android.content.Context;
import android.util.Log;

import org.pjsip.pjsua2.Account;
import org.pjsip.pjsua2.AudDevManager;
import org.pjsip.pjsua2.AudioMedia;
import org.pjsip.pjsua2.AudioMediaVector2;
import org.pjsip.pjsua2.Call;
import org.pjsip.pjsua2.CallInfo;
import org.pjsip.pjsua2.CallMediaInfo;
import org.pjsip.pjsua2.Endpoint;
import org.pjsip.pjsua2.Media;
import org.pjsip.pjsua2.OnCallMediaStateParam;
import org.pjsip.pjsua2.OnCallSdpCreatedParam;
import org.pjsip.pjsua2.OnCallStateParam;
import org.pjsip.pjsua2.SdpSession;
import org.pjsip.pjsua2.pjmedia_type;
import org.pjsip.pjsua2.pjsua_call_media_status;
import org.pjsip.pjsua2.pjsip_inv_state;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * PJSIP Call implementation handling call state transitions and audio routing.
 */
public class JioPjsipCall extends Call {
    private static final String TAG = "JioPjsipCall";
    private final JioSipManager sipManager;
    private boolean mediaConnected = false;

    public JioPjsipCall(Account acc, JioSipManager manager) {
        super(acc);
        this.sipManager = manager;
        logToFile("JioPjsipCall created (outgoing)");
    }

    public JioPjsipCall(Account acc, int callId, JioSipManager manager) {
        super(acc, callId);
        this.sipManager = manager;
        logToFile("JioPjsipCall created (incoming, id=" + callId + ")");
    }

    private void logToFile(String message) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = ts + " [" + TAG + "] " + message + "\n";
        Log.e(TAG, message);
        try {
            Context ctx = sipManager.getContext();
            if (ctx != null) {
                File logFile = new File(ctx.getFilesDir(), "call_audio_debug.txt");
                FileWriter fw = new FileWriter(logFile, true);
                fw.write(line);
                fw.flush();
                fw.close();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCallState(OnCallStateParam prm) {
        try {
            CallInfo ci = getInfo();
            int state = ci.getState();
            String stateText = ci.getStateText();
            int code = ci.getLastStatusCode();
            String reason = ci.getLastReason();
            logToFile("onCallState: State=" + stateText + " (" + state + "), Code=" + code + ", Reason=" + reason);

            if (state == pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED || state == pjsip_inv_state.PJSIP_INV_STATE_EARLY) {
                connectMedia();
            } else if (state == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
                disconnectMedia();
            }

            sipManager.handleCallState(this, state, reason, ci.getRemoteUri());
        } catch (Exception e) {
            logToFile("Error in onCallState: " + e.getMessage());
        }
    }

    @Override
    public void onCallMediaState(OnCallMediaStateParam prm) {
        logToFile("onCallMediaState callback received");
        connectMedia();
    }

    @Override
    public void onCallSdpCreated(OnCallSdpCreatedParam prm) {
        try {
            SdpSession sdp = prm.getSdp();
            if (sdp != null) {
                String wholeSdp = sdp.getWholeSdp();
                logToFile("onCallSdpCreated: SDP offer created by PJSIP:\n" + wholeSdp);
                if (wholeSdp != null && wholeSdp.contains("a=X-nat:0")) {
                    String cleaned = wholeSdp.replace("a=X-nat:0\r\n", "").replace("a=X-nat:0\n", "");
                    sdp.setWholeSdp(cleaned);
                    logToFile("onCallSdpCreated: stripped a=X-nat:0 from SDP offer");
                }
            }
        } catch (Exception e) {
            logToFile("Error in onCallSdpCreated: " + e.getMessage());
        }
    }

    /**
     * Connects call audio streams to the sound hardware bridge.
     * Can be invoked safely multiple times from onCallMediaState, onCallState, or
     * UI handlers.
     */
    public synchronized boolean connectMedia() {
        try {
            String currentState = "unknown";
            try {
                CallInfo ci = getInfo();
                if (ci != null) {
                    currentState = ci.getStateText() + " (" + ci.getState() + ")";
                }
            } catch (Exception ignored) {
            }
            logToFile("connectMedia called in state: " + currentState);

            Endpoint ep = sipManager.getEndpoint();
            if (ep == null) {
                logToFile("connectMedia: Endpoint is null");
                return false;
            }

            AudDevManager adm = ep.audDevManager();
            AudioMedia callAudMed = null;

            // Attempt 1: getAudioMedia(-1) directly finds the active conference slot
            try {
                callAudMed = getAudioMedia(-1);
                if (callAudMed != null) {
                    logToFile("connectMedia: getAudioMedia(-1) found valid audio slot " + callAudMed.getPortId());
                }
            } catch (Exception e) {
                logToFile("connectMedia: getAudioMedia(-1) exception: " + e.getMessage());
            }

            // Attempt 2: fallback via CallInfo inspection
            if (callAudMed == null) {
                try {
                    CallInfo ci = getInfo();
                    int mediaCount = (int) ci.getMedia().size();
                    logToFile("connectMedia: fallback inspection, media count=" + mediaCount);

                    for (int i = 0; i < mediaCount; i++) {
                        CallMediaInfo cmi = ci.getMedia().get(i);
                        logToFile(
                                "connectMedia: media[" + i + "] type=" + cmi.getType() + " status=" + cmi.getStatus());

                        if (cmi.getType() == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                                cmi.getStatus() == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                            Media m = getMedia(i);
                            if (m != null) {
                                callAudMed = AudioMedia.typecastFromMedia(m);
                                if (callAudMed != null) {
                                    logToFile(
                                            "connectMedia: typecastFromMedia success, slot=" + callAudMed.getPortId());
                                    break;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    logToFile("connectMedia: fallback inspection error: " + e.getMessage());
                }
            }

            if (callAudMed == null) {
                logToFile("connectMedia: callAudMed is still null, cannot connect yet");
                return false;
            }

            AudioMedia capDev = adm.getCaptureDevMedia();
            AudioMedia playDev = adm.getPlaybackDevMedia();
            int capSlot = capDev != null ? capDev.getPortId() : -1;
            int playSlot = playDev != null ? playDev.getPortId() : -1;
            int callSlot = callAudMed.getPortId();

            logToFile("connectMedia: CallSlot=" + callSlot + " -> PlaySlot=" + playSlot + ", CapSlot=" + capSlot
                    + " -> CallSlot=" + callSlot);

            boolean captureRequested = false;
            boolean playbackRequested = false;

            // Connect Microphone -> Call
            if (capDev != null) {
                try {
                    capDev.startTransmit(callAudMed);
                    captureRequested = true;
                    logToFile("connectMedia: microphone TX connection requested");
                } catch (Exception e) {
                    logToFile("connectMedia: capDev.startTransmit ERROR: " + e.getMessage());
                }
            }

            // Connect Call -> Speaker/Earpiece Playback
            if (playDev != null) {
                try {
                    callAudMed.startTransmit(playDev);
                    playbackRequested = true;
                    logToFile("connectMedia: playback RX connection requested");
                } catch (Exception e) {
                    logToFile("connectMedia: callAudMed.startTransmit ERROR: " + e.getMessage());
                }
            }

            // Maximize volume and boost RX/TX levels
            try {
                adm.setOutputVolume(100);
            } catch (Exception ignored) {
            }

            applyVolumeBoost(sipManager.isSpeakerphoneActive());

            // Enumerate and log all active conference ports for verification
            try {
                AudioMediaVector2 confPorts = ep.mediaEnumPorts2();
                logToFile("connectMedia: Bridge port count=" + confPorts.size());
                for (int p = 0; p < confPorts.size(); p++) {
                    AudioMedia port = confPorts.get(p);
                    logToFile("  ConfPort[" + p + "]: slot=" + port.getPortId());
                }
            } catch (Exception ignored) {
            }

            // startTransmit may finish asynchronously. These flags only mean
            // that both requests were accepted; they do not prove RTP carries speech.
            mediaConnected = captureRequested && playbackRequested;
            if (!mediaConnected) {
                logToFile("connectMedia: incomplete: captureRequested=" + captureRequested
                        + ", playbackRequested=" + playbackRequested);
            }
            return mediaConnected;
        } catch (Exception e) {
            logToFile("connectMedia: FATAL exception: " + e.getMessage());
            return false;
        }
    }

    public synchronized void applyVolumeBoost(boolean isSpeaker) {
        try {
            Endpoint ep = sipManager.getEndpoint();
            if (ep == null)
                return;
            AudDevManager adm = ep.audDevManager();
            AudioMedia playDev = adm != null ? adm.getPlaybackDevMedia() : null;
            AudioMedia capDev = adm != null ? adm.getCaptureDevMedia() : null;
            AudioMedia callAudMed = null;
            try {
                callAudMed = getAudioMedia(-1);
            } catch (Exception ignored) {
            }

            float callTxBoost = isSpeaker ? 1.6f : 1.1f; // Clean +4dB boost on speakerphone without clipping PCM
                                                         // headroom
            float callRxBoost = 1.5f; // Boost incoming microphone audio sent to RTP encoder
            float playDevRxBoost = 1.0f; // Unity gain on playback port to avoid compounding gain distortion

            if (capDev != null) {
                try {
                    capDev.adjustTxLevel(1.5f); // Boost microphone input into conference bridge (+3.5 dB)
                    logToFile("applyVolumeBoost: capDev.adjustTxLevel(1.5f) applied");
                } catch (Exception e) {
                    logToFile("applyVolumeBoost: capDev adjustTxLevel error: " + e.getMessage());
                }
            }

            if (callAudMed != null) {
                try {
                    callAudMed.adjustTxLevel(callTxBoost);
                    logToFile("applyVolumeBoost: callAudMed.adjustTxLevel(" + callTxBoost + ") applied");
                } catch (Exception e) {
                    logToFile("applyVolumeBoost: adjustTxLevel error: " + e.getMessage());
                }

                try {
                    callAudMed.adjustRxLevel(callRxBoost);
                    logToFile("applyVolumeBoost: callAudMed.adjustRxLevel(" + callRxBoost + ") applied");
                } catch (Exception e) {
                    logToFile("applyVolumeBoost: adjustRxLevel error: " + e.getMessage());
                }
            }

            if (playDev != null) {
                try {
                    playDev.adjustRxLevel(playDevRxBoost);
                    logToFile("applyVolumeBoost: playDev.adjustRxLevel(" + playDevRxBoost + ") applied");
                } catch (Exception e) {
                    logToFile("applyVolumeBoost: playDev adjustRxLevel error: " + e.getMessage());
                }
            }

            if (adm != null) {
                try {
                    adm.setOutputVolume(100);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            logToFile("applyVolumeBoost exception: " + e.getMessage());
        }
    }

    public synchronized void disconnectMedia() {
        // Always clean up. One direction may have connected even if the other failed.
        try {
            logToFile("disconnectMedia: stopping transmits");
            Endpoint ep = sipManager.getEndpoint();
            if (ep != null) {
                AudDevManager adm = ep.audDevManager();
                if (adm != null) {
                    AudioMedia capDev = null;
                    AudioMedia playDev = null;
                    try {
                        capDev = adm.getCaptureDevMedia();
                        playDev = adm.getPlaybackDevMedia();
                    } catch (Exception ignored) {
                    }

                    AudioMedia callAudMed = null;
                    try {
                        callAudMed = getAudioMedia(-1);
                    } catch (Exception ignored) {
                    }

                    if (capDev != null && callAudMed != null) {
                        try {
                            capDev.stopTransmit(callAudMed);
                            logToFile("capDev.stopTransmit(callAudMed) success");
                        } catch (Exception e) {
                            logToFile("capDev.stopTransmit error: " + e.getMessage());
                        }
                    }
                    if (playDev != null && callAudMed != null) {
                        try {
                            callAudMed.stopTransmit(playDev);
                            logToFile("callAudMed.stopTransmit(playDev) success");
                        } catch (Exception e) {
                            logToFile("callAudMed.stopTransmit error: " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            logToFile("disconnectMedia exception: " + e.getMessage());
        } finally {
            mediaConnected = false;
        }
    }

    public boolean isMediaConnected() {
        return mediaConnected;
    }
}
