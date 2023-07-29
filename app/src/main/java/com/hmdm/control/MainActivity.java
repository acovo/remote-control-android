/*
 * Headwind Remote: Open Source Remote Access Software for Android
 * https://headwind-remote.com
 *
 * Copyright (C) 2022 headwind-remote.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hmdm.control;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hmdm.control.janus.SharingEngineJanus;

import com.hmdm.control.BuildConfig;

public class MainActivity extends AppCompatActivity implements SharingEngineJanus.EventListener, SharingEngineJanus.StateListener {

    private ImageView imageViewConnStatus;
    private TextView textViewConnStatus;
    private EditText editTextSessionId;
    private EditText editTextPassword;
    private TextView textViewComment;
    private TextView textViewConnect;
    private TextView textViewSendLink;
    private TextView textViewExit;

    private ImageView overlayDot;
    private Handler handler = new Handler();
    private int overlayDotAlpha;
    private int overlayDotDirection = 1;

    private Dialog exitOnIdleDialog;
    private int exitCounter;
    private static final int EXIT_PROMPT_SEC = 10;

    private static final int OVERLAY_DOT_ANIMATION_INCREMENT = 20;
    private static final int OVERLAY_DOT_ANIMATION_DELAY = 200;

    private SharingEngine sharingEngine;

    private SettingsHelper settingsHelper;

    private String sessionId;
    private String password;
    private String adminName;

    private final static String ATTR_SESSION_ID = "sessionId";
    private final static String ATTR_PASSWORD = "password";
    private final static String ATTR_ADMIN_NAME = "adminName";

    private boolean needReconnect = false;

    private MediaProjectionManager projectionManager;

    private BroadcastReceiver mSharingServiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            if (intent.getAction().equals(Const.ACTION_SCREEN_SHARING_START)) {
                notifySharingStart();

            } else if (intent.getAction().equals(Const.ACTION_SCREEN_SHARING_STOP)) {
                notifySharingStop();
                adminName = null;
                updateUI();
                cancelSharingTimeout();
                scheduleExitOnIdle();

            } else if (intent.getAction().equals(Const.ACTION_SCREEN_SHARING_FAILED)) {
                String message = intent.getStringExtra(Const.EXTRA_MESSAGE);
                if (message != null) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
                adminName = null;
                updateUI();
                cancelSharingTimeout();
                scheduleExitOnIdle();

            } else if (intent.getAction().equals(Const.ACTION_CONNECTION_FAILURE)) {
                sharingEngine.setState(Const.STATE_DISCONNECTED);
                Toast.makeText(MainActivity.this, R.string.connection_failure_hint, Toast.LENGTH_LONG).show();
                updateUI();

            } else if (intent.getAction().equals(Const.ACTION_SCREEN_SHARING_PERMISSION_NEEDED)) {
                startActivityForResult(projectionManager.createScreenCaptureIntent(), Const.REQUEST_SCREEN_SHARE);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (savedInstanceState != null) {
            restoreInstanceState(savedInstanceState);
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        settingsHelper = SettingsHelper.getInstance(this);
        sharingEngine = SharingEngineFactory.getSharingEngine();
        sharingEngine.setEventListener(this);
        sharingEngine.setStateListener(this);

        DisplayMetrics metrics = new DisplayMetrics();
        ScreenSharingHelper.getRealScreenSize(this, metrics);
        float videoScale = ScreenSharingHelper.adjustScreenMetrics(metrics);
        settingsHelper.setFloat(SettingsHelper.KEY_VIDEO_SCALE, videoScale);
        ScreenSharingHelper.setScreenMetrics(this, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi);

        sharingEngine.setScreenWidth(metrics.widthPixels);
        sharingEngine.setScreenHeight(metrics.heightPixels);

        IntentFilter intentFilter = new IntentFilter(Const.ACTION_SCREEN_SHARING_START);
        intentFilter.addAction(Const.ACTION_SCREEN_SHARING_STOP);
        intentFilter.addAction(Const.ACTION_SCREEN_SHARING_PERMISSION_NEEDED);
        intentFilter.addAction(Const.ACTION_SCREEN_SHARING_FAILED);
        intentFilter.addAction(Const.ACTION_CONNECTION_FAILURE);
        LocalBroadcastManager.getInstance(this).registerReceiver(mSharingServiceReceiver, intentFilter);

        projectionManager = (MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        initUI();
        setDefaultSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();

        startService(new Intent(MainActivity.this, GestureDispatchService.class));
        checkAccessibility();
    }

    private void checkAccessibility() {
        if (!Utils.isAccessibilityPermissionGranted(this)) {
            // Sometimes this method returns false for an unknown reason;
            // Let's try to repeat the request in 1/2 sec
            handler.postDelayed(() -> {
                if (!Utils.isAccessibilityPermissionGranted(this)) {
                    textViewConnect.setVisibility(View.INVISIBLE);
                    try {
                        new AlertDialog.Builder(this)
                                .setMessage(R.string.accessibility_hint)
                                .setPositiveButton(R.string.continue_button, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        Intent intent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                                        try {
                                            startActivityForResult(intent, 0);
                                        } catch (Exception e) {
                                            // Accessibility settings cannot be opened
                                            reportAccessibilityUnavailable();
                                        }
                                    }
                                })
                                .setCancelable(false)
                                .create()
                                .show();
                    } catch (Exception e) {
                        // The config may change when the app is in the background
                        // In this case, we will get an exception while trying to use UI
                        // Just ignore this error
                        e.printStackTrace();
                    }

                } else {
                    configureAndConnect();
                }

            }, 500);
        } else {
            configureAndConnect();
        }
    }

    private void reportAccessibilityUnavailable() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.accessibility_unavailable_error)
                .setPositiveButton(R.string.button_exit, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        exitApp();
                    }
                })
                .setCancelable(false)
                .create()
                .show();
    }

    private void configureAndConnect() {
        if (settingsHelper.getString(SettingsHelper.KEY_SERVER_URL) == null) {
            // Not configured yet
            settingsHelper.setString(SettingsHelper.KEY_SERVER_URL, BuildConfig.DEFAULT_SERVER_URL);
            settingsHelper.setString(SettingsHelper.KEY_SECRET, BuildConfig.DEFAULT_SECRET);
            settingsHelper.setBoolean(SettingsHelper.KEY_USE_DEFAULT, !BuildConfig.DEFAULT_SECRET.equals(""));
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivityForResult(intent, Const.REQUEST_SETTINGS);
            return;
        }

        if (needReconnect) {
            // Here we go after changing settings
            needReconnect = false;
            if (sharingEngine.getState() != Const.STATE_DISCONNECTED) {
                sharingEngine.disconnect(MainActivity.this, (success, errorReason) -> connect());
            } else {
                connect();
            }
        } else {
            if (sharingEngine.getState() == Const.STATE_DISCONNECTED && sharingEngine.getErrorReason() == null) {
                connect();
            }
        }
    }

    @Override
    public void onDestroy() {
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(mSharingServiceReceiver);
        } catch (Exception e) {
        }
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        savedInstanceState.putString(ATTR_SESSION_ID, sessionId);
        savedInstanceState.putString(ATTR_PASSWORD, password);
        savedInstanceState.putString(ATTR_ADMIN_NAME, adminName);
        super.onSaveInstanceState(savedInstanceState);
    }

    private void restoreInstanceState(Bundle savedInstanceState) {
        sessionId = savedInstanceState.getString(ATTR_SESSION_ID);
        password = savedInstanceState.getString(ATTR_PASSWORD);
        adminName = savedInstanceState.getString(ATTR_ADMIN_NAME);
    }

    @Override
    public void onBackPressed() {
        Toast.makeText(this, R.string.back_pressed, Toast.LENGTH_LONG).show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            if (adminName != null) {
                Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_LONG).show();
                return true;
            }
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivityForResult(intent, Const.REQUEST_SETTINGS);
            cancelExitOnIdle();
            return true;
        } else if (id == R.id.action_about) {
            showAbout();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == Const.REQUEST_SETTINGS) {
            if (resultCode == Const.RESULT_DIRTY) {
                needReconnect = true;
            } else {
                scheduleExitOnIdle();
            }
        } else if (requestCode == Const.REQUEST_SCREEN_SHARE) {
            if (resultCode != RESULT_OK) {
                Toast.makeText(this, R.string.screen_cast_denied, Toast.LENGTH_LONG).show();
                adminName = null;
                updateUI();
                cancelSharingTimeout();
                scheduleExitOnIdle();
            } else {
                ScreenSharingHelper.startSharing(this, resultCode, data);
            }
        }
    }

    private void initUI() {
        imageViewConnStatus = findViewById(R.id.image_conn_status);
        textViewConnStatus = findViewById(R.id.conn_status);
        editTextSessionId = findViewById(R.id.session_id_edit);
        editTextPassword = findViewById(R.id.password_edit);
        textViewComment = findViewById(R.id.comment);
        textViewConnect = findViewById(R.id.reconnect);
        textViewSendLink = findViewById(R.id.send_link);
        textViewExit = findViewById(R.id.disconnect_exit);

        textViewConnect.setOnClickListener(v -> connect());

        textViewSendLink.setOnClickListener(v -> sendLink());

        textViewExit.setOnClickListener(v -> gracefulExit());
    }

    private void gracefulExit() {
        if (adminName != null) {
            notifySharingStop();
            ScreenSharingHelper.stopSharing(MainActivity.this, true);
        }
        sharingEngine.disconnect(MainActivity.this, (success, errorReason) -> exitApp());
        // 5 sec timeout to exit
        handler.postDelayed(() -> exitApp(), 5000);
    }

    private void exitApp() {
        Intent intent = new Intent(MainActivity.this, ScreenSharingService.class);
        stopService(intent);
        intent = new Intent(MainActivity.this, GestureDispatchService.class);
        stopService(intent);

        // Delayed exit to let services gracefully finish their work
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    finishAffinity();
                } catch (Exception e) {
                    // On some Android 8 devices:
                    // Fatal Exception: java.lang.IllegalStateException
                    // Can not be called to deliver a result
                    e.printStackTrace();
                }
                System.exit(0);
            }
        }, 1000);
    }

    private void updateUI() {
        int[] stateLabels = {R.string.state_disconnected, R.string.state_connecting, R.string.state_connected, R.string.state_sharing, R.string.state_disconnecting};
        int[] stateImages = {R.drawable.ic_disconnected, R.drawable.ic_connecting, R.drawable.ic_connected, R.drawable.ic_sharing, R.drawable.ic_connecting};

        int state = sharingEngine.getState();
        if (state == Const.STATE_CONNECTED && adminName != null) {
            imageViewConnStatus.setImageDrawable(getDrawable(stateImages[Const.STATE_SHARING]));
            textViewConnStatus.setText(stateLabels[Const.STATE_SHARING]);
        } else {
            imageViewConnStatus.setImageDrawable(getDrawable(stateImages[state]));
            textViewConnStatus.setText(stateLabels[state]);
        }
        String serverUrl = Utils.prepareDisplayUrl(settingsHelper.getString(SettingsHelper.KEY_SERVER_URL));

        textViewSendLink.setVisibility(state == Const.STATE_CONNECTED ? View.VISIBLE : View.INVISIBLE);
        textViewConnect.setVisibility(state == Const.STATE_DISCONNECTED ? View.VISIBLE : View.INVISIBLE);
        switch (state) {
            case Const.STATE_DISCONNECTED:
                editTextSessionId.setText("");
                editTextPassword.setText("");
                if (sharingEngine.getErrorReason() != null) {
                    textViewComment.setText(getString(R.string.hint_connection_error, serverUrl));
                }
                break;
            case Const.STATE_CONNECTING:
                textViewComment.setText(getString(R.string.hint_connecting, serverUrl));
                break;
            case Const.STATE_DISCONNECTING:
                textViewComment.setText(getString(R.string.hint_disconnecting));
                break;
            case Const.STATE_CONNECTED:
                editTextSessionId.setText(sessionId);
                editTextPassword.setText(password);
                textViewComment.setText(adminName != null ?
                        getString(R.string.hint_sharing, adminName) :
                        getString(R.string.hint_connected, serverUrl)
                        );
                break;
        }
    }

    private void setDefaultSettings() {
        if (settingsHelper.getString(SettingsHelper.KEY_DEVICE_NAME) == null) {
            settingsHelper.setString(SettingsHelper.KEY_DEVICE_NAME, Build.MANUFACTURER + " " + Build.MODEL);
        }
        if (settingsHelper.getInt(SettingsHelper.KEY_BITRATE) == 0) {
            settingsHelper.setInt(SettingsHelper.KEY_BITRATE, Const.DEFAULT_BITRATE);
        }
        if (settingsHelper.getInt(SettingsHelper.KEY_FRAME_RATE) == 0) {
            settingsHelper.setInt(SettingsHelper.KEY_FRAME_RATE, Const.DEFAULT_FRAME_RATE);
        }
        if (settingsHelper.getInt(SettingsHelper.KEY_IDLE_TIMEOUT) == 0) {
            settingsHelper.setInt(SettingsHelper.KEY_IDLE_TIMEOUT, Const.DEFAULT_IDLE_TIMEOUT);
        }
        if (settingsHelper.getInt(SettingsHelper.KEY_PING_TIMEOUT) == 0) {
            settingsHelper.setInt(SettingsHelper.KEY_PING_TIMEOUT, Const.DEFAULT_PING_TIMEOUT);
        }
    }

    private void sendLink() {
        String url = settingsHelper.getString(SettingsHelper.KEY_SERVER_URL);
        url += "?session=" + sessionId + "&pin=" + password;
        try {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.send_link_subject));
            String shareMessage= getString(R.string.send_link_message, url, settingsHelper.getString(SettingsHelper.KEY_DEVICE_NAME));
            shareIntent.putExtra(Intent.EXTRA_TEXT, shareMessage);
            startActivity(Intent.createChooser(shareIntent, getString(R.string.send_link_chooser)));
        } catch(Exception e) {
            e.printStackTrace();
            Toast.makeText(this, R.string.send_link_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void showAbout() {
        ImageView imageView = new ImageView(this);
        imageView.setImageDrawable(getResources().getDrawable(R.mipmap.ic_launcher));
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_message, BuildConfig.VERSION_NAME, BuildConfig.VARIANT))
                .setPositiveButton(R.string.ok, (dialog, which) -> dialog.dismiss())
                .create()
                .show();
    }

    private void connect() {
        if (sessionId == null || password == null) {
            sessionId = Utils.randomString(8, true);
            password = Utils.randomString(4, true);
        }
        sharingEngine.setUsername(settingsHelper.getString(SettingsHelper.KEY_DEVICE_NAME));
        sharingEngine.connect(this, sessionId, password, (success, errorReason) -> {
            if (!success) {
                if (errorReason != null && errorReason.equals(Const.ERROR_ICE_FAILED)) {
                    errorReason = getString(R.string.connection_error_ice);
                }
                String message = getString(R.string.connection_error, settingsHelper.getString(SettingsHelper.KEY_SERVER_URL), errorReason);
                reportError(message);
                editTextSessionId.setText(null);
                editTextPassword.setText(null);
            }
        });

        scheduleExitOnIdle();
    }

    private void reportError(final String message) {
//        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setMessage(message)
                .setNegativeButton(R.string.copy_message, (dialog1, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText(Const.LOG_TAG, message);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(MainActivity.this, R.string.message_copied, Toast.LENGTH_LONG).show();
                    dialog1.dismiss();
                })
                .setPositiveButton(R.string.close, (dialog1, which) -> dialog1.dismiss())
                .create();
        try {
            dialog.show();
            handler.postDelayed(() -> {
                try {
                    dialog.dismiss();
                } catch (Exception e) {
                }
            }, 10000);
        } catch (Exception e) {
        }
    }

    @Override
    public void onStartSharing(String adminName) {
        // This event is raised when the admin joins the text room
        this.adminName = adminName;
        updateUI();
        cancelExitOnIdle();
        scheduleSharingTimeout();
        ScreenSharingHelper.requestSharing(this);
    }

    @Override
    public void onStopSharing() {
        // This event is raised when the admin leaves the text room
        notifySharingStop();
        adminName = null;
        updateUI();
        cancelSharingTimeout();
        scheduleExitOnIdle();
        ScreenSharingHelper.stopSharing(this, false);
    }

    @Override
    public void onRemoteControlEvent(String event) {
        Intent intent = new Intent(MainActivity.this, GestureDispatchService.class);
        intent.setAction(Const.ACTION_GESTURE);
        intent.putExtra(Const.EXTRA_EVENT, event);
        startService(intent);
    }

    @Override
    public void onPing() {
        if (adminName != null) {
            cancelSharingTimeout();
            scheduleSharingTimeout();
        }
    }

    @Override
    public void onSharingApiStateChanged(int state) {
        updateUI();
        if (state == Const.STATE_CONNECTED) {
            String rtpHost = Utils.getRtpUrl(settingsHelper.getString(SettingsHelper.KEY_SERVER_URL));
            int rtpAudioPort = sharingEngine.getAudioPort();
            int rtpVideoPort = sharingEngine.getVideoPort();
            String testDstIp = settingsHelper.getString(SettingsHelper.KEY_TEST_DST_IP);
            if (testDstIp != null && !testDstIp.trim().equals("")) {
                rtpHost = testDstIp;
                rtpVideoPort = Const.TEST_RTP_PORT;
                Toast.makeText(this, "Test mode: sending stream to " + rtpHost + ":" + rtpVideoPort, Toast.LENGTH_LONG).show();
            }

            ScreenSharingHelper.configure(this, settingsHelper.getBoolean(SettingsHelper.KEY_TRANSLATE_AUDIO),
                    settingsHelper.getInt(SettingsHelper.KEY_FRAME_RATE),
                    settingsHelper.getInt(SettingsHelper.KEY_BITRATE),
                    settingsHelper.getInt(SettingsHelper.KEY_FORCE_REFRESH_SEC),
                    rtpHost,
                    rtpAudioPort,
                    rtpVideoPort
                    );
        }
    }

    private void scheduleExitOnIdle() {
        int exitOnIdleTimeout = settingsHelper.getInt(SettingsHelper.KEY_IDLE_TIMEOUT);
        if (exitOnIdleTimeout > 0) {
            exitCounter = EXIT_PROMPT_SEC;
            handler.postDelayed(warningOnIdleRunnable, exitOnIdleTimeout * 1000);
            Log.d(Const.LOG_TAG, "Scheduling exit in " + (exitOnIdleTimeout * 1000) + " sec");
        }
    }

    private void cancelExitOnIdle() {
        Log.d(Const.LOG_TAG, "Cancelling scheduled exit");
        handler.removeCallbacks(warningOnIdleRunnable);
        handler.removeCallbacks(exitRunnable);
    }

    private Runnable exitRunnable = () -> {
        exitCounter--;
        if (exitCounter > 0) {
            TextView messageView = exitOnIdleDialog.findViewById(android.R.id.message);
            if (messageView != null) {
                messageView.setText(MainActivity.this.getResources().getString(R.string.app_idle_warning, exitCounter));
            }
            scheduleExitRunnable();

        } else {
            gracefulExit();
        }
    };

    private Runnable warningOnIdleRunnable = () -> {
         exitOnIdleDialog = new AlertDialog.Builder(MainActivity.this)
                .setMessage(MainActivity.this.getResources().getString(R.string.app_idle_warning, exitCounter))
                .setPositiveButton(R.string.button_exit, (dialog1, which) -> {
                    gracefulExit();
                })
                .setNegativeButton(R.string.button_keep_idle, (dialog1, which) -> {
                    scheduleExitOnIdle();
                    handler.removeCallbacks(exitRunnable);
                    dialog1.dismiss();
                })
                .setCancelable(false)
                .create();
         try {
             exitOnIdleDialog.show();
             scheduleExitRunnable();
         } catch (Exception e) {
             gracefulExit();
         }
    };

    private void scheduleExitRunnable() {
        handler.postDelayed(exitRunnable, 1000);
    }

    private void scheduleSharingTimeout() {
        int pingTimeout = settingsHelper.getInt(SettingsHelper.KEY_PING_TIMEOUT);
        if (pingTimeout > 0) {
            Log.d(Const.LOG_TAG, "Scheduling sharing stop in " + (pingTimeout * 1000) + " sec");
            handler.postDelayed(sharingStopByPingTimeoutRunnable, pingTimeout * 1000);
        }
    }

    private void cancelSharingTimeout() {
        Log.d(Const.LOG_TAG, "Cancelling scheduled sharing stop");
        handler.removeCallbacks(sharingStopByPingTimeoutRunnable);
    }

    private Runnable sharingStopByPingTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            Toast.makeText(MainActivity.this, R.string.app_sharing_session_ping_timeout, Toast.LENGTH_LONG).show();
            if (adminName != null) {
                notifySharingStop();
                ScreenSharingHelper.stopSharing(MainActivity.this, false);
            }
            adminName = null;
            updateUI();
            cancelSharingTimeout();
            scheduleExitOnIdle();
            sharingEngine.disconnect(MainActivity.this, (success, errorReason) -> connect());
        }
    };

    private Runnable overlayDotRunnable = new Runnable() {
        @Override
        public void run() {
            if (overlayDotDirection == 0) {
                return;
            }
            overlayDotAlpha += OVERLAY_DOT_ANIMATION_INCREMENT * overlayDotDirection;
            if (overlayDotAlpha > 255) {
                overlayDotAlpha = 255;
                overlayDotDirection = -overlayDotDirection;
            }
            if (overlayDotAlpha < 128) {
                overlayDotAlpha = 128;
                overlayDotDirection = -overlayDotDirection;
            }
            overlayDot.setImageAlpha(overlayDotAlpha);
            handler.postDelayed(overlayDotRunnable, OVERLAY_DOT_ANIMATION_DELAY);
        }
    };

    private void notifySharingStart() {
        notifyGestureService(Const.ACTION_SCREEN_SHARING_START);
        if (settingsHelper.getBoolean(SettingsHelper.KEY_NOTIFY_SHARING)) {
            // Show a flashing dot
            if (BuildConfig.LOCK_ORIENTATION) {
                Utils.lockDeviceRotation(this, true);
            }
            overlayDot = createOverlayDot();
            overlayDotAlpha = 0;
            overlayDotDirection = 1;
            handler.postDelayed(overlayDotRunnable, OVERLAY_DOT_ANIMATION_DELAY);

        } else {
            // Just show some dialog to trigger the traffic
            final AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                    .setMessage(R.string.share_start_text)
                    .setPositiveButton(R.string.ok, (dialog1, which) -> dialog1.dismiss())
                    .create();
            dialog.show();
            handler.postDelayed(() -> {
                if (dialog != null && dialog.isShowing()) {
                    try {
                        dialog.dismiss();
                    } catch (Exception e) {
                    }
                }
            }, 3000);
        }
    }

    private void notifySharingStop() {
        notifyGestureService(Const.ACTION_SCREEN_SHARING_STOP);
        if (settingsHelper.getBoolean(SettingsHelper.KEY_NOTIFY_SHARING)) {
            overlayDotDirection = 0;
            if (overlayDot != null) {
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                wm.removeView(overlayDot);
                overlayDot = null;
            }
            if (BuildConfig.LOCK_ORIENTATION) {
                Utils.lockDeviceRotation(this, false);
            }
        }
    }

    private void notifyGestureService(String action) {
        Intent intent = new Intent(MainActivity.this, GestureDispatchService.class);
        intent.setAction(action);
        startService(intent);
    }

    public ImageView createOverlayDot() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int maxSize = displayMetrics.widthPixels > displayMetrics.heightPixels ?
                displayMetrics.widthPixels : displayMetrics.heightPixels;
        int size = (int)(Const.FLASHING_DOT_RELATIVE_SIZE * maxSize);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(size, size,
                Utils.OverlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        |WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        |WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.LEFT | Gravity.TOP;
        params.x = getResources().getDimensionPixelOffset(R.dimen.overlay_dot_offset);
        params.y = getResources().getDimensionPixelOffset(R.dimen.overlay_dot_offset);

        ImageView view = new ImageView(this);
        view.setImageResource(R.drawable.flash_dot);
        view.setImageAlpha(0);
        WindowManager wm = (WindowManager)getSystemService(Context.WINDOW_SERVICE);
        wm.addView(view, params);
        return view;
    }
}
