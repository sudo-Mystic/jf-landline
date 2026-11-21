package com.jio.calling.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.CallLog;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.jio.calling.R;
import com.jio.calling.audio.JioAudioManager;
import com.jio.calling.sip.JioCallListener;
import com.jio.calling.sip.JioSipManager;
import com.jio.calling.util.JioCallLogHelper;

/**
 * Active in-call screen for JioFiber landline calls.
 * Displays on top of the lockscreen / stock dialer as a standalone overlay.
 * Styled in the clean stock Android KitKat phone UI aesthetic.
 */
public class InCallActivity extends Activity implements JioCallListener {
    public static final String EXTRA_NUMBER = "extra_number";
    public static final String EXTRA_IS_INCOMING = "extra_is_incoming";

    private TextView tvCallNumber;
    private TextView tvCallStatus;
    private Chronometer chronometer;
    private View avatarContainer;
    private View dtmfGrid;

    private ImageButton btnMute;
    private TextView tvMuteLabel;
    private ImageButton btnSpeaker;
    private TextView tvSpeakerLabel;
    private ImageButton btnKeypad;
    private TextView tvKeypadLabel;
    private ImageButton btnEndCall;

    private JioSipManager sipManager;
    private JioAudioManager audioManager;
    private String destinationNumber;
    private boolean isKeypadVisible = false;

    private boolean isIncoming = false;
    private long callStartMillis = 0;
    private long callConnectedElapsedRealtime = 0;
    private boolean hasLoggedCall = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Display on top of lockscreen on Android KitKat
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        setContentView(R.layout.activity_in_call);
        setVolumeControlStream(android.media.AudioManager.STREAM_VOICE_CALL);

        tvCallNumber = findViewById(R.id.tv_call_number);
        tvCallStatus = findViewById(R.id.tv_call_status);
        chronometer = findViewById(R.id.call_chronometer);
        avatarContainer = findViewById(R.id.avatar_container);
        dtmfGrid = findViewById(R.id.dtmf_grid);

        btnMute = findViewById(R.id.btn_mute);
        tvMuteLabel = findViewById(R.id.tv_mute_label);
        btnSpeaker = findViewById(R.id.btn_speaker);
        tvSpeakerLabel = findViewById(R.id.tv_speaker_label);
        btnKeypad = findViewById(R.id.btn_keypad);
        tvKeypadLabel = findViewById(R.id.tv_keypad_label);
        btnEndCall = findViewById(R.id.btn_end_call);

        callStartMillis = System.currentTimeMillis();
        String rawNumber = getIntent().getStringExtra(EXTRA_NUMBER);
        destinationNumber = JioCallLogHelper.normalizeNumber(rawNumber);
        if (destinationNumber == null || destinationNumber.isEmpty()) {
            destinationNumber = "Unknown";
        }
        isIncoming = getIntent().getBooleanExtra(EXTRA_IS_INCOMING, false);

        tvCallNumber.setText(destinationNumber);
        tvCallStatus.setText(isIncoming ? "Connecting..." : "Calling...");

        sipManager = JioSipManager.getInstance(this);
        sipManager.addCallListener(this);

        // Borrow the call-scoped audio controller; SIP/Telecom owns its lifecycle.
        audioManager = sipManager.getCallAudioManager();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 102);
            }
        }

        setupControls();
        setupDtmfPad();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null) {
            String num = intent.getStringExtra(EXTRA_NUMBER);
            if (num != null && !num.isEmpty()) {
                destinationNumber = JioCallLogHelper.normalizeNumber(num);
                tvCallNumber.setText(destinationNumber);
            }
            isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, isIncoming);
        }
        tvCallStatus.removeCallbacks(finishRunnable);
        btnEndCall.setEnabled(true);
        tvCallStatus.setText(isIncoming ? "Connecting..." : "Calling...");
    }

    private void setupControls() {
        updateMuteButton();
        updateSpeakerButton();

        btnMute.setOnClickListener(v -> {
            if (audioManager != null) {
                audioManager.toggleMute();
                updateMuteButton();
            }
        });

        btnSpeaker.setOnClickListener(v -> {
            if (audioManager != null) {
                audioManager.toggleSpeakerphone();
                updateSpeakerButton();
            }
        });

        btnKeypad.setOnClickListener(v -> {
            toggleKeypad(!isKeypadVisible);
        });

        btnEndCall.setOnClickListener(v -> {
            tvCallStatus.setText("Ending call...");
            btnEndCall.setEnabled(false);
            if (sipManager != null) {
                sipManager.hangupCall();
            }
            finishCall();
        });
    }

    private void toggleKeypad(boolean show) {
        isKeypadVisible = show;
        if (dtmfGrid != null) {
            dtmfGrid.setVisibility(isKeypadVisible ? View.VISIBLE : View.GONE);
        }
        if (avatarContainer != null) {
            avatarContainer.setVisibility(isKeypadVisible ? View.GONE : View.VISIBLE);
        }
        if (btnKeypad != null) {
            btnKeypad.setBackgroundResource(isKeypadVisible ? R.drawable.bg_action_circle_active : R.drawable.bg_action_circle);
        }
        if (tvKeypadLabel != null) {
            tvKeypadLabel.setText(isKeypadVisible ? "Hide" : "Dialpad");
            tvKeypadLabel.setTextColor(getResources().getColor(isKeypadVisible ? R.color.accent : R.color.text_secondary));
        }
    }

    private void updateMuteButton() {
        if (audioManager == null || btnMute == null) return;
        boolean muted = audioManager.isMuted();
        btnMute.setBackgroundResource(muted ? R.drawable.bg_action_circle_red : R.drawable.bg_action_circle);
        if (tvMuteLabel != null) {
            tvMuteLabel.setText(muted ? "Muted" : "Mute");
            tvMuteLabel.setTextColor(getResources().getColor(muted ? R.color.call_red : R.color.text_secondary));
        }
    }

    private void updateSpeakerButton() {
        if (audioManager == null || btnSpeaker == null) return;
        boolean speaker = audioManager.isSpeakerphoneOn();
        btnSpeaker.setBackgroundResource(speaker ? R.drawable.bg_action_circle_green : R.drawable.bg_action_circle);
        if (tvSpeakerLabel != null) {
            tvSpeakerLabel.setText(speaker ? "Speaker ON" : "Speaker");
            tvSpeakerLabel.setTextColor(getResources().getColor(speaker ? R.color.call_green : R.color.text_secondary));
        }
    }

    private void setupDtmfPad() {
        int[] buttonIds = {
            R.id.btn_dtmf_1, R.id.btn_dtmf_2, R.id.btn_dtmf_3,
            R.id.btn_dtmf_4, R.id.btn_dtmf_5, R.id.btn_dtmf_6,
            R.id.btn_dtmf_7, R.id.btn_dtmf_8, R.id.btn_dtmf_9,
            R.id.btn_dtmf_star, R.id.btn_dtmf_0, R.id.btn_dtmf_hash
        };

        View.OnClickListener dtmfClick = v -> {
            Button b = (Button) v;
            String digit = b.getText().toString();
            if (sipManager != null) {
                sipManager.sendDtmf(digit);
            }
        };

        for (int id : buttonIds) {
            Button b = findViewById(id);
            if (b != null) {
                b.setOnClickListener(dtmfClick);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (isKeypadVisible) {
            toggleKeypad(false);
            return;
        }
        // Move task to back during call so stock dialer / previous app is revealed
        // without inadvertently opening MainActivity
        moveTaskToBack(true);
    }

    private void finishCall() {
        if (!hasLoggedCall) {
            hasLoggedCall = true;
            long durationSec = 0;
            if (callConnectedElapsedRealtime > 0) {
                durationSec = (SystemClock.elapsedRealtime() - callConnectedElapsedRealtime) / 1000;
            }
            int callType = isIncoming ? CallLog.Calls.INCOMING_TYPE : CallLog.Calls.OUTGOING_TYPE;
            JioCallLogHelper.logCall(this, destinationNumber, callType, callStartMillis, durationSec);
        }

        if (chronometer != null) {
            chronometer.stop();
        }
        // Audio ends when SIP reports DISCONNECTED, not when an Activity closes.
        finish();
    }

    private final Runnable finishRunnable = this::finishCall;

    // --- JioCallListener ---
    @Override
    public void onCallConnecting() {
        runOnUiThread(() -> {
            tvCallStatus.removeCallbacks(finishRunnable);
            btnEndCall.setEnabled(true);
            tvCallStatus.setText(isIncoming ? "Connecting..." : "Calling...");
        });
    }

    @Override
    public void onCallRinging() {
        runOnUiThread(() -> {
            tvCallStatus.removeCallbacks(finishRunnable);
            btnEndCall.setEnabled(true);
            tvCallStatus.setText("Ringing...");
        });
    }

    @Override
    public void onCallEstablished() {
        runOnUiThread(() -> {
            tvCallStatus.removeCallbacks(finishRunnable);
            btnEndCall.setEnabled(true);
            tvCallStatus.setText("Connected");
            callConnectedElapsedRealtime = SystemClock.elapsedRealtime();
            chronometer.setBase(callConnectedElapsedRealtime);
            chronometer.setVisibility(View.VISIBLE);
            chronometer.start();
            if (audioManager != null) {
                audioManager.applyCallAudioRouting();
            }
            if (sipManager != null) {
                sipManager.connectActiveCallMedia();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (audioManager != null && sipManager != null && sipManager.isCallActive()) {
            audioManager.applyCallAudioRouting();
            updateSpeakerButton();
            updateMuteButton();
        }
        if (sipManager != null) {
            sipManager.connectActiveCallMedia();
        }
    }

    @Override
    public void onCallTerminated(String reason) {
        runOnUiThread(() -> {
            if (sipManager != null && sipManager.isCallActive()) {
                return;
            }
            tvCallStatus.setText(reason != null ? reason : "Call Ended");
            btnEndCall.setEnabled(false);
            tvCallStatus.removeCallbacks(finishRunnable);
            tvCallStatus.postDelayed(finishRunnable, 1200);
        });
    }

    @Override public void onCallIncoming(String num, String name) {}

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!hasLoggedCall) {
            hasLoggedCall = true;
            long durationSec = 0;
            if (callConnectedElapsedRealtime > 0) {
                durationSec = (SystemClock.elapsedRealtime() - callConnectedElapsedRealtime) / 1000;
            }
            int callType = isIncoming ? CallLog.Calls.INCOMING_TYPE : CallLog.Calls.OUTGOING_TYPE;
            JioCallLogHelper.logCall(this, destinationNumber, callType, callStartMillis, durationSec);
        }
        if (sipManager != null) {
            sipManager.removeCallListener(this);
        }
        // Audio lifecycle is owned by JioSipManager, not by this Activity.
    }
}
