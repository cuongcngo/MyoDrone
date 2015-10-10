package com.cuongcngo.myodrone;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.widget.TextView;
import android.widget.Toast;

import com.parrot.freeflight.FreeFlightApplication;
import com.parrot.freeflight.drone.DroneConfig;
import com.parrot.freeflight.drone.NavData;
import com.parrot.freeflight.receivers.DroneBatteryChangedReceiver;
import com.parrot.freeflight.receivers.DroneBatteryChangedReceiverDelegate;
import com.parrot.freeflight.receivers.DroneCameraReadyActionReceiverDelegate;
import com.parrot.freeflight.receivers.DroneCameraReadyChangeReceiver;
import com.parrot.freeflight.receivers.DroneEmergencyChangeReceiver;
import com.parrot.freeflight.receivers.DroneEmergencyChangeReceiverDelegate;
import com.parrot.freeflight.receivers.DroneFlyingStateReceiver;
import com.parrot.freeflight.receivers.DroneFlyingStateReceiverDelegate;
import com.parrot.freeflight.receivers.DroneRecordReadyActionReceiverDelegate;
import com.parrot.freeflight.receivers.DroneRecordReadyChangeReceiver;
import com.parrot.freeflight.receivers.DroneVideoRecordStateReceiverDelegate;
import com.parrot.freeflight.receivers.DroneVideoRecordingStateReceiver;
import com.parrot.freeflight.receivers.WifiSignalStrengthChangedReceiver;
import com.parrot.freeflight.receivers.WifiSignalStrengthReceiverDelegate;
import com.parrot.freeflight.remotecontrollers.ControlButtonsFactory;
import com.parrot.freeflight.sensors.DeviceOrientationChangeDelegate;
import com.parrot.freeflight.sensors.DeviceOrientationManager;
import com.parrot.freeflight.sensors.DeviceSensorManagerWrapper;
import com.parrot.freeflight.service.DroneControlService;
import com.parrot.freeflight.settings.ApplicationSettings;
import com.parrot.freeflight.transcodeservice.TranscodingService;
import com.parrot.freeflight.utils.NookUtils;
import com.thalmic.myo.AbstractDeviceListener;
import com.thalmic.myo.DeviceListener;
import com.thalmic.myo.Hub;
import com.thalmic.myo.Myo;
import com.thalmic.myo.Pose;
import com.thalmic.myo.Quaternion;
import com.thalmic.myo.XDirection;

import java.io.File;

public class ControlActivity extends AppCompatActivity
        implements DeviceOrientationChangeDelegate, WifiSignalStrengthReceiverDelegate, DroneVideoRecordStateReceiverDelegate, DroneEmergencyChangeReceiverDelegate,
        DroneBatteryChangedReceiverDelegate, DroneFlyingStateReceiverDelegate, DroneCameraReadyActionReceiverDelegate, DroneRecordReadyActionReceiverDelegate
{


    public static String TAG = "ControlActivity";

    private static final int LOW_DISK_SPACE_BYTES_LEFT = 1048576 * 20; //20 mebabytes
    private static final int WARNING_MESSAGE_DISMISS_TIME = 5000; // 5 seconds


    private static final float ACCELERO_TRESHOLD = (float) Math.PI / 180.0f * 2.0f;

    private static final int PITCH = 1;
    private static final int ROLL = 2;

    private Context mContext = this;

    private DroneService mService;

    private int screenRotationIndex;

    private WifiSignalStrengthChangedReceiver wifiSignalReceiver;
    private DroneVideoRecordingStateReceiver videoRecordingStateReceiver;
    private DroneEmergencyChangeReceiver droneEmergencyReceiver;
    private DroneBatteryChangedReceiver droneBatteryReceiver;
    private DroneFlyingStateReceiver droneFlyingStateReceiver;
    private DroneCameraReadyChangeReceiver droneCameraReadyChangedReceiver;
    private DroneRecordReadyChangeReceiver droneRecordReadyChangeReceiver;


    private boolean combinedYawEnabled;
    private boolean acceleroEnabled;
    private boolean magnetoEnabled;
    private boolean magnetoAvailable;
    private boolean controlLinkAvailable;

    private float pitchBase;
    private float rollBase;
    private boolean running;

    private boolean flying;
    private boolean recording;
    private boolean cameraReady;
    private boolean prevRecording;
    private boolean rightJoyPressed;
    private boolean leftJoyPressed;

    private float prevRoll = 0, prevPitch = 0, prevYaw = 0;
    private float startingPitch = 0;
    private float startingYaw = 0;
    private float startingRoll = 0;

    private Pose prevPose = Pose.REST;

    private Thread messengerThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (isFinishing()) {
            return;
        }

        setContentView(R.layout.activity_control);

        Hub hub = Hub.getInstance();
        if (!hub.init(this)) {
            Log.e(TAG, "Could not initialize the Hub.");
            finish();
            return;
        }

        screenRotationIndex = getWindow().getWindowManager().getDefaultDisplay().getRotation();

        bindService(new Intent(this, DroneService.class), mConnection, Context.BIND_AUTO_CREATE);

        Bundle bundle = getIntent().getExtras();

        combinedYawEnabled = true;
        acceleroEnabled = false;
        running = false;

        wifiSignalReceiver = new WifiSignalStrengthChangedReceiver(this);
        videoRecordingStateReceiver = new DroneVideoRecordingStateReceiver(this);
        droneEmergencyReceiver = new DroneEmergencyChangeReceiver(this);
        droneBatteryReceiver = new DroneBatteryChangedReceiver(this);
        droneFlyingStateReceiver = new DroneFlyingStateReceiver(this);
        droneCameraReadyChangedReceiver = new DroneCameraReadyChangeReceiver(this);
        droneRecordReadyChangeReceiver = new DroneRecordReadyChangeReceiver(this);

        outputTextcontrol = (TextView)findViewById(R.id.textView2);
        outputTextcontrol.setMovementMethod(new ScrollingMovementMethod());

        messengerThread = new Thread(messenger, "Messenger Thread");
    }

    private ServiceConnection mConnection = new ServiceConnection()
    {

        public void onServiceConnected(ComponentName name, IBinder service)
        {
            mService = (DroneService) ((DroneControlService.LocalBinder) service).getService();
            onDroneServiceConnected();
        }

        public void onServiceDisconnected(ComponentName name)
        {
            mService = null;
        }
    };

    private TextView outputTextcontrol;

    private DeviceListener mListener = new AbstractDeviceListener() {
        @Override
        public void onConnect(Myo myo, long timestamp) {
            Toast.makeText(mContext, "Myo Connected!", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onDisconnect(Myo myo, long timestamp) {
            Toast.makeText(mContext, "Myo Disconnected!", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onPose(Myo myo, long timestamp, Pose pose) {
            Toast.makeText(mContext, "Pose test: " + pose, Toast.LENGTH_SHORT).show();
            appendOutput("Pose: " + pose);
            //TODO: Do something awesome.


            if (pose == Pose.FIST) {
                mService.setProgressiveCommandEnabled(true);
                mService.setProgressiveCommandCombinedYawEnabled(true);
            } else if (pose == Pose.FINGERS_SPREAD) {
                mService.setProgressiveCommandEnabled(true);
                mService.setProgressiveCommandCombinedYawEnabled(false);
            }

            if(pose != Pose.FINGERS_SPREAD) {
                mService.setGaz(0);
            }

            switch (pose) {
                case FINGERS_SPREAD:
                    onFingersSpread();
                    break;
                case FIST:
                    onFist();
                    break;

            }

            if(pose == Pose.DOUBLE_TAP) {
                mService.triggerTakeOff();
            }

            prevPose = pose;
        }

        @Override
        public void onOrientationData(Myo myo, long timestamp, Quaternion rotation) {
            // Calculate Euler angles (roll, pitch, and yaw) from the quaternion.
            float roll = (float) Math.toDegrees(Quaternion.roll(rotation));
            float pitch = (float) Math.toDegrees(Quaternion.pitch(rotation));
            float yaw = (float) Math.toDegrees(Quaternion.yaw(rotation));

            // Adjust roll and pitch for the orientation of the Myo on the arm.
            if (myo.getXDirection() == XDirection.TOWARD_ELBOW) {
                roll *= -1;
                yaw *= -1;
            }

            prevRoll = roll;
            prevPitch = pitch;
            prevYaw = yaw;

        }

    };

    private boolean stopThread = false;

    private boolean threadCanRun = true;

    private Runnable messenger = new Runnable() {
        @Override
        public void run() {
            while(!stopThread) {
                if(threadCanRun && mService != null && prevPose != null) {
                    switch(prevPose) {

                        case FIST:
                            float dPitch = (prevPitch - startingPitch)/60;
                            dPitch = Math.max(Math.min(1, dPitch), -1);
                            float dYaw = (prevYaw - startingYaw)/60;
                            dYaw = Math.max(Math.min(1, dYaw), -1);
                            float dRoll = prevRoll - startingRoll;
                            if(dRoll < -180) {
                                dRoll += 360;
                            }
                            else if (dRoll > 180) {
                                dRoll = 360 - dRoll;
                            }
                            dRoll /= 90;
                            dRoll = Math.max(Math.min(1, dRoll), -1);

                            Log.d(TAG, "FIST Pitch: " + dPitch);
                            Log.d(TAG, "FIST Yaw: " + dYaw);
                            Log.d(TAG, "FIST Roll: " + dRoll);

                            mService.setRoll(dYaw);
                            mService.setPitch(dPitch);
                            mService.setYaw(dRoll);
                            break;
                        case FINGERS_SPREAD:
                            float dSpreadPitch = (prevPitch - startingPitch)/60;
                            dSpreadPitch = Math.max(Math.min(1, dSpreadPitch), -1);
                            Log.d(TAG, "SPREAD starting: " + startingPitch + " gaz: " + dSpreadPitch);

                            mService.setGaz(dSpreadPitch);
                            break;
                    }
                }

                try {
                    Thread.sleep(10, 0);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    };

    private void onDoubleTap() {
        appendOutput("Take off here.");
        if(mService != null) {

            mService.triggerTakeOff();
        }
    }

    private void onFingersSpread() {
        if(mService != null) {
            mService.setProgressiveCommandEnabled(true);
        }

        startingPitch = prevPitch;
    }

    private void onFist() {
        startingPitch = prevPitch;
        startingYaw = prevYaw;
        startingRoll = prevRoll;
    }



    @Override
    protected void onDestroy() {
        unbindService(mConnection);

        super.onDestroy();
        Log.d(TAG, "ControlDroneActivity destroyed");
        System.gc();
    }

    private void registerReceivers() {
        // System wide receiver
        registerReceiver(wifiSignalReceiver, new IntentFilter(WifiManager.RSSI_CHANGED_ACTION));

        // Local receivers
        LocalBroadcastManager localBroadcastMgr = LocalBroadcastManager.getInstance(getApplicationContext());
        localBroadcastMgr.registerReceiver(videoRecordingStateReceiver, new IntentFilter(mService.VIDEO_RECORDING_STATE_CHANGED_ACTION));
        localBroadcastMgr.registerReceiver(droneEmergencyReceiver, new IntentFilter(mService.DRONE_EMERGENCY_STATE_CHANGED_ACTION));
        localBroadcastMgr.registerReceiver(droneBatteryReceiver, new IntentFilter(mService.DRONE_BATTERY_CHANGED_ACTION));
        localBroadcastMgr.registerReceiver(droneFlyingStateReceiver, new IntentFilter(mService.DRONE_FLYING_STATE_CHANGED_ACTION));
        localBroadcastMgr.registerReceiver(droneCameraReadyChangedReceiver, new IntentFilter(mService.CAMERA_READY_CHANGED_ACTION));
        localBroadcastMgr.registerReceiver(droneRecordReadyChangeReceiver, new IntentFilter(mService.RECORD_READY_CHANGED_ACTION));
    }

    private void unregisterReceivers()
    {
        // Unregistering system receiver
        unregisterReceiver(wifiSignalReceiver);

        // Unregistering local receivers
        LocalBroadcastManager localBroadcastMgr = LocalBroadcastManager.getInstance(getApplicationContext());
        localBroadcastMgr.unregisterReceiver(videoRecordingStateReceiver);
        localBroadcastMgr.unregisterReceiver(droneEmergencyReceiver);
        localBroadcastMgr.unregisterReceiver(droneBatteryReceiver);
        localBroadcastMgr.unregisterReceiver(droneFlyingStateReceiver);
        localBroadcastMgr.unregisterReceiver(droneCameraReadyChangedReceiver);
        localBroadcastMgr.unregisterReceiver(droneRecordReadyChangeReceiver);
    }

    @Override
    protected void onResume() {

        if (mService != null) {
            mService.resume();
        }

        registerReceivers();
        refreshWifiSignalStrength();

        Hub hub = Hub.getInstance();
        if (!hub.init(this)) {
            appendOutput("Could not initialize the Hub.");
            finish();
            return;
        }

        Hub.getInstance().addListener(mListener);
        Hub.getInstance().setLockingPolicy(Hub.LockingPolicy.NONE);

        super.onResume();
    }

    @Override
    protected void onPause() {
        if (mService != null) {
            mService.pause();
        }

        unregisterReceivers();

        Hub hub = Hub.getInstance();
        if (!hub.init(this)) {
            appendOutput("Could not initialize the Hub.");
            finish();
            return;
        }

        Hub.getInstance().removeListener(mListener);

        System.gc();
        super.onPause();
    }

    /**
     * Called when we connected to mService
     */
    protected void onDroneServiceConnected()
    {
        appendOutput("DroneService connected.");
        if (mService != null) {
            mService.resume();
            mService.requestDroneStatus();
        } else {
            appendOutput("DroneServiceConnected event ignored as mService is null");
        }

        runTranscoding();

        if (messengerThread != null && !messengerThread.isAlive()) {
            messengerThread.start();
        }
    }


    @Override
    public void onDroneFlyingStateChanged(boolean flying)
    {
        this.flying = flying;
    }

    @SuppressLint("NewApi")
    public void onDroneRecordReadyChanged(boolean ready)
    {

    }


    protected void onNotifyLowDiskSpace()
    {
        appendOutput("Your device is low on disk space.");
    }


    protected void onNotifyLowUsbSpace()
    {
        appendOutput("USB drive full");
    }


    protected void onNotifyNoMediaStorageAvailable()
    {
        appendOutput("Please insert SD card into smartphone");
    }


    public void onCameraReadyChanged(boolean ready)
    {
        cameraReady = ready;

    }


    public void onDroneEmergencyChanged(int code)
    {

        controlLinkAvailable = (code != NavData.ERROR_STATE_NAVDATA_CONNECTION);

    }


    public void onDroneBatteryChanged(int value)
    {
        appendOutput("Batter level: " + value);
    }

    public void onWifiSignalStrengthChanged(int strength)
    {
        appendOutput("Wifi strength: " + strength);
    }


    public void onDroneRecordVideoStateChanged(boolean recording, boolean usbActive, int remaining)
    {
        if (mService == null)
            return;

        prevRecording = this.recording;
        this.recording = recording;

        updateBackButtonState();

        if (!recording) {
            if (prevRecording != recording && mService != null
                    && mService.getDroneVersion() == DroneConfig.EDroneVersion.DRONE_1) {
                runTranscoding();
                appendOutput("Your video is being processed. Please do not close application");
            }
        }

        if (prevRecording != recording) {
            if (usbActive && mService.getDroneConfig().isRecordOnUsb() && remaining == 0) {
                onNotifyLowUsbSpace();
            }
        }
    }

    protected void showSettingsDialog()
    {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        Fragment prev = getSupportFragmentManager().findFragmentByTag("settings");

        if (prev != null) {
            return;
        }

        ft.addToBackStack(null);
    }

    @Override
    public void onBackPressed()
    {
        if (canGoBack()) {
            super.onBackPressed();
        }
    }


    private boolean canGoBack()
    {
        return !((flying || recording || !cameraReady) && controlLinkAvailable);
    }


    private void applySettings(ApplicationSettings settings)
    {
        applySettings(settings, false);
    }

    private void applySettings(ApplicationSettings settings, boolean skipJoypadConfig)
    {
        magnetoEnabled = true;

        if (mService != null)
            mService.setMagnetoEnabled(magnetoEnabled);

    }

    private ApplicationSettings getSettings()
    {
        return ((FreeFlightApplication) getApplication()).getAppSettings();
    }

    public void refreshWifiSignalStrength()
    {
        WifiManager manager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        int signalStrength = WifiManager.calculateSignalLevel(manager.getConnectionInfo().getRssi(), 4);
        onWifiSignalStrengthChanged(signalStrength);
    }

    private void updateBackButtonState()
    {

    }


    private void runTranscoding()
    {
        if (mService.getDroneVersion() == DroneConfig.EDroneVersion.DRONE_1) {
            File mediaDir = mService.getMediaDir();

            if (mediaDir != null) {
                Intent transcodeIntent = new Intent(this, TranscodingService.class);
                transcodeIntent.putExtra(TranscodingService.EXTRA_MEDIA_PATH, mediaDir.toString());
                startService(transcodeIntent);
            } else {
                Log.d(TAG, "Transcoding skipped SD card is missing.");
            }
        }
    }

    public void onDeviceOrientationChanged(float[] orientation, float magneticHeading, int magnetoAccuracy)
    {
        if (mService == null) {
            return;
        }

        if (magnetoEnabled && magnetoAvailable) {
            float heading = magneticHeading * 57.2957795f;

            if (screenRotationIndex == 1) {
                heading += 90.f;
            }

            mService.setDeviceOrientation((int) heading, 0);
        } else {
            mService.setDeviceOrientation(0, 0);
        }

        if (running == false) {
            pitchBase = orientation[PITCH];
            rollBase = orientation[ROLL];
            mService.setPitch(0);
            mService.setRoll(0);
        } else {

            float x = (orientation[PITCH] - pitchBase);
            float y = (orientation[ROLL] - rollBase);

            if (screenRotationIndex == 0) {
                // Xoom
                if (acceleroEnabled && (Math.abs(x) > ACCELERO_TRESHOLD || Math.abs(y) > ACCELERO_TRESHOLD)) {
                    x *= -1;
                    mService.setPitch(x);
                    mService.setRoll(y);
                }
            } else if (screenRotationIndex == 1) {
                if (acceleroEnabled && (Math.abs(x) > ACCELERO_TRESHOLD || Math.abs(y) > ACCELERO_TRESHOLD)) {
                    x *= -1;
                    y *= -1;

                    mService.setPitch(y);
                    mService.setRoll(x);
                }
            } else if (screenRotationIndex == 3) {
                // google tv
                if (acceleroEnabled && (Math.abs(x) > ACCELERO_TRESHOLD || Math.abs(y) > ACCELERO_TRESHOLD)) {

                    mService.setPitch(y);
                    mService.setRoll(x);
                }
            }
        }
    }

    private boolean isLowOnDiskSpace()
    {
        boolean lowOnSpace = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            DroneConfig config = mService.getDroneConfig();
            if (!recording && !config.isRecordOnUsb()) {
                File mediaDir = mService.getMediaDir();
                long freeSpace = 0;

                if (mediaDir != null) {
                    freeSpace = mediaDir.getUsableSpace();
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD
                        && freeSpace < LOW_DISK_SPACE_BYTES_LEFT) {
                    lowOnSpace = true;
                }
            }
        } else {
            // TODO: Provide alternative implementation. Probably using StatFs
        }

        return lowOnSpace;
    }

    private void onRecord()
    {
        if (mService != null) {
            DroneConfig droneConfig = mService.getDroneConfig();

            boolean sdCardMounted = mService.isMediaStorageAvailable();
            boolean recordingToUsb = droneConfig.isRecordOnUsb() && mService.isUSBInserted();

            if (recording) {
                // Allow to stop recording
                mService.record();
            } else {
                // Start recording
                if (!sdCardMounted) {
                    if (recordingToUsb) {
                        mService.record();
                    } else {
                        onNotifyNoMediaStorageAvailable();
                    }
                } else {
                    if (!recordingToUsb && isLowOnDiskSpace()) {
                        onNotifyLowDiskSpace();
                    }

                    mService.record();
                }
            }
        }
    }

    protected void onTakePhoto()
    {
        if (mService.isMediaStorageAvailable()) {
            mService.takePhoto();
        } else {
            onNotifyNoMediaStorageAvailable();
        }
    }

    private void appendOutput(CharSequence cs) {
        if(outputTextcontrol != null) {
            outputTextcontrol.append("\n");
            outputTextcontrol.append(cs);

            if(outputTextcontrol.getLayout() != null) {
                // find the amount we need to scroll.  This works by
                // asking the TextView's internal layout for the position
                // of the final line and then subtracting the TextView's height
                final int scrollAmount = outputTextcontrol.getLayout().getLineTop(outputTextcontrol.getLineCount()) - outputTextcontrol.getHeight();
                // if there is no need to scroll, scrollAmount will be <=0
                if (scrollAmount > 0)
                    outputTextcontrol.scrollTo(0, scrollAmount);
                else
                    outputTextcontrol.scrollTo(0, 0);
            }
        }
        Log.d(TAG, cs.toString());
    }
    
    

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_control, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
