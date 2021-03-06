package com.cuongcngo.myodrone;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.parrot.freeflight.receivers.DroneAvailabilityDelegate;
import com.parrot.freeflight.receivers.DroneAvailabilityReceiver;
import com.parrot.freeflight.receivers.DroneBatteryChangedReceiver;
import com.parrot.freeflight.receivers.DroneBatteryChangedReceiverDelegate;
import com.parrot.freeflight.receivers.DroneConnectionChangeReceiverDelegate;
import com.parrot.freeflight.receivers.DroneConnectionChangedReceiver;
import com.parrot.freeflight.receivers.DroneReadyReceiver;
import com.parrot.freeflight.receivers.DroneReadyReceiverDelegate;
import com.parrot.freeflight.receivers.NetworkChangeReceiver;
import com.parrot.freeflight.receivers.NetworkChangeReceiverDelegate;
import com.parrot.freeflight.service.DroneControlService;
import com.parrot.freeflight.service.intents.DroneStateManager;
import com.parrot.freeflight.tasks.CheckDroneNetworkAvailabilityTask;
import com.thalmic.myo.AbstractDeviceListener;
import com.thalmic.myo.DeviceListener;
import com.thalmic.myo.Hub;
import com.thalmic.myo.Myo;
import com.thalmic.myo.Pose;
import com.thalmic.myo.scanner.ScanActivity;

public class MainActivity extends AppCompatActivity
        implements ServiceConnection,
        DroneAvailabilityDelegate,
        NetworkChangeReceiverDelegate,
        DroneConnectionChangeReceiverDelegate,
        DroneReadyReceiverDelegate, DroneBatteryChangedReceiverDelegate {

    static {
        System.loadLibrary("avutil");
        System.loadLibrary("swscale");
        System.loadLibrary("avcodec");
        System.loadLibrary("avfilter");
        System.loadLibrary("avformat");
        System.loadLibrary("avdevice");
        System.loadLibrary("glfix");
        System.loadLibrary("adfreeflight");
    }

    public static String TAG = "MainActivity";

    private Context mContext = this;

    private TextView outputText;
    private Button btnConnectMyo;

    private DroneService mService;
    private DroneBatteryChangedReceiver droneBatteryReceiver;

    private BroadcastReceiver droneStateReceiver;
    private BroadcastReceiver networkChangeReceiver;
    private BroadcastReceiver droneConnectionChangeReceiver;
    private BroadcastReceiver droneReadyReceiver;

    private CheckDroneNetworkAvailabilityTask checkDroneConnectionTask;

    private boolean droneOnNetwork = false;
    private boolean droneReady = false;
    private boolean myoReady = false;



    private DeviceListener mListener = new AbstractDeviceListener() {
        @Override
        public void onConnect(Myo myo, long timestamp) {
            Toast.makeText(mContext, "Myo Connected!", Toast.LENGTH_SHORT).show();
            appendOutput("Myo Connected!");
            myoReady = true;

            onDeviceReady();

//            Intent intent = new Intent(mContext, ControlActivity.class);
//            mContext.startActivity(intent);
        }

        @Override
        public void onDisconnect(Myo myo, long timestamp) {
            Toast.makeText(mContext, "Myo Disconnected!", Toast.LENGTH_SHORT).show();
            appendOutput("Myo Disconnected!");
            myoReady = false;
        }

        @Override
        public void onPose(Myo myo, long timestamp, Pose pose) {
            Toast.makeText(mContext, "Pose: " + pose, Toast.LENGTH_SHORT).show();
            appendOutput("Pose: " + pose);
            //TODO: Do something awesome.
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });

        initBroadcastReceivers();

        try {
            bindService(new Intent(this, DroneService.class), this, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            e.printStackTrace();
        }

        outputText = (TextView)findViewById(R.id.output_text);
        outputText.setMovementMethod(new ScrollingMovementMethod());
        btnConnectMyo = (Button)findViewById(R.id.btn_connect_myo);

        Hub.getInstance().setLockingPolicy(Hub.LockingPolicy.NONE);



    }

    private void appendOutput(CharSequence cs) {
        if(outputText != null) {
            outputText.append("\n");
            outputText.append(cs);

            if(outputText.getLayout() != null) {
                // find the amount we need to scroll.  This works by
                // asking the TextView's internal layout for the position
                // of the final line and then subtracting the TextView's height
                final int scrollAmount = outputText.getLayout().getLineTop(outputText.getLineCount()) - outputText.getHeight();
                // if there is no need to scroll, scrollAmount will be <=0
                if (scrollAmount > 0)
                    outputText.scrollTo(0, scrollAmount);
                else
                    outputText.scrollTo(0, 0);
            }
        }
        Log.d(TAG, cs.toString());
    }

    protected void initBroadcastReceivers()
    {
        appendOutput("initializing receivers");
        droneStateReceiver = new DroneAvailabilityReceiver(this);
        networkChangeReceiver = new NetworkChangeReceiver(this);
        droneConnectionChangeReceiver = new DroneConnectionChangedReceiver(this);
        droneReadyReceiver = new DroneReadyReceiver(this);
        droneBatteryReceiver = new DroneBatteryChangedReceiver(this);
    }

    private void registerBroadcastReceivers()
    {
        appendOutput("registering receivers");
        LocalBroadcastManager broadcastManager = LocalBroadcastManager.getInstance(getApplicationContext());
        //LocalBroadcastManager broadcastManager = LocalBroadcastManager.getInstance(this);
        broadcastManager.registerReceiver(droneStateReceiver, new IntentFilter(
                DroneStateManager.ACTION_DRONE_STATE_CHANGED));

        broadcastManager.registerReceiver(droneConnectionChangeReceiver, new IntentFilter(DroneControlService.DRONE_CONNECTION_CHANGED_ACTION));
        broadcastManager.registerReceiver(droneReadyReceiver, new IntentFilter(DroneControlService.DRONE_STATE_READY_ACTION));
        broadcastManager.registerReceiver(droneBatteryReceiver, new IntentFilter(DroneControlService.DRONE_BATTERY_CHANGED_ACTION));

        registerReceiver(networkChangeReceiver, new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION));
    }


    private void unregisterReceivers()
    {
        appendOutput("UNregistering receivers");
        LocalBroadcastManager broadcastManager = LocalBroadcastManager.getInstance(getApplicationContext());
        broadcastManager.unregisterReceiver(droneStateReceiver);
        broadcastManager.unregisterReceiver(droneConnectionChangeReceiver);
        broadcastManager.unregisterReceiver(droneReadyReceiver);
        broadcastManager.unregisterReceiver(droneBatteryReceiver);
        unregisterReceiver(networkChangeReceiver);
    }


    @Override
    protected void onDestroy()
    {
        unbindService(this);
        super.onDestroy();
    }


    @Override
    protected void onPause()
    {
        super.onPause();

        if (mService != null) {
            mService.pause();
        }

        unregisterReceivers();
        stopTasks();

        Hub hub = Hub.getInstance();
        if (!hub.init(this)) {
            appendOutput("Could not initialize the Hub.");
            finish();
            return;
        }

        Hub.getInstance().removeListener(mListener);
    }

    public void onDroneBatteryChanged(int value) {
        setBatteryValue(value);
    }

    private void setBatteryValue(int value) {
        // TODO
    }

    @Override
    protected void onResume()
    {
        super.onResume();

        registerBroadcastReceivers();

        disableAllButtons();

        checkDroneConnectivity();

        Hub hub = Hub.getInstance();
        if (!hub.init(this)) {
            appendOutput("Could not initialize the Hub.");
            finish();
            return;
        }

        Hub.getInstance().addListener(mListener);
        Hub.getInstance().setLockingPolicy(Hub.LockingPolicy.NONE);
    }


    private void disableAllButtons()
    {
        droneOnNetwork = false;
    }

    protected boolean onStartFreeflight() {
        if (!droneOnNetwork) {
            return false;
        }

//        Intent connectActivity = new Intent(this, ConnectActivity.class);
//        startActivity(connectActivity);
        // TODO implement control

        return true;
    }

    public void onNetworkChanged(NetworkInfo info)
    {
        appendOutput("Network state has changed. State is: " + (info.isConnected() ? "CONNECTED" : "DISCONNECTED"));
        if (mService != null && info.isConnected()) {
            checkDroneConnectivity();
        } else {
            droneOnNetwork = false;
        }
    }

    public void connectMyo(View v) {
        appendOutput("connect myo button clicked");
        Intent intent = new Intent(mContext, ScanActivity.class);
        mContext.startActivity(intent);
    }

    public void onDroneConnected()
    {
        if (mService != null) {
//            mService.pause();
            appendOutput("Drone connected.");

            mService.requestConfigUpdate();
        }
    }


    public void onDroneDisconnected()
    {
        // Left unimplemented
    }


    public void onDroneAvailabilityChanged(boolean droneOnNetwork)
    {
        if (droneOnNetwork) {
//            Log.d(TAG, "AR.Drone connection [CONNECTED]");
            appendOutput("AR.Drone connection [ON NETWORK]");
            this.droneOnNetwork = droneOnNetwork;

//            Handler handler = new Handler();
//            handler.postDelayed(new Runnable() {
//                public void run() {
//                    if (mService != null) {
//                        mService.triggerTakeOff();
//                        appendOutput("About to take off");
//                        try {
//                            Thread.sleep(10000, 0);
//                            mService.triggerTakeOff();
//                        }
//                        catch (InterruptedException e) {
//                            e.printStackTrace();
//                        }
//                    }
//
//                    appendOutput("mService not available");
//                }
//            }, 1000);

        } else {
//            Log.d(TAG, "AR.Drone connection [DISCONNECTED]");
            appendOutput("AR.Drone connection [NOT ON NETWORK]");
        }
    }

    public void onDroneReady() {
        // TODO
        appendOutput("Drone is ready.");
        droneReady = true;

//                    Intent intent = new Intent(mContext, ControlActivity.class);
//            mContext.startActivity(intent);
        onDeviceReady();
    }

    private void onDeviceReady() {
        if(myoReady && droneReady) {
            Intent intent = new Intent(mContext, ControlActivity.class);
            mContext.startActivity(intent);
        }
    }

    @SuppressLint("NewApi")
    private void checkDroneConnectivity()
    {
        if (checkDroneConnectionTask != null && checkDroneConnectionTask.getStatus() != AsyncTask.Status.FINISHED) {
            checkDroneConnectionTask.cancel(true);
        }

        checkDroneConnectionTask = new CheckDroneNetworkAvailabilityTask() {

            @Override
            protected void onPostExecute(Boolean result) {
                onDroneAvailabilityChanged(result);
            }

        };

        if (Build.VERSION.SDK_INT >= 11) {
            checkDroneConnectionTask.executeOnExecutor(CheckDroneNetworkAvailabilityTask.THREAD_POOL_EXECUTOR, this);
        } else {
            checkDroneConnectionTask.execute(this);
        }
    }

    public void onServiceConnected(ComponentName name, IBinder service)
    {
        appendOutput("DroneService CONNECTED");
        mService = (DroneService)((DroneControlService.LocalBinder) service).getService();


        if(mService != null) {
            mService.requestDroneStatus();
            mService.resume();
        }
    }


    public void onServiceDisconnected(ComponentName name)
    {
        mService = null;
    }

    private boolean taskRunning(AsyncTask<?,?,?> checkMediaTask2)
    {
        if (checkMediaTask2 == null)
            return false;

        if (checkMediaTask2.getStatus() == AsyncTask.Status.FINISHED)
            return false;

        return true;
    }


    private void stopTasks()
    {
        if (taskRunning(checkDroneConnectionTask)) {
            checkDroneConnectionTask.cancelAnyFtpOperation();
        }

    }

    protected boolean isFreeFlightEnabled()
    {
        return droneOnNetwork;
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

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
