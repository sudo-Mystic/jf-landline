package com.jio.calling.ui;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

import com.jio.calling.R;
import com.jio.calling.gateway.GatewayAuthManager;
import com.jio.calling.model.JioAccount;
import com.jio.calling.provisioning.JioAccountStorage;
import com.jio.calling.service.JioSipService;
import com.jio.calling.sip.JioRegistrationListener;
import com.jio.calling.sip.JioSipManager;
import com.jio.calling.telecom.JioTelecomManager;
import com.jio.calling.util.AppPreferences;

/**
 * Main dashboard & configuration hub for Jio Landline.
 * Provides controls for:
 * - Real-time SIP registration & line status
 * - Stock dialer call interception toggle
 * - Android Telecom Calling Accounts integration & configuration
 * - Remote companion gateway pairing PIN & status
 * - Device and network diagnostics
 */
public class MainActivity extends AppCompatActivity implements JioRegistrationListener {
    private static final String TAG = "MainActivity";

    // Header Status Pill
    private LinearLayout llStatusPill;
    private ImageView ivStatusDot;
    private TextView tvStatusPill;

    // Line Status
    private TextView tvPhoneNumber;
    private Button btnRefresh;
    private TextView tvRouterHost;
    private TextView tvSipPort;
    private TextView tvRealmInfo;
    private TextView tvWifiSsid;
    private TextView tvStatusDesc;

    // Call Interception & Routing
    private SwitchCompat switchIntercept;
    private TextView tvInterceptStatus;

    // Android Telecom
    private LinearLayout cardTelecomSection;
    private SwitchCompat switchTelecom;
    private SwitchCompat switchTelecomUi;
    private Button btnOpenTelecomSettings;
    private TextView tvTelecomStatus;

    // Companion Gateway
    private TextView tvGatewayInfo;
    private TextView tvGatewayPin;
    private Button btnNewPin;
    private Button btnCopyPin;

    // Diagnostics
    private TextView tvDiagIp;
    private TextView tvDiagCodec;
    private TextView tvDiagMac;
    private TextView tvDiagEngine;
    private TextView tvDiagAndroid;
    private TextView tvDiagKeepalive;

    private JioSipManager sipManager;
    private GatewayAuthManager authManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Turn screen on and display smoothly on dedicated landline phone
        getWindow().addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        setContentView(R.layout.activity_main);

        initViews();
        setupManagers();
        setupListeners();

        checkAndRequestPermissions();

        // Start background 24/7 service
        Intent serviceIntent = new Intent(this, JioSipService.class);
        serviceIntent.setAction(JioSipService.ACTION_START);
        ContextCompat.startForegroundService(this, serviceIntent);

        updateAccountDisplay();
    }

    private static final int REQUEST_PERMISSIONS_CODE = 101;

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> permissions = new ArrayList<>();
            permissions.add(Manifest.permission.RECORD_AUDIO);
            permissions.add(Manifest.permission.READ_PHONE_STATE);
            permissions.add(Manifest.permission.CALL_PHONE);
            permissions.add(Manifest.permission.WRITE_CALL_LOG);

            if (Build.VERSION.SDK_INT >= 33) {
                permissions.add("android.permission.POST_NOTIFICATIONS");
            }
            if (Build.VERSION.SDK_INT >= 30) {
                permissions.add("android.permission.READ_PHONE_NUMBERS");
            }
            if (Build.VERSION.SDK_INT <= 28) {
                permissions.add(Manifest.permission.PROCESS_OUTGOING_CALLS);
            }

            List<String> permissionsToRequest = new ArrayList<>();
            for (String perm : permissions) {
                if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(perm);
                }
            }

            if (!permissionsToRequest.isEmpty()) {
                ActivityCompat.requestPermissions(
                        this,
                        permissionsToRequest.toArray(new String[0]),
                        REQUEST_PERMISSIONS_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            Log.i(TAG, "Permissions updated by user.");
            if (JioTelecomManager.isSupported() && AppPreferences.isTelecomEnabled(this)) {
                JioTelecomManager.registerPhoneAccount(this);
                updateTelecomStatusText();
            }
        }
    }

    private void initViews() {
        llStatusPill = findViewById(R.id.ll_status_pill);
        ivStatusDot = findViewById(R.id.iv_status_dot);
        tvStatusPill = findViewById(R.id.tv_status_pill);

        tvPhoneNumber = findViewById(R.id.tv_phone_number);
        btnRefresh = findViewById(R.id.btn_refresh);
        tvRouterHost = findViewById(R.id.tv_router_host);
        tvSipPort = findViewById(R.id.tv_sip_port);
        tvRealmInfo = findViewById(R.id.tv_realm_info);
        tvWifiSsid = findViewById(R.id.tv_wifi_ssid);
        tvStatusDesc = findViewById(R.id.tv_status_desc);

        switchIntercept = findViewById(R.id.switch_intercept);
        tvInterceptStatus = findViewById(R.id.tv_intercept_status);

        cardTelecomSection = findViewById(R.id.card_telecom_section);
        switchTelecom = findViewById(R.id.switch_telecom);
        switchTelecomUi = findViewById(R.id.switch_telecom_ui);
        btnOpenTelecomSettings = findViewById(R.id.btn_open_telecom_settings);
        tvTelecomStatus = findViewById(R.id.tv_telecom_status);

        tvGatewayInfo = findViewById(R.id.tv_gateway_info);
        tvGatewayPin = findViewById(R.id.tv_gateway_pin);
        btnNewPin = findViewById(R.id.btn_new_pin);
        btnCopyPin = findViewById(R.id.btn_copy_pin);

        tvDiagIp = findViewById(R.id.tv_diag_ip);
        tvDiagCodec = findViewById(R.id.tv_diag_codec);
        tvDiagMac = findViewById(R.id.tv_diag_mac);
        tvDiagEngine = findViewById(R.id.tv_diag_engine);
        tvDiagAndroid = findViewById(R.id.tv_diag_android);
        tvDiagKeepalive = findViewById(R.id.tv_diag_keepalive);
    }

    private void setupManagers() {
        sipManager = JioSipManager.getInstance(this);
        sipManager.addRegistrationListener(this);
        authManager = GatewayAuthManager.getInstance(this);
    }

    private void setupListeners() {
        // --- 1. Stock Dialer Interception (Enabled by default on < Android 6, disabled
        // on Android 6+) ---
        boolean isInterceptEnabled = AppPreferences.isCallInterceptionEnabled(this);
        switchIntercept.setChecked(isInterceptEnabled);
        updateInterceptStatusText(isInterceptEnabled);

        switchIntercept.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppPreferences.setCallInterceptionEnabled(this, isChecked);
            updateInterceptStatusText(isChecked);
            String msg = isChecked
                    ? "Stock dialer call interception enabled"
                    : "Stock dialer call interception disabled";
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });

        // --- 2. Android Telecom Integration ---
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Android < 6.0: Hide the entire section of Android Telecom integration
            cardTelecomSection.setVisibility(View.GONE);
        } else {
            // Android 6.0+: Keep Android Telecom section visible
            cardTelecomSection.setVisibility(View.VISIBLE);

            boolean isTelecomEnabled = AppPreferences.isTelecomEnabled(this);
            switchTelecom.setChecked(isTelecomEnabled);

            boolean isTelecomUi = AppPreferences.isUseTelecomUiEnabled(this);
            switchTelecomUi.setChecked(isTelecomUi);
            switchTelecomUi.setEnabled(isTelecomEnabled);

            switchTelecom.setOnCheckedChangeListener((buttonView, isChecked) -> {
                AppPreferences.setTelecomEnabled(this, isChecked);
                switchTelecomUi.setEnabled(isChecked);
                if (isChecked) {
                    JioTelecomManager.registerPhoneAccount(this);
                    Toast.makeText(this, "Registered with Android Telecom", Toast.LENGTH_SHORT).show();
                } else {
                    JioTelecomManager.unregisterPhoneAccount(this);
                    Toast.makeText(this, "Unregistered from Android Telecom", Toast.LENGTH_SHORT).show();
                }
                updateTelecomStatusText();
            });

            switchTelecomUi.setOnCheckedChangeListener((buttonView, isChecked) -> {
                AppPreferences.setUseTelecomUiEnabled(this, isChecked);
                String msg = isChecked
                        ? "Using Android System In-Call UI"
                        : "Using App Custom Call Screen";
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            });

            btnOpenTelecomSettings.setOnClickListener(v -> {
                boolean opened = JioTelecomManager.openCallingAccountsSettings(this);
                if (!opened) {
                    Toast.makeText(this, "Could not open Calling Accounts settings directly", Toast.LENGTH_SHORT)
                            .show();
                }
            });

            updateTelecomStatusText();
        }

        // --- 3. Refresh Credentials / Router Sync ---
        btnRefresh.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Re-provision landline?")
                .setMessage("This clears saved Jio credentials and discovers them again from your router. Use it only when registration needs repair.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (dialog, which) -> {
                    Toast.makeText(this, "Re-discovering router and credentials...", Toast.LENGTH_SHORT).show();
                    JioAccountStorage.clear(this);
                    Intent refreshIntent = new Intent(this, JioSipService.class);
                    refreshIntent.setAction(JioSipService.ACTION_START);
                    ContextCompat.startForegroundService(this, refreshIntent);
                })
                .show());

        // --- 4. Companion PIN Management ---
        btnNewPin.setOnClickListener(v -> {
            String newPin = authManager.regeneratePin();
            tvGatewayPin.setText("PIN: " + newPin);
            Toast.makeText(this, "New PIN: " + newPin, Toast.LENGTH_SHORT).show();
        });

        btnCopyPin.setOnClickListener(v -> {
            String pin = authManager.getPin();
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                ClipData clip = ClipData.newPlainText("Companion PIN", pin);
                cm.setPrimaryClip(clip);
                Toast.makeText(this, "PIN copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateInterceptStatusText(boolean isEnabled) {
        if (isEnabled) {
            tvInterceptStatus.setText("Active: Calls placed in phone dialer will route via Jio Landline");
            tvInterceptStatus.setTextColor(getResources().getColor(R.color.call_green));
        } else {
            tvInterceptStatus.setText("Disabled: Outgoing calls will proceed normally through cellular SIM");
            tvInterceptStatus.setTextColor(getResources().getColor(R.color.status_amber));
        }
    }

    private void updateTelecomStatusText() {
        if (!JioTelecomManager.isSupported())
            return;

        boolean isRegistered = JioTelecomManager.isPhoneAccountRegistered(this);
        boolean isEnabled = JioTelecomManager.isPhoneAccountEnabled(this);

        if (isEnabled) {
            tvTelecomStatus.setText("Status: Calling Account Active in Android Settings");
            tvTelecomStatus.setTextColor(getResources().getColor(R.color.call_green));
        } else if (isRegistered) {
            tvTelecomStatus.setText("Status: Registered. (Tap button to enable in Settings)");
            tvTelecomStatus.setTextColor(getResources().getColor(R.color.status_amber));
        } else {
            tvTelecomStatus.setText("Status: Not Registered with TelecomManager");
            tvTelecomStatus.setTextColor(getResources().getColor(R.color.text_secondary));
        }
    }

    private void updateAccountDisplay() {
        JioAccount account = JioAccountStorage.load(this);
        boolean isReg = sipManager != null && sipManager.isRegistered();

        if (account != null && account.isValid()) {
            String num = account.getMsisdn();
            if (num != null && num.length() == 10) {
                // Format: 033-31628760 or similar
                tvPhoneNumber.setText(num.substring(0, 3) + "-" + num.substring(3));
            } else {
                tvPhoneNumber.setText(num != null ? num : "Active Line");
            }
            tvRouterHost.setText("Router: " + account.getProxyHost());
            tvRealmInfo.setText("Realm: " + (account.getRealm() != null ? account.getRealm() : "ko.wln.ims.jio.com"));
        } else {
            tvPhoneNumber.setText("Searching Router...");
            tvRouterHost.setText("Router: Detecting...");
            tvRealmInfo.setText("Realm: Jio IMS");
        }

        // Live Registration status
        if (isReg) {
            llStatusPill.setBackgroundResource(R.drawable.bg_status_pill_green);
            ivStatusDot.setImageResource(R.drawable.ic_dot_green);
            tvStatusPill.setText("ONLINE");
            tvStatusPill.setTextColor(getResources().getColor(R.color.call_green));
            tvStatusDesc.setText("Line ready for calls");
            tvStatusDesc.setTextColor(getResources().getColor(R.color.call_accent));
        } else {
            llStatusPill.setBackgroundResource(R.drawable.bg_status_pill_amber);
            ivStatusDot.setImageResource(R.drawable.ic_dot_amber);
            tvStatusPill.setText("CONNECTING");
            tvStatusPill.setTextColor(getResources().getColor(R.color.status_amber));
            tvStatusDesc.setText("Connecting to Jio router…");
            tvStatusDesc.setTextColor(getResources().getColor(R.color.on_dark_warning));
        }

        // Companion Gateway display
        if (authManager != null) {
            String ip = (sipManager != null) ? sipManager.getLocalWifiIpAddress() : "";
            if (ip == null || ip.isEmpty()) {
                ip = "192.168.29.X";
            }
            tvGatewayInfo.setText("Companion Gateway: " + ip + ":8088");
            tvGatewayPin.setText("PIN: " + authManager.getPin());
            tvDiagIp.setText("Local IP: " + ip);
        }

        // Diagnostics
        if (account != null && account.getVirtualMac() != null && !account.getVirtualMac().isEmpty()) {
            tvDiagMac.setText("MAC: " + account.getVirtualMac().toUpperCase());
        }
        tvDiagAndroid.setText("OS: Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");

        if (JioTelecomManager.isSupported()) {
            updateTelecomStatusText();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccountDisplay();
    }

    // --- JioRegistrationListener ---
    @Override
    public void onRegistrationProgress() {
        runOnUiThread(() -> {
            llStatusPill.setBackgroundResource(R.drawable.bg_status_pill_amber);
            ivStatusDot.setImageResource(R.drawable.ic_dot_amber);
            tvStatusPill.setText("CONNECTING");
            tvStatusPill.setTextColor(getResources().getColor(R.color.status_amber));
            tvStatusDesc.setText("Connecting to Jio router…");
            tvStatusDesc.setTextColor(getResources().getColor(R.color.on_dark_warning));
        });
    }

    @Override
    public void onRegistered() {
        runOnUiThread(() -> {
            updateAccountDisplay();
        });
    }

    @Override
    public void onRegistrationFailed(int statusCode, String reason) {
        runOnUiThread(() -> {
            llStatusPill.setBackgroundResource(R.drawable.bg_status_pill_red);
            ivStatusDot.setImageResource(R.drawable.ic_dot_red);
            tvStatusPill.setText("OFFLINE");
            tvStatusPill.setTextColor(getResources().getColor(R.color.call_red));
            tvStatusDesc.setText("Registration failed (" + statusCode + ")");
            tvStatusDesc.setTextColor(getResources().getColor(R.color.on_dark_error));
        });
    }

    @Override
    public void onUnregistered() {
        runOnUiThread(() -> {
            llStatusPill.setBackgroundResource(R.drawable.bg_status_pill_red);
            ivStatusDot.setImageResource(R.drawable.ic_dot_red);
            tvStatusPill.setText("DISCONNECTED");
            tvStatusPill.setTextColor(getResources().getColor(R.color.call_red));
            tvStatusDesc.setText("Line disconnected");
            tvStatusDesc.setTextColor(getResources().getColor(R.color.on_dark_error));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sipManager != null) {
            sipManager.removeRegistrationListener(this);
        }
    }
}
