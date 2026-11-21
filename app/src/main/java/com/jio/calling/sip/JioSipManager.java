package com.jio.calling.sip;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.jio.calling.audio.JioAudioManager;
import com.jio.calling.model.JioAccount;
import com.jio.calling.util.JioCallLogHelper;

import java.util.Locale;

import org.pjsip.pjsua2.AccountConfig;
import org.pjsip.pjsua2.AuthCredInfo;
import org.pjsip.pjsua2.CallInfo;
import org.pjsip.pjsua2.CallOpParam;
import org.pjsip.pjsua2.CodecFmtp;
import org.pjsip.pjsua2.CodecFmtpVector;
import org.pjsip.pjsua2.CodecParam;
import org.pjsip.pjsua2.Endpoint;
import org.pjsip.pjsua2.EpConfig;
import org.pjsip.pjsua2.IpChangeParam;
import org.pjsip.pjsua2.LogEntry;
import org.pjsip.pjsua2.LogWriter;
import org.pjsip.pjsua2.SipHeader;
import org.pjsip.pjsua2.TransportConfig;
import org.pjsip.pjsua2.pjsip_inv_state;
import org.pjsip.pjsua2.pjsip_status_code;
import org.pjsip.pjsua2.pjsip_transport_type_e;
import org.pjsip.pjsua2.pjsua_100rel_use;
import org.pjsip.pjsua2.pjsua_sip_timer_use;

import java.util.ArrayList;
import java.util.List;

/**
 * High-level manager coordinating SIP registration and call management via native PJSIP.
 * Sets up TLS transport on port 5068, configures Digest authentication,
 * prioritizes AMR codec, and handles incoming/outgoing VoIP calls.
 */
public class JioSipManager {
    private static final String TAG = "JioSipManager";
    private static JioSipManager instance;

    private final Context context;
    private final JioAudioManager callAudioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Endpoint endpoint;
    private JioPjsipAccount pjsipAccount;
    private JioPjsipCall activeCall;
    private JioAccount currentAccount;
    private LogWriter logWriter;

    private boolean isInitialized = false;
    private boolean isRegistered = false;
    private boolean isNativeLoaded = false;

    // Call state tracking
    private String activeCallNumber = null;
    private boolean isCallActive = false;

    private final List<JioRegistrationListener> regListeners = new ArrayList<>();
    private final List<JioCallListener> callListeners = new ArrayList<>();

    private JioSipManager(Context context) {
        this.context = context.getApplicationContext();
        this.callAudioManager = new JioAudioManager(this.context);
    }

    public static synchronized JioSipManager getInstance(Context context) {
        if (instance == null) {
            instance = new JioSipManager(context);
        }
        return instance;
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    /** Audio is owned by the SIP call lifecycle, not the activity lifecycle. */
    public JioAudioManager getCallAudioManager() {
        return callAudioManager;
    }

    private boolean prepareCallAudio() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Cannot start outgoing audio: RECORD_AUDIO not granted");
            return false;
        }
        return callAudioManager.startCallAudio();
    }

    public void addRegistrationListener(JioRegistrationListener l) {
        synchronized (regListeners) {
            if (!regListeners.contains(l)) regListeners.add(l);
        }
    }

    public void removeRegistrationListener(JioRegistrationListener l) {
        synchronized (regListeners) {
            regListeners.remove(l);
        }
    }

    public void addCallListener(JioCallListener l) {
        synchronized (callListeners) {
            if (!callListeners.contains(l)) callListeners.add(l);
        }
    }

    public void removeCallListener(JioCallListener l) {
        synchronized (callListeners) {
            callListeners.remove(l);
        }
    }

    /**
     * Initializes the native PJSIP stack, audio engine, and network transports.
     */
    public synchronized void initialize() {
        if (isInitialized) return;

        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("pjsua2");
            isNativeLoaded = true;
            Log.i(TAG, "Native PJSIP libraries loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native PJSIP libraries: " + e.getMessage(), e);
            isNativeLoaded = false;
            return;
        }

        try {
            endpoint = new Endpoint();
            endpoint.libCreate();

            EpConfig epConfig = new EpConfig();
            epConfig.getLogConfig().setLevel(5);
            epConfig.getLogConfig().setConsoleLevel(5);
            logWriter = new LogWriter() {
                @Override
                public void write(LogEntry entry) {
                    if (entry != null && entry.getMsg() != null) {
                        Log.d("PjsipLog", entry.getMsg().trim());
                    }
                }
            };
            epConfig.getLogConfig().setWriter(logWriter);
            epConfig.getUaConfig().setUserAgent("JUICEJFV-1.3.32");

            // Media configuration
            // Auto-close sound device 1 second after call ends so Android AudioTrack/AudioRecord
            // release hardware control and allow system sounds, ringtones, and music to play.
            epConfig.getMedConfig().setSndAutoCloseTime(1);

            // Disable software echo cancellation (Speex AEC)
            // Speex software AEC causes massive xruns, latency mismatch, and discards all playback frames on Android MT6572.
            // Hardware AEC is provided natively by the Android Audio HAL in MODE_IN_COMMUNICATION.
            epConfig.getMedConfig().setEcTailLen(0);

            // Audio clock rates: Native 44100Hz stereo conference bridge and sound device
            // The MT6572 hardware DAC strictly requires 44100Hz stereo (channel mask 0x0003).
            // When clockRate and sndClockRate both match 44100Hz, no intermediate resample_port
            // is placed on the sound device, completely preventing PJMEDIA_ENCSAMPLESPFRAME errors.
            // Match AMR frame packetization and telephony standards: 8000Hz mono.
            // AMR-NB frames are strictly 20ms blocks (160 samples at 8000Hz). Setting clockRate to
            // 8000Hz and channelCount to 1 (mono) guarantees a 100% exact match with OpenCORE AMR-NB,
            // completely eliminating resamplers and preventing the 220162 (PJMEDIA_ENCSAMPLESPFRAME) audio failure.
            epConfig.getMedConfig().setClockRate(8000);
            epConfig.getMedConfig().setSndClockRate(8000);
            epConfig.getMedConfig().setChannelCount(1);

            // Disable VAD (silence suppression) to keep continuous 20ms RTP packet pacing for Jio IMS gateway.
            // In JioFiber/AirFiber networks, silence suppression causes the gateway to discard/pause RTP flow.
            epConfig.getMedConfig().setNoVad(true);

            // Disable software clock and delay buffers (playdbuf / capdbuf).
            // On low-end MT6572 hardware, the software timer clock lags behind hardware AudioTrack DAC consumption,
            // causing playdbuf to permanently underflow and zero out (silence) all incoming audio frames.
            // Using native device clock feeds decoded frames directly from the conference bridge to AudioTrack without dropouts.
            epConfig.getMedConfig().setSndUseSwClock(false);

            // Audio buffer latency (in ms) to prevent AudioTrack underruns on MediaTek HAL
            epConfig.getMedConfig().setSndPlayLatency(100);
            epConfig.getMedConfig().setSndRecLatency(100);
            epConfig.getMedConfig().setJbInit(40);
            epConfig.getMedConfig().setJbMinPre(20);
            epConfig.getMedConfig().setJbMaxPre(80);
            epConfig.getMedConfig().setJbMax(300);

            endpoint.libInit(epConfig);

            // 1. Create SIP TLS transport on port 5068 (or ephemeral)
            TransportConfig tlsCfg = new TransportConfig();
            tlsCfg.setPort(0);
            tlsCfg.getTlsConfig().setVerifyServer(false);
            tlsCfg.getTlsConfig().setVerifyClient(false);
            endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, tlsCfg);
            Log.i(TAG, "Created SIP TLS transport");

            // 2. Create SIP UDP transport as fallback
            TransportConfig udpCfg = new TransportConfig();
            udpCfg.setPort(0);
            endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, udpCfg);
            Log.i(TAG, "Created SIP UDP transport");

            endpoint.libStart();
            Log.i(TAG, "PJSIP stack started successfully");

            // Enumerate and prioritize codecs
            try {
                org.pjsip.pjsua2.CodecInfoVector2 codecs = endpoint.codecEnum2();
                for (int i = 0; i < codecs.size(); i++) {
                    String id = codecs.get(i).getCodecId();
                    Log.i(TAG, "Available codec: " + id + " priority=" + codecs.get(i).getPriority());
                    boolean isWb = id.toLowerCase().contains("amr-wb");
                    boolean isNb = id.toLowerCase().contains("amr") && !isWb;

                    if (isNb) {
                        // AMR-NB is the gold standard for JioFiber fixed-line VoIP (8000Hz mono).
                        // Highest priority (255) ensures AMR-NB is offered and selected first over AMR-WB.
                        endpoint.codecSetPriority(id, (short) 255);
                        Log.i(TAG, "Promoted AMR-NB codec priority: " + id + " -> 255");

                        try {
                            CodecParam param = endpoint.codecGetParam(id);
                            if (param != null) {
                                // Strictly enforce AMR-NB 12.2 kbps (Mode 7) for Jio IMS
                                // Stock PJSIP defaults avgBps to 5900 (Mode 2), causing Jio IMS to drop/reject RTP
                                if (param.getInfo() != null) {
                                    param.getInfo().setAvgBps(12200);
                                    param.getInfo().setMaxBps(12200);
                                }

                                if (param.getSetting() != null) {
                                    param.getSetting().setVad(false);
                                    param.getSetting().setCng(false);
                                    param.getSetting().setPlc(true);

                                    CodecFmtpVector fmtpVec = new CodecFmtpVector();

                                    // Official JioJoin & RFC 4867 specification:
                                    // octet-align=1; mode-change-capability=2; max-red=0; mode-set=0,1,2,3,4,5,6,7
                                    CodecFmtp f0 = new CodecFmtp();
                                    f0.setName("octet-align");
                                    f0.setVal("1");
                                    fmtpVec.add(f0);

                                    CodecFmtp f1 = new CodecFmtp();
                                    f1.setName("mode-change-capability");
                                    f1.setVal("2");
                                    fmtpVec.add(f1);

                                    CodecFmtp f2 = new CodecFmtp();
                                    f2.setName("max-red");
                                    f2.setVal("0");
                                    fmtpVec.add(f2);

                                    CodecFmtp f3 = new CodecFmtp();
                                    f3.setName("mode-set");
                                    f3.setVal("0,1,2,3,4,5,6,7");
                                    fmtpVec.add(f3);

                                    param.getSetting().setEncFmtp(fmtpVec);

                                    // PJSIP uses decFmtp to generate the SDP offer. Both octet-align=1 and
                                    // mode-set=0,1,2,3,4,5,6,7 MUST be advertised in decFmtp so the router/IMS
                                    // core accepts our media offer and mid-dialog updates without dropping.
                                    CodecFmtpVector decVec = new CodecFmtpVector();
                                    CodecFmtp d0 = new CodecFmtp();
                                    d0.setName("octet-align");
                                    d0.setVal("1");
                                    decVec.add(d0);

                                    CodecFmtp d1 = new CodecFmtp();
                                    d1.setName("mode-set");
                                    d1.setVal("0,1,2,3,4,5,6,7");
                                    decVec.add(d1);

                                    param.getSetting().setDecFmtp(decVec);
                                }
                                endpoint.codecSetParam(id, param);
                                Log.i(TAG, "Configured AMR-NB codec: 12.2kbps (Mode 7), no-VAD, PLC, octet-align=1, mode-set=0,1,2,3,4,5,6,7");
                            }
                        } catch (Exception fe) {
                            Log.w(TAG, "Could not set AMR-NB fmtp: " + fe.getMessage());
                        }
                    } else if (isWb) {
                        // AMR-WB has lower priority (240) than AMR-NB (255) so 8000Hz AMR-NB is preferred.
                        // Configure valid AMR-WB bitrates (23.85 kbps Mode 8) and octet-align to prevent encoder failure.
                        endpoint.codecSetPriority(id, (short) 240);
                        Log.i(TAG, "Configured AMR-WB codec priority: " + id + " -> 240");

                        try {
                            CodecParam param = endpoint.codecGetParam(id);
                            if (param != null) {
                                if (param.getInfo() != null) {
                                    param.getInfo().setAvgBps(23850);
                                    param.getInfo().setMaxBps(23850);
                                }

                                if (param.getSetting() != null) {
                                    param.getSetting().setVad(false);
                                    param.getSetting().setCng(false);
                                    param.getSetting().setPlc(true);

                                    CodecFmtpVector fmtpVec = new CodecFmtpVector();
                                    CodecFmtp f0 = new CodecFmtp();
                                    f0.setName("octet-align");
                                    f0.setVal("1");
                                    fmtpVec.add(f0);

                                    param.getSetting().setEncFmtp(fmtpVec);

                                    CodecFmtpVector decVec = new CodecFmtpVector();
                                    CodecFmtp d0 = new CodecFmtp();
                                    d0.setName("octet-align");
                                    d0.setVal("1");
                                    decVec.add(d0);

                                    param.getSetting().setDecFmtp(decVec);
                                }
                                endpoint.codecSetParam(id, param);
                                Log.i(TAG, "Configured AMR-WB codec: 23.85kbps, octet-align=1");
                            }
                        } catch (Exception fe) {
                            Log.w(TAG, "Could not set AMR-WB fmtp: " + fe.getMessage());
                        }
                    } else if (id.toLowerCase().contains("pcma") || id.toLowerCase().contains("pcmu")) {
                        endpoint.codecSetPriority(id, (short) 50);
                    } else {
                        endpoint.codecSetPriority(id, (short) 0);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Codec enumeration warning: " + e.getMessage());
            }

            isInitialized = true;
        } catch (Exception e) {
            Log.e(TAG, "Error initializing PJSIP Endpoint: " + e.getMessage(), e);
        }
    }

    public synchronized void handleNetworkChange() {
        ensureThreadRegistered();
        try {
            if (endpoint != null && isInitialized) {
                IpChangeParam prm = new IpChangeParam();
                prm.setRestartListener(true);
                endpoint.handleIpChange(prm);
                Log.i(TAG, "Notified PJSIP endpoint of IP/network change");
            }
        } catch (Exception e) {
            Log.w(TAG, "Error handling IP change in PJSIP: " + e.getMessage());
        }
    }

    /**
     * Periodic keepalive heartbeat called from AlarmManager while device is sleeping.
     * Pokes PJSIP event loop to process any pending timers and send UDP NAT keepalives.
     */
    public synchronized void handleKeepAlive() {
        if (!isInitialized || !isNativeLoaded || endpoint == null) {
            return;
        }
        ensureThreadRegistered();
        try {
            endpoint.libHandleEvents(10);
        } catch (Exception e) {
            Log.w(TAG, "Keepalive event processing error: " + e.getMessage());
        }
    }

    public void ensureThreadRegistered() {
        if (endpoint != null && isNativeLoaded) {
            try {
                if (!endpoint.libIsThreadRegistered()) {
                    endpoint.libRegisterThread("Thread_" + Thread.currentThread().getId());
                }
            } catch (Exception e) {
                Log.w(TAG, "Error registering native thread: " + e.getMessage());
            }
        }
    }

    /**
     * Registers the SIP account for JioFiber VoIP.
     */
    public synchronized void registerAccount(JioAccount account) {
        if (account == null || !account.isValid()) {
            notifyRegistrationFailed(400, "Invalid Account Configuration");
            return;
        }

        this.currentAccount = account;
        notifyRegistrationProgress();

        if (!isInitialized || !isNativeLoaded || endpoint == null) {
            Log.w(TAG, "PJSIP not initialized, attempting initialize()...");
            initialize();
            if (!isInitialized || endpoint == null) {
                notifyRegistrationFailed(500, "Native Engine Initialization Failed");
                return;
            }
        }

        ensureThreadRegistered();

        try {
            if (pjsipAccount != null) {
                try {
                    pjsipAccount.delete();
                } catch (Exception ignored) {}
                pjsipAccount = null;
            }

            Log.i(TAG, "Registering JioAccount:");
            Log.i(TAG, "  User     : " + account.getUsername());
            Log.i(TAG, "  Proxy    : " + account.getProxyUri());
            Log.i(TAG, "  Registrar: " + account.getRegistrarUri());
            Log.i(TAG, "  ID       : " + account.getIdUri());

            String idUri = account.getIdUri();
            if (idUri != null && !idUri.startsWith("<")) {
                idUri = "<" + idUri + ">";
            }
            AccountConfig accCfg = new AccountConfig();
            accCfg.setIdUri(idUri);
            accCfg.getRegConfig().setRegistrarUri(account.getRegistrarUri());
            accCfg.getRegConfig().setTimeoutSec(300);
            accCfg.getRegConfig().setRegisterOnAdd(true);

            // REGISTER headers required by Jio IMS
            try {
                SipHeader regPani = new SipHeader();
                regPani.setHName("P-Access-Network-Info");
                regPani.setHValue("IEEE-802.11");
                accCfg.getRegConfig().getHeaders().add(regPani);

                SipHeader regPpi = new SipHeader();
                regPpi.setHName("P-Preferred-Identity");
                regPpi.setHValue(idUri);
                accCfg.getRegConfig().getHeaders().add(regPpi);
            } catch (Exception re) {
                Log.w(TAG, "Could not add registration headers: " + re.getMessage());
            }

            // Contact params with essential Jio IMS capability tags (+u.jio.jfv for outbound authorization,
            // mmtel/telephony/video for IMS voice routing, kept under 256 bytes to prevent PJ_ETOOSMALL).
            String contactParams = ";+u.jio.jfv;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\";+g.gsma.rcs.telephony=\"none\";video";
            accCfg.getSipConfig().setContactParams(contactParams);

            // Enable 100rel / PRACK (mandatory for reliable provisional responses from Jio core)
            accCfg.getCallConfig().setPrackUse(pjsua_100rel_use.PJSUA_100REL_OPTIONAL);
            accCfg.getCallConfig().setTimerUse(pjsua_sip_timer_use.PJSUA_SIP_TIMER_OPTIONAL);
            accCfg.getCallConfig().setTimerMinSESec(90);
            accCfg.getCallConfig().setTimerSessExpiresSec(1800);

            // Outbound proxy with loose routing
            accCfg.getSipConfig().getProxies().add(account.getProxyUri() + ";lr");

            // Digest authentication
            AuthCredInfo cred = new AuthCredInfo("Digest", "*", account.getAuthUser(), 0, account.getPassword());
            accCfg.getSipConfig().getAuthCreds().add(cred);

            // RFC 5626 Outbound & Instance ID
            accCfg.getNatConfig().setSipOutboundUse(1);
            String instanceId = account.getInstanceUuid();
            if (instanceId == null || instanceId.isEmpty()) {
                String mac = account.getVirtualMac() != null ? account.getVirtualMac().replace(":", "").toUpperCase(Locale.US) : "000000000000";
                instanceId = "00000000-0000-1000-8000-" + mac;
            }
            if (instanceId.startsWith("<") && instanceId.endsWith(">")) {
                accCfg.getNatConfig().setSipOutboundInstanceId(instanceId);
            } else {
                accCfg.getNatConfig().setSipOutboundInstanceId("<" + instanceId + ">");
            }
            Log.i(TAG, "Configured SIP Outbound Instance ID: " + instanceId + " (MAC: " + account.getVirtualMac() + ")");
            accCfg.getNatConfig().setUdpKaIntervalSec(15);

            // Configure media transport
            String registrarHost = account.getRegistrarHost();
            String localIp = getLocalIpAddress(registrarHost);
            Log.i(TAG, "Configuring media transport address: " + (!localIp.isEmpty() ? localIp : "<auto>"));
            if (!localIp.isEmpty()) {
                accCfg.getMediaConfig().getTransportConfig().setPublicAddress(localIp);
            }
            // CRITICAL: Leaving boundAddress empty ("") binds the RTP UDP sockets to INADDR_ANY (0.0.0.0).
            // This allows the OS kernel to dynamically receive and route RTP packets across all interfaces
            // (Wi-Fi, Ethernet, or NetBird VPN tunnel) and completely prevents EADDRNOTAVAIL (cannot assign requested address).
            accCfg.getMediaConfig().getTransportConfig().setBoundAddress("");

            // Standard JioFiber RTP port range (matching official juicejseconfig.cfg and sidhant-sipserver)
            // Port 52000-52200 is the designated local VoIP RTP port allowed through the ONT firewall.
            accCfg.getMediaConfig().getTransportConfig().setPort(52000);
            accCfg.getMediaConfig().getTransportConfig().setPortRange(200);
            accCfg.getMediaConfig().setStreamKaEnabled(true);
            accCfg.getMediaConfig().setLockCodecEnabled(false);
            // Router and device are on same subnet (192.168.29.x). Disable SDP NAT rewrite so PJSIP
            // does NOT insert a=X-nat:0 into SDP (which confuses ONT IBCF routing).
            accCfg.getNatConfig().setSdpNatRewriteUse(0);
            accCfg.getNatConfig().setMediaStunUse(0);
            accCfg.getNatConfig().setSipStunUse(0);

            pjsipAccount = new JioPjsipAccount(this);
            pjsipAccount.create(accCfg, true);

            Log.i(TAG, "PJSIP Account created, awaiting registration response...");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create PJSIP account: " + e.getMessage(), e);
            notifyRegistrationFailed(500, e.getMessage());
        }
    }

    public synchronized void unregisterAccount() {
        ensureThreadRegistered();
        if (pjsipAccount != null) {
            try {
                pjsipAccount.delete();
            } catch (Exception ignored) {}
            pjsipAccount = null;
        }
        isRegistered = false;
        notifyUnregistered();
    }

    /**
     * Dials an outgoing phone number via Jio SIP.
     */
    public synchronized void makeCall(String destinationNumber) {
        if (!isRegistered || currentAccount == null || pjsipAccount == null) {
            Log.e(TAG, "Cannot place call: Account not registered");
            notifyCallTerminated("Account Not Registered");
            return;
        }

        // Must run for custom UI, Android Telecom UI and gateway-initiated calls.
        if (!prepareCallAudio()) {
            notifyCallTerminated("Microphone permission or audio setup failed");
            return;
        }
        ensureThreadRegistered();

        String cleanNumber = formatDestinationNumber(destinationNumber);
        this.activeCallNumber = JioCallLogHelper.normalizeNumber(destinationNumber);
        this.isCallActive = true;

        String realm = currentAccount.getRealm();
        if (realm == null || realm.isEmpty()) {
            realm = currentAccount.getHomeDomain();
        }
        if (realm == null || realm.isEmpty()) {
            realm = "ko.wln.ims.jio.com";
        }

        // Standard JioFiber dial URI: <sip:NUMBER@REALM> matching sidhant-sipserver and sivatheja-bridge
        String sipUri = "<sip:" + cleanNumber + "@" + realm + ">";
        Log.i(TAG, "Placing native Jio VoIP call to: " + sipUri);

        try {
            if (activeCall != null) {
                final JioPjsipCall oldCall = activeCall;
                activeCall = null;
                try {
                    CallOpParam prm = new CallOpParam(true);
                    oldCall.hangup(prm);
                } catch (Exception ignored) {}
                mainHandler.postDelayed(() -> {
                    try {
                        oldCall.delete();
                    } catch (Exception ignored) {}
                }, 500);
            }

            activeCall = new JioPjsipCall(pjsipAccount, this);
            CallOpParam prm = new CallOpParam(true);

            // Configure single audio stream only, no video, no real-time text
            prm.getOpt().setAudioCount(1);
            prm.getOpt().setVideoCount(0);
            prm.getOpt().setTextCount(0);

            // P-Access-Network-Info (standard 802.11 network identity without emergency PSAPId)
            SipHeader paniHdr = new SipHeader();
            paniHdr.setHName("P-Access-Network-Info");
            paniHdr.setHValue("IEEE-802.11");
            prm.getTxOption().getHeaders().add(paniHdr);

            // P-Preferred-Identity (must exactly match From header public ID with +91)
            String fullPubId = currentAccount.getIdUri();
            if (fullPubId == null || fullPubId.isEmpty()) {
                fullPubId = "sip:+913331628760@" + realm;
            }
            if (!fullPubId.startsWith("<")) {
                fullPubId = "<" + fullPubId + ">";
            }
            SipHeader ppiHdr = new SipHeader();
            ppiHdr.setHName("P-Preferred-Identity");
            ppiHdr.setHValue(fullPubId);
            prm.getTxOption().getHeaders().add(ppiHdr);

            // NOTE: Do NOT add duplicate Supported headers or P-Preferred-Identity headers.
            // AccountConfig manages 100rel, timer, replaces, and outbound automatically.

            activeCall.makeCall(sipUri, prm);
            notifyCallConnecting();
        } catch (Exception e) {
            Log.e(TAG, "Failed to initiate native call: " + e.getMessage(), e);
            isCallActive = false;
            callAudioManager.stopCallAudio();
            notifyCallTerminated("Call Failed: " + e.getMessage());
        }
    }

    /**
     * Formats dialed numbers for JioFiber landline routing.
     * Jio landlines require national dialing format (leading 0 for mobile and STD, no +91).
     */
    public static String formatDestinationNumber(String destinationNumber) {
        if (destinationNumber == null) return "";
        String clean = destinationNumber.replaceAll("[^0-9+*#]", "");
        if (clean.isEmpty()) return "";

        // Strip leading +91 or +
        if (clean.startsWith("+91") && clean.length() > 3) {
            clean = clean.substring(3);
        } else if (clean.startsWith("+")) {
            clean = clean.substring(1);
        }

        // 10-digit mobile number starting with 6, 7, 8, or 9 -> prefix with 0
        if (clean.length() == 10 && clean.matches("^[6-9][0-9]{9}$")) {
            clean = "0" + clean;
        }
        // 10-digit Kolkata landline starting with 33 -> prefix with 0
        else if (clean.length() == 10 && clean.startsWith("33")) {
            clean = "0" + clean;
        }
        // 8-digit Kolkata local landline (e.g. 31628760) -> prefix with 033
        else if (clean.length() == 8 && clean.matches("^[2-9][0-9]{7}$")) {
            clean = "033" + clean;
        }
        // If 12 digits starting with 91 followed by 10-digit mobile -> 0XXXXXXXXXX
        else if (clean.length() == 12 && clean.startsWith("91") && clean.substring(2).matches("^[6-9][0-9]{9}$")) {
            clean = "0" + clean.substring(2);
        }

        return clean;
    }

    public synchronized void answerCall() {
        if (activeCall != null) {
            if (!prepareCallAudio()) {
                Log.e(TAG, "Cannot answer: microphone permission or audio setup failed");
                hangupCall(); // Never answer a call with known-inoperative microphone setup.
                return;
            }
            ensureThreadRegistered();
            try {
                CallOpParam prm = new CallOpParam(true);
                prm.getOpt().setAudioCount(1);
                prm.getOpt().setVideoCount(0);
                prm.getOpt().setTextCount(0);
                prm.setStatusCode(pjsip_status_code.PJSIP_SC_OK);
                activeCall.answer(prm);
                Log.i(TAG, "Incoming call answered");
            } catch (Exception e) {
                Log.e(TAG, "Error answering call: " + e.getMessage(), e);
            }
        }
    }

    public String getLocalIpAddress() {
        String targetHost = (currentAccount != null && currentAccount.getRegistrarHost() != null)
                ? currentAccount.getRegistrarHost()
                : "192.168.29.1";
        return getLocalIpAddress(targetHost);
    }

    public static String getLocalIpAddress(String targetHost) {
        if (targetHost == null || targetHost.isEmpty()) {
            targetHost = "192.168.29.1";
        }
        if (targetHost.startsWith("sip:")) targetHost = targetHost.substring(4);
        int colon = targetHost.indexOf(':');
        if (colon != -1) targetHost = targetHost.substring(0, colon);
        int semi = targetHost.indexOf(';');
        if (semi != -1) targetHost = targetHost.substring(0, semi);

        // 1. Kernel routing table lookup via UDP connect trick:
        // Connects a dummy UDP socket to the target host to let the OS kernel FIB routing
        // select the exact source IP and network interface (Wi-Fi, Ethernet, Cellular, or VPN like NetBird/WireGuard).
        // No actual packets are transmitted on the network.
        try {
            java.net.DatagramSocket socket = new java.net.DatagramSocket();
            socket.connect(java.net.InetAddress.getByName(targetHost), 5068);
            java.net.InetAddress localAddr = socket.getLocalAddress();
            socket.close();
            if (localAddr != null && !localAddr.isAnyLocalAddress() && !localAddr.isLoopbackAddress()) {
                String ip = localAddr.getHostAddress();
                if (ip != null && !ip.isEmpty() && !"0.0.0.0".equals(ip)) {
                    Log.i(TAG, "Kernel routing table resolved source IP: " + ip + " for destination " + targetHost);
                    return ip;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Kernel UDP routing probe to " + targetHost + " not available: " + e.getMessage());
        }

        // 2. Active network interface inspection fallback:
        // Prioritize VPN tunnel interfaces (wt, tun, vpn), followed by Wi-Fi (wlan) and Ethernet (eth).
        try {
            java.util.List<String> wifiOrEthIps = new java.util.ArrayList<>();
            java.util.List<String> otherIps = new java.util.ArrayList<>();

            for (java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                java.net.NetworkInterface intf = en.nextElement();
                if (!intf.isUp() || intf.isLoopback()) continue;
                String ifName = intf.getName() != null ? intf.getName().toLowerCase(Locale.US) : "";

                for (java.util.Enumeration<java.net.InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    java.net.InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof java.net.Inet4Address) {
                        String ip = inetAddress.getHostAddress();
                        if (ifName.contains("wt") || ifName.contains("tun") || ifName.contains("vpn")) {
                            Log.i(TAG, "Found active VPN tunnel interface IP: " + ip + " on " + ifName);
                            return ip;
                        } else if (ifName.contains("wlan") || ifName.contains("eth")) {
                            wifiOrEthIps.add(ip);
                        } else {
                            otherIps.add(ip);
                        }
                    }
                }
            }

            if (!wifiOrEthIps.isEmpty()) {
                return wifiOrEthIps.get(0);
            }
            if (!otherIps.isEmpty()) {
                return otherIps.get(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error enumerating network interfaces: " + e.getMessage());
        }

        return "";
    }

    public String getLocalWifiIpAddress() {
        return getLocalIpAddress();
    }

    public static String getLocalWifiMacAddress(Context context) {
        try {
            if (context != null) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null && wm.getConnectionInfo() != null) {
                    String mac = wm.getConnectionInfo().getMacAddress();
                    if (mac != null && !mac.isEmpty() && !mac.equals("02:00:00:00:00:00")) {
                        return mac.toLowerCase(Locale.US);
                    }
                }
            }
        } catch (Exception ignored) {}

        try {
            for (java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                java.net.NetworkInterface intf = en.nextElement();
                if ("wlan0".equalsIgnoreCase(intf.getName()) || intf.getName().startsWith("wlan")) {
                    byte[] macBytes = intf.getHardwareAddress();
                    if (macBytes != null && macBytes.length == 6) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < macBytes.length; i++) {
                            sb.append(String.format(Locale.US, "%02x%s", macBytes[i], (i < macBytes.length - 1) ? ":" : ""));
                        }
                        return sb.toString().toLowerCase(Locale.US);
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public synchronized void hangupCall() {
        if (activeCall != null) {
            ensureThreadRegistered();
            try {
                CallOpParam prm = new CallOpParam(true);
                prm.setStatusCode(pjsip_status_code.PJSIP_SC_DECLINE);
                activeCall.hangup(prm);
                Log.i(TAG, "Call hangup signaled");
            } catch (Exception e) {
                Log.e(TAG, "Error hanging up call: " + e.getMessage(), e);
            }
        }
    }

    public synchronized void sendDtmf(String digits) {
        if (activeCall != null && digits != null && !digits.isEmpty()) {
            ensureThreadRegistered();
            try {
                activeCall.dialDtmf(digits);
                Log.i(TAG, "Sent DTMF: " + digits);
            } catch (Exception e) {
                Log.e(TAG, "Error sending DTMF: " + e.getMessage(), e);
            }
        }
    }

    public Context getContext() {
        return context;
    }

    public void connectActiveCallMedia() {
        if (activeCall != null) {
            ensureThreadRegistered();
            activeCall.connectMedia();
        }
    }

    // --- Internal Callbacks from JioPjsipAccount & JioPjsipCall ---

    public void handleRegState(int code, String reason) {
        if (code == 200) {
            isRegistered = true;
            notifyRegistered();
        } else if (code >= 300) {
            isRegistered = false;
            notifyRegistrationFailed(code, reason);
        } else if (code == 0) {
            notifyRegistrationProgress();
        }
    }

    public void handleIncomingCall(JioPjsipCall call) {
        this.activeCall = call;
        this.isCallActive = true;
        try {
            CallInfo ci = call.getInfo();
            String remoteUri = ci.getRemoteUri();
            this.activeCallNumber = JioCallLogHelper.normalizeNumber(extractNumberFromUri(remoteUri));
            notifyIncomingCall(activeCallNumber, activeCallNumber);
        } catch (Exception e) {
            Log.e(TAG, "Error handling incoming call: " + e.getMessage(), e);
        }
    }

    public void handleCallState(JioPjsipCall call, int state, String reason, String remoteUri) {
        if (activeCall != null && call != activeCall) {
            Log.d(TAG, "Ignoring call state (" + state + ") from superseded call");
            return;
        }

        if (state == pjsip_inv_state.PJSIP_INV_STATE_CALLING) {
            notifyCallConnecting();
        } else if (state == pjsip_inv_state.PJSIP_INV_STATE_EARLY) {
            notifyCallRinging();
            if (call != null) {
                call.connectMedia();
            }
        } else if (state == pjsip_inv_state.PJSIP_INV_STATE_CONNECTING) {
            notifyCallConnecting();
        } else if (state == pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED) {
            isCallActive = true;
            if (call != null) {
                call.connectMedia();
            }
            mainHandler.postDelayed(() -> {
                if (activeCall != null) activeCall.connectMedia();
            }, 300);
            mainHandler.postDelayed(() -> {
                if (activeCall != null) activeCall.connectMedia();
            }, 800);
            notifyCallEstablished();
        } else if (state == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
            isCallActive = false;
            if (call != null) {
                call.disconnectMedia();
            }
            callAudioManager.stopCallAudio();
            notifyCallTerminated(reason != null && !reason.isEmpty() ? reason : "Call Terminated");
            if (activeCall == call) {
                activeCall = null;
            }
            // Safely delete native Call SWIG wrapper asynchronously to avoid dangling callbacks
            mainHandler.postDelayed(() -> {
                try {
                    call.delete();
                } catch (Exception ignored) {}
            }, 500);
        }
    }

    private String extractNumberFromUri(String uri) {
        if (uri == null) return "Unknown";
        int start = uri.indexOf(':');
        int end = uri.indexOf('@');
        if (start != -1 && end != -1 && end > start) {
            return uri.substring(start + 1, end);
        }
        return uri;
    }

    // --- Listener Notifications ---

    private void notifyRegistrationProgress() {
        mainHandler.post(() -> {
            synchronized (regListeners) {
                for (JioRegistrationListener l : regListeners) l.onRegistrationProgress();
            }
        });
    }

    private void notifyRegistered() {
        mainHandler.post(() -> {
            synchronized (regListeners) {
                for (JioRegistrationListener l : regListeners) l.onRegistered();
            }
        });
    }

    private void notifyRegistrationFailed(int code, String reason) {
        mainHandler.post(() -> {
            synchronized (regListeners) {
                for (JioRegistrationListener l : regListeners) l.onRegistrationFailed(code, reason);
            }
        });
    }

    private void notifyUnregistered() {
        mainHandler.post(() -> {
            synchronized (regListeners) {
                for (JioRegistrationListener l : regListeners) l.onUnregistered();
            }
        });
    }

    private void notifyIncomingCall(String number, String name) {
        mainHandler.post(() -> {
            synchronized (callListeners) {
                for (JioCallListener l : callListeners) l.onCallIncoming(number, name);
            }
        });
    }

    private void notifyCallConnecting() {
        mainHandler.post(() -> {
            synchronized (callListeners) {
                for (JioCallListener l : callListeners) l.onCallConnecting();
            }
        });
    }

    private void notifyCallRinging() {
        mainHandler.post(() -> {
            synchronized (callListeners) {
                for (JioCallListener l : callListeners) l.onCallRinging();
            }
        });
    }

    private void notifyCallEstablished() {
        mainHandler.post(() -> {
            synchronized (callListeners) {
                for (JioCallListener l : callListeners) l.onCallEstablished();
            }
        });
    }

    private void notifyCallTerminated(String reason) {
        mainHandler.post(() -> {
            synchronized (callListeners) {
                for (JioCallListener l : callListeners) l.onCallTerminated(reason);
            }
        });
    }

    public JioAccount getCurrentAccount() {
        return currentAccount;
    }

    public boolean isRegistered() {
        return isRegistered;
    }

    public boolean isCallActive() {
        return isCallActive;
    }

    public String getActiveCallNumber() {
        return activeCallNumber;
    }

    private boolean isSpeakerphone = false;

    public void setSpeakerphoneActive(boolean isSpeaker) {
        this.isSpeakerphone = isSpeaker;
        if (activeCall != null) {
            activeCall.applyVolumeBoost(isSpeaker);
        }
    }

    public boolean isSpeakerphoneActive() {
        return isSpeakerphone;
    }
}
