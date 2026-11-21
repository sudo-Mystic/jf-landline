package com.jio.calling.audio;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.PowerManager;
import android.util.Log;

/**
 * Manages audio routing, volume, microphone mute, speakerphone,
 * and proximity-based screen locking during calls.
 */
public class JioAudioManager implements SensorEventListener {
    private static final String TAG = "JioAudioManager";

    private final Context context;
    private final AudioManager audioManager;
    private final SensorManager sensorManager;
    private final Sensor proximitySensor;
    private PowerManager.WakeLock proximityWakeLock;

    private boolean isSpeakerphoneOn = false;
    private boolean isMuted = false;
    private int savedAudioMode = AudioManager.MODE_NORMAL;
    private boolean callAudioStarted = false;

    public interface ProximityListener {
        void onProximityChanged(boolean isNear);
    }

    private ProximityListener proximityListener;

    public JioAudioManager(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.sensorManager = (SensorManager) this.context.getSystemService(Context.SENSOR_SERVICE);
        this.proximitySensor = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) : null;

        initProximityWakeLock();
    }

    @SuppressWarnings("deprecation")
    private void initProximityWakeLock() {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                // PROXIMITY_SCREEN_OFF_WAKE_LOCK is available in KitKat API 19 (value 32)
                int proximityFlag = 0x00000020;
                proximityWakeLock = pm.newWakeLock(proximityFlag, "jio:proximity_screen_off");
                proximityWakeLock.setReferenceCounted(false);
            }
        } catch (Exception e) {
            Log.w(TAG, "Proximity wake lock not supported: " + e.getMessage());
        }
    }

    public void setProximityListener(ProximityListener listener) {
        this.proximityListener = listener;
    }

    /**
     * Prepares audio hardware for an active VoIP voice call.
     */
    public synchronized boolean startCallAudio() {
        if (audioManager == null) return false;
        if (callAudioStarted) return true;

        savedAudioMode = audioManager.getMode();

        // 1. Request transient audio focus for VoIP voice call stream (STREAM_VOICE_CALL)
        // PJSIP android_jni_dev creates AudioTrack specifically on STREAM_VOICE_CALL
        try {
            int focusResult = audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            );
            Log.i(TAG, "Audio focus requested (STREAM_VOICE_CALL result=" + focusResult + ")");
        } catch (Exception e) {
            Log.w(TAG, "Could not request audio focus: " + e.getMessage());
        }

        // 2. Ensure voice call stream volume is set to maximum
        try {
            int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVol, 0);
        } catch (Exception e) {
            Log.w(TAG, "Could not set initial call volume: " + e.getMessage());
        }

        // 3. Put audio hardware into communication mode and default to earpiece
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        } catch (Exception e) {
            Log.e(TAG, "Could not enter MODE_IN_COMMUNICATION", e);
            try { audioManager.abandonAudioFocus(null); } catch (Exception ignored) {}
            return false;
        }

        callAudioStarted = true;
        setSpeakerphone(false);
        setMute(false);

        // Enable proximity sensor listener
        if (sensorManager != null && proximitySensor != null) {
            sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

        Log.i(TAG, "Audio configured for active call (STREAM_VOICE_CALL / MODE_IN_COMMUNICATION / Earpiece)");
        return true;
    }

    /**
     * Applies or re-applies current routing and volumes.
     */
    public void applyCallAudioRouting() {
        if (audioManager == null) return;
        try {
            // Keep phone in communication mode for VoIP audio processing and acoustic echo cancellation
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(isSpeakerphoneOn);

            // Set max volume for STREAM_VOICE_CALL
            int maxVoiceVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoiceVol, 0);

            // Set max volume for STREAM_MUSIC so PJSIP track playback remains fully audible on both routes
            int maxMusicVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusicVol, 0);

            int maxSysVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_SYSTEM);
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, maxSysVol, 0);

            Log.i(TAG, "Applied call audio routing: speaker=" + isSpeakerphoneOn +
                    ", voiceVol=" + maxVoiceVol + ", musicVol=" + maxMusicVol);
        } catch (Exception e) {
            Log.w(TAG, "Error applying audio routing: " + e.getMessage());
        }
    }

    /**
     * Restores normal audio hardware settings when a call finishes.
     */
    public synchronized void stopCallAudio() {
        if (audioManager == null || !callAudioStarted) return;
        callAudioStarted = false;

        if (proximityWakeLock != null && proximityWakeLock.isHeld()) {
            try {
                proximityWakeLock.release();
            } catch (Exception ignored) {}
        }

        if (sensorManager != null && proximitySensor != null) {
            sensorManager.unregisterListener(this);
        }

        try {
            audioManager.setSpeakerphoneOn(false);
            audioManager.setMicrophoneMute(false);
            audioManager.setMode(savedAudioMode);
        } catch (Exception e) {
            Log.w(TAG, "Could not restore previous audio mode: " + e.getMessage());
        }

        // Restore ring, music, and system stream volumes so the phone sounds remain fully audible
        try {
            int maxRing = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING);
            audioManager.setStreamVolume(AudioManager.STREAM_RING, maxRing, 0);
            int maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (int) (maxMusic * 0.85f), 0);
            int maxSys = audioManager.getStreamMaxVolume(AudioManager.STREAM_SYSTEM);
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, maxSys, 0);
            int maxNotif = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, maxNotif, 0);
        } catch (Exception e) {
            Log.w(TAG, "Error restoring audio stream volumes: " + e.getMessage());
        }

        try {
            audioManager.abandonAudioFocus(null);
            Log.i(TAG, "Audio focus abandoned");
        } catch (Exception ignored) {}

        Log.i(TAG, "Audio restored to normal mode with speaker routing");
    }

    public void setSpeakerphone(boolean on) {
        if (audioManager == null) return;
        this.isSpeakerphoneOn = on;
        applyCallAudioRouting();

        try {
            com.jio.calling.sip.JioSipManager.getInstance(context).setSpeakerphoneActive(on);
        } catch (Exception ignored) {}

        // If speakerphone is on, do not turn screen off on ear proximity
        if (proximityWakeLock != null) {
            if (on && proximityWakeLock.isHeld()) {
                proximityWakeLock.release();
            } else if (!on && !proximityWakeLock.isHeld()) {
                // Maximum 2 hours safety timeout so lock never leaks if call drops unexpectedly
                proximityWakeLock.acquire(2 * 60 * 60 * 1000L);
            }
        }
    }

    public boolean isSpeakerphoneOn() {
        return isSpeakerphoneOn;
    }

    public void toggleSpeakerphone() {
        setSpeakerphone(!isSpeakerphoneOn);
    }

    public void setMute(boolean mute) {
        if (audioManager == null) return;
        this.isMuted = mute;
        audioManager.setMicrophoneMute(mute);
    }

    public boolean isMuted() {
        return isMuted;
    }

    public void toggleMute() {
        setMute(!isMuted);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            float distance = event.values[0];
            float maxRange = event.sensor.getMaximumRange();
            boolean isNear = distance < maxRange && distance < 5.0f;

            if (proximityListener != null) {
                proximityListener.onProximityChanged(isNear);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}

