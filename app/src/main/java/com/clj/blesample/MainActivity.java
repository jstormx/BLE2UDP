package com.clj.blesample;

import android.Manifest;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import android.os.PowerManager;
import android.os.PowerManager.WakeLock;

import com.clj.blesample.adapter.DeviceAdapter;
import com.clj.blesample.comm.ObserverManager;
import com.clj.blesample.operation.OperationActivity;
import com.clj.fastble.BleManager;
import com.clj.fastble.callback.BleGattCallback;
import com.clj.fastble.callback.BleMtuChangedCallback;
import com.clj.fastble.callback.BleRssiCallback;
import com.clj.fastble.callback.BleNotifyCallback;
import com.clj.fastble.callback.BleScanCallback;
import com.clj.fastble.callback.BleWriteCallback;
import com.clj.fastble.callback.BleReadCallback;
import com.clj.fastble.data.BleDevice;
import com.clj.fastble.exception.BleException;
import com.clj.fastble.scan.BleScanRuleConfig;
import com.clj.blesample.udp.UDPBuild;
import com.clj.blesample.udp.MyTimeTask;
import com.clj.blesample.udp.Constants;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import java.net.DatagramPacket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimerTask;
import android.os.Handler;
import android.os.Message;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    private WakeLock wakeLock;
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final int REQUEST_CODE_OPEN_GPS = 1;
    private static final int REQUEST_CODE_PERMISSION_LOCATION = 2;

    private LinearLayout layout_setting;
    private TextView txt_setting;
    private Button btn_scan;
    private EditText et_name, et_mac, et_uuid;
    private Switch sw_auto;
    private ImageView img_loading;

    private Animation operatingAnim;
    private DeviceAdapter mDeviceAdapter;
    private ProgressDialog progressDialog;

    private UDPBuild udpBuild;
    private static final int TIMER = 999;
    private MyTimeTask task;

    private byte[] recMAC;
    private byte[] recCMD;
    private String recDATA;
    private int runFlag;
    private int tryCount =0;
    private String uuid_service = "000016f0-0000-1000-8000-00805f9b34fb";
    private String uuid_charact = "000016f2-0000-1000-8000-00805f9b34fb";

    private SharedPreferences shareData;
    private Editor editor;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        byte[] tmpData;
        shareData = getSharedPreferences("Data", MODE_PRIVATE);//创建一个给全局使用        的XML文件
        //editor = shareData.edit();//绑定sharePreferences对象
        readXMLdatas();

        setContentView(R.layout.activity_main);
        initView();
        setTimer();
        acquireWakeLock();
        BleManager.getInstance().init(getApplication());
        BleManager.getInstance()
                .enableLog(true)
                .setReConnectCount(1, 5000)
                .setConnectOverTime(20000)
                .setOperateTimeout(5000);
        udpBuild = UDPBuild.getUdpBuild();
        udpBuild.setUdpReceiveCallback(new UDPBuild.OnUDPReceiveCallbackBlock() {
            @Override
            public void OnParserComplete(DatagramPacket data) {
                //String strReceivemac = new String(data.getData(), 0, 17);
                recMAC = new byte[17];
                for (int i = 0; i < 17; i++)
                {
                    recMAC[i] = data.getData()[i];
                }

                recCMD = new byte[data.getLength() - 18];
                for (int i = 0; i < recCMD.length; i++)
                {
                    recCMD[i] = data.getData()[i+18];
                }
                runFlag = 1;// wait connect
                //SimpleDateFormat formatter = new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss");
                //Date curDate =  new Date(System.currentTimeMillis());
                //String str = formatter.format(curDate);
                //connectDirect(strReceivemac);
                // receive.append(str + ':' + strReceive + '\n');
            }
        });
        udpBuild.sendMessage("init udp");
    }

    @Override
    protected void onResume() {
        super.onResume();
        showConnectedDevice();
    }
    private void setTimer(){
        task =new MyTimeTask(1000, new TimerTask() {
            @Override
            public void run() {
                mHandler.sendEmptyMessage(TIMER);
                //或者发广播，启动服务都是可以的
            }
        });
        task.start();
    }

    private Handler mHandler = new Handler(){
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what){
                case TIMER:
                    //在此执行定时操作
                    if(runFlag != 0) {
                        Log.d("UDPBuild", "inTIMER...runFlag=" + runFlag + " recMAC=" + recMAC + " recCMD=" + recCMD);
                        switch(runFlag){
                            case 1: //连接设备
                                tryCount++;
                                if(tryCount >= 5)
                                {
                                    runFlag = 0;
                                    recMAC = null;
                                    tryCount=0;
                                }else {
                                    if(recMAC != null)
                                    connectDirect(new String(recMAC, 0, 17));
                                }
                                break;
                            case 2: //允许uuid notify
                                tryCount=0;
                                UUIDnotify();
                                break;
                            case 3: //写数据
                                UUIDwrite(recCMD);
                                break;
                            case 4: //读数据
                                //UUIDread();
                                break;
                            case 5: //断开连接
                                BleManager.getInstance().disconnectAllDevice();

                                runFlag=0;
                                break;

                            default:

                                break;
                        }
                    }
                    break;
                default:
                    break;
            }
        }
    };

    private void stopTimer(){
        task.stop();
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        BleManager.getInstance().disconnectAllDevice();
        BleManager.getInstance().destroy();
        stopTimer();
        releaseWakeLock();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_scan:
                if (btn_scan.getText().equals(getString(R.string.start_scan))) {
                    checkPermissions();
                } else if (btn_scan.getText().equals(getString(R.string.stop_scan))) {
                    BleManager.getInstance().cancelScan();
                }
                break;

            case R.id.txt_setting:
                if (layout_setting.getVisibility() == View.VISIBLE) {
                    layout_setting.setVisibility(View.GONE);
                    txt_setting.setText(getString(R.string.expand_search_settings));
                } else {
                    layout_setting.setVisibility(View.VISIBLE);
                    txt_setting.setText(getString(R.string.retrieve_search_settings));
                }
                break;
        }
    }
    private void readXMLdatas() {
        Constants.SOCKET_HOST = shareData.getString("SOCKET_HOST",Constants.SOCKET_HOST);//读取数据
        Constants.SOCKET_UDP_PORT = shareData.getInt("SOCKET_UDP_PORT",Constants.SOCKET_UDP_PORT);//读取数据
        Constants.LINSEN_SOCKET_UDP_PORT = shareData.getInt("LINSEN_SOCKET_UDP_PORT",Constants.LINSEN_SOCKET_UDP_PORT);//读取数据

    }
    private void saveXMLdatas() {
        editor = shareData.edit();//绑定sharePreferences对象
        editor.putString("SOCKET_HOST",Constants.SOCKET_HOST);
        editor.putInt("SOCKET_UDP_PORT",Constants.SOCKET_UDP_PORT);
        editor.putInt("LINSEN_SOCKET_UDP_PORT",Constants.LINSEN_SOCKET_UDP_PORT);
        editor.commit();//注意保存数据
    }

    private void initView() {
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        btn_scan = (Button) findViewById(R.id.btn_scan);
        btn_scan.setText(getString(R.string.start_scan));
        btn_scan.setOnClickListener(this);

        et_name = (EditText) findViewById(R.id.et_name);
        et_name.setText(Constants.SOCKET_HOST);
        et_mac = (EditText) findViewById(R.id.et_mac);
        et_mac.setText(String.valueOf(Constants.SOCKET_UDP_PORT));
        et_uuid = (EditText) findViewById(R.id.et_uuid);
        et_uuid.setText(String.valueOf(Constants.LINSEN_SOCKET_UDP_PORT));
        sw_auto = (Switch) findViewById(R.id.sw_auto);

        layout_setting = (LinearLayout) findViewById(R.id.layout_setting);
        txt_setting = (TextView) findViewById(R.id.txt_setting);
        txt_setting.setOnClickListener(this);
        layout_setting.setVisibility(View.GONE);
        txt_setting.setText(getString(R.string.expand_search_settings));

        img_loading = (ImageView) findViewById(R.id.img_loading);
        operatingAnim = AnimationUtils.loadAnimation(this, R.anim.rotate);
        operatingAnim.setInterpolator(new LinearInterpolator());
        progressDialog = new ProgressDialog(this);

        mDeviceAdapter = new DeviceAdapter(this);
        mDeviceAdapter.setOnDeviceClickListener(new DeviceAdapter.OnDeviceClickListener() {
            @Override
            public void onConnect(BleDevice bleDevice) {
                if (!BleManager.getInstance().isConnected(bleDevice)) {
                    BleManager.getInstance().cancelScan();
                    connect(bleDevice);
                }
            }

            @Override
            public void onDisConnect(final BleDevice bleDevice) {
                if (BleManager.getInstance().isConnected(bleDevice)) {
                    BleManager.getInstance().disconnect(bleDevice);
                }
            }

            @Override
            public void onDetail(BleDevice bleDevice) {
                if (BleManager.getInstance().isConnected(bleDevice)) {
                    Intent intent = new Intent(MainActivity.this, OperationActivity.class);
                    intent.putExtra(OperationActivity.KEY_DATA, bleDevice);
                    startActivity(intent);
                }
            }
        });
        ListView listView_device = (ListView) findViewById(R.id.list_device);
        listView_device.setAdapter(mDeviceAdapter);
    }

    private void showConnectedDevice() {
        List<BleDevice> deviceList = BleManager.getInstance().getAllConnectedDevice();
        mDeviceAdapter.clearConnectedDevice();
        for (BleDevice bleDevice : deviceList) {
            mDeviceAdapter.addDevice(bleDevice);
        }
        mDeviceAdapter.notifyDataSetChanged();
    }
/*
    private void setScanRule() {
        String[] uuids;
        String str_uuid = et_uuid.getText().toString();
        if (TextUtils.isEmpty(str_uuid)) {
            uuids = null;
        } else {
            uuids = str_uuid.split(",");
        }
        UUID[] serviceUuids = null;
        if (uuids != null && uuids.length > 0) {
            serviceUuids = new UUID[uuids.length];
            for (int i = 0; i < uuids.length; i++) {
                String name = uuids[i];
                String[] components = name.split("-");
                if (components.length != 5) {
                    serviceUuids[i] = null;
                } else {
                    serviceUuids[i] = UUID.fromString(uuids[i]);
                }
            }
        }

        String[] names;
        String str_name = et_name.getText().toString();
        if (TextUtils.isEmpty(str_name)) {
            names = null;
        } else {
            names = str_name.split(",");
        }

        String mac = et_mac.getText().toString();

        boolean isAutoConnect = sw_auto.isChecked();

        BleScanRuleConfig scanRuleConfig = new BleScanRuleConfig.Builder()
                .setServiceUuids(serviceUuids)      // 只扫描指定的服务的设备，可选
                .setDeviceName(true, names)   // 只扫描指定广播名的设备，可选
                .setDeviceMac(mac)                  // 只扫描指定mac的设备，可选
                .setAutoConnect(isAutoConnect)      // 连接时的autoConnect参数，可选，默认false
                .setScanTimeOut(10000)              // 扫描超时时间，可选，默认10秒
                .build();
        BleManager.getInstance().initScanRule(scanRuleConfig);
    }
*/
    private void setScanRule() {
    String str_uuid = et_uuid.getText().toString();
    if (TextUtils.isEmpty(str_uuid)) {

    } else {
        Constants.LINSEN_SOCKET_UDP_PORT = Integer.parseInt(str_uuid);
    }
    UUID[] serviceUuids = null;

    String[] names = null;

    String str_name = et_name.getText().toString();
    if (TextUtils.isEmpty(str_name)) {

    } else {
        Constants.SOCKET_HOST = str_name;
    }

    String mac = null;//et_mac.getText().toString();

        String str_mac = et_mac.getText().toString();
        if (TextUtils.isEmpty(str_mac)) {

        } else {
            Constants.SOCKET_UDP_PORT = Integer.parseInt(str_mac);
        }
        saveXMLdatas();
    boolean isAutoConnect = sw_auto.isChecked();

    BleScanRuleConfig scanRuleConfig = new BleScanRuleConfig.Builder()
            .setServiceUuids(serviceUuids)      // 只扫描指定的服务的设备，可选
            .setDeviceName(true, names)   // 只扫描指定广播名的设备，可选
            .setDeviceMac(mac)                  // 只扫描指定mac的设备，可选
            .setAutoConnect(isAutoConnect)      // 连接时的autoConnect参数，可选，默认false
            .setScanTimeOut(10000)              // 扫描超时时间，可选，默认10秒
            .build();
    BleManager.getInstance().initScanRule(scanRuleConfig);
}
    private void startScan() {
        BleManager.getInstance().scan(new BleScanCallback() {
            @Override
            public void onScanStarted(boolean success) {
                mDeviceAdapter.clearScanDevice();
                mDeviceAdapter.notifyDataSetChanged();
                img_loading.startAnimation(operatingAnim);
                img_loading.setVisibility(View.VISIBLE);
                btn_scan.setText(getString(R.string.stop_scan));
            }

            @Override
            public void onLeScan(BleDevice bleDevice) {
                super.onLeScan(bleDevice);
            }

            @Override
            public void onScanning(BleDevice bleDevice) {
                mDeviceAdapter.addDevice(bleDevice);
                mDeviceAdapter.notifyDataSetChanged();
            }

            @Override
            public void onScanFinished(List<BleDevice> scanResultList) {
                img_loading.clearAnimation();
                img_loading.setVisibility(View.INVISIBLE);
                btn_scan.setText(getString(R.string.start_scan));
            }
        });
    }
    private void UUIDwrite(byte[] data) {
        BleDevice bleDevice = mDeviceAdapter.getItem(0);
        if(bleDevice != null) {
            BleManager.getInstance().write(
                    bleDevice,
                    uuid_service,
                    uuid_charact,
                    data,
                    new BleWriteCallback() {
                        @Override
                        public void onWriteSuccess(int current, int total, byte[] justWrite) {
                            if(runFlag == 3) {
                                runFlag = 4;//发送完成，带读取数据
                            }

                        }

                        @Override
                        public void onWriteFailure(BleException exception) {

                        }
                    });
        }
        else {
            runFlag = 1;//设备断开，等待连接
        }
    }
    private void UUIDread() {
        BleDevice bleDevice = mDeviceAdapter.getItem(0);
        if(bleDevice != null) {
            BleManager.getInstance().read(
                    bleDevice,
                    uuid_service,
                    uuid_charact,
                    new BleReadCallback() {
                        @Override
                        public void onReadSuccess(byte[] data) {
                            byte[] recMACbytes = recMAC;
                            byte[] recDATAbytes = new byte[recMAC.length+data.length+1];
                            for(int i =0;i<recMAC.length;i++)
                            {
                                recDATAbytes[i]=recMACbytes[i];
                            }
                            recDATAbytes[recMAC.length]=0x20;
                            for(int i =0;i<data.length;i++)
                            {
                                recDATAbytes[recMAC.length+1+i]=data[i];
                            }

                            //String strRec = new String(data,0,data.length);
                            //recDATA = recMAC+" "+strRec;
                            runFlag = 5;//以读取发送
                            udpBuild.sendMessageBytes(recDATAbytes);

                        }

                        @Override
                        public void onReadFailure(BleException exception) {

                        }
                    });
        }
        else {
            runFlag = 1;//设备断开，等待连接
        }
    }

    private void UUIDnotify() {
        BleDevice bleDevice = mDeviceAdapter.getItem(0);
        if(bleDevice != null) {
            BleManager.getInstance().notify(
                    bleDevice,
                    uuid_service,
                    uuid_charact,
                    new BleNotifyCallback() {
                        @Override
                        public void onNotifySuccess() {

                            runFlag = 3;//已注册，等待写入
                        }

                        @Override
                        public void onNotifyFailure(BleException exception) {

                        }

                        @Override
                        public void onCharacteristicChanged(byte[] data) {
                            byte[] recMACbytes = recMAC;
                            byte[] recDATAbytes = new byte[recMAC.length+data.length+1];
                            for(int i =0;i<recMAC.length;i++)
                            {
                                recDATAbytes[i]=recMACbytes[i];
                            }
                            recDATAbytes[recMAC.length]=0x20;
                            for(int i =0;i<data.length;i++)
                            {
                                recDATAbytes[recMAC.length+1+i]=data[i];
                            }

                            //String strRec = new String(data,0,data.length);
                            //recDATA = recMAC+" "+strRec;
                            runFlag = 5;//以读取发送
                            udpBuild.sendMessageBytes(recDATAbytes);

                        }
                    });
        }
        else {
            runFlag = 1;//设备断开，等待连接
        }
    }
    private void connectDirect(String mac) {
        BleManager.getInstance().connect(mac, new BleGattCallback() {
            @Override
            public void onStartConnect() {
               // progressDialog.show();
            }

            @Override
            public void onConnectFail(BleDevice bleDevice, BleException exception) {
                //img_loading.clearAnimation();
                //img_loading.setVisibility(View.INVISIBLE);
                //btn_scan.setText(getString(R.string.start_scan));
                //progressDialog.dismiss();
                Toast.makeText(MainActivity.this, getString(R.string.connect_fail), Toast.LENGTH_LONG).show();
            }

            @Override
            public void onConnectSuccess(BleDevice bleDevice, BluetoothGatt gatt, int status) {
                //progressDialog.dismiss();
                mDeviceAdapter.addDevice(bleDevice);
                mDeviceAdapter.notifyDataSetChanged();
                runFlag = 2;//connected
                Log.d("UDPBuild", "onConnectSuccess...");
            }

            @Override
            public void onDisConnected(boolean isActiveDisConnected, BleDevice bleDevice, BluetoothGatt gatt, int status) {
                //progressDialog.dismiss();

                mDeviceAdapter.removeDevice(bleDevice);
                mDeviceAdapter.notifyDataSetChanged();

                if (isActiveDisConnected) {
                    Toast.makeText(MainActivity.this, getString(R.string.active_disconnected), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, getString(R.string.disconnected), Toast.LENGTH_LONG).show();
                    ObserverManager.getInstance().notifyObserver(bleDevice);
                }

            }
        });
    }

    private void connect(final BleDevice bleDevice) {
        BleManager.getInstance().connect(bleDevice, new BleGattCallback() {
            @Override
            public void onStartConnect() {
                progressDialog.show();
            }

            @Override
            public void onConnectFail(BleDevice bleDevice, BleException exception) {
                img_loading.clearAnimation();
                img_loading.setVisibility(View.INVISIBLE);
                btn_scan.setText(getString(R.string.start_scan));
                progressDialog.dismiss();
                Toast.makeText(MainActivity.this, getString(R.string.connect_fail), Toast.LENGTH_LONG).show();
            }

            @Override
            public void onConnectSuccess(BleDevice bleDevice, BluetoothGatt gatt, int status) {
                progressDialog.dismiss();
                mDeviceAdapter.addDevice(bleDevice);
                mDeviceAdapter.notifyDataSetChanged();
            }

            @Override
            public void onDisConnected(boolean isActiveDisConnected, BleDevice bleDevice, BluetoothGatt gatt, int status) {
                progressDialog.dismiss();

                mDeviceAdapter.removeDevice(bleDevice);
                mDeviceAdapter.notifyDataSetChanged();

                if (isActiveDisConnected) {
                    Toast.makeText(MainActivity.this, getString(R.string.active_disconnected), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, getString(R.string.disconnected), Toast.LENGTH_LONG).show();
                    ObserverManager.getInstance().notifyObserver(bleDevice);
                }

            }
        });
    }

    private void readRssi(BleDevice bleDevice) {
        BleManager.getInstance().readRssi(bleDevice, new BleRssiCallback() {
            @Override
            public void onRssiFailure(BleException exception) {
                Log.i(TAG, "onRssiFailure" + exception.toString());
            }

            @Override
            public void onRssiSuccess(int rssi) {
                Log.i(TAG, "onRssiSuccess: " + rssi);
            }
        });
    }

    private void setMtu(BleDevice bleDevice, int mtu) {
        BleManager.getInstance().setMtu(bleDevice, mtu, new BleMtuChangedCallback() {
            @Override
            public void onSetMTUFailure(BleException exception) {
                Log.i(TAG, "onsetMTUFailure" + exception.toString());
            }

            @Override
            public void onMtuChanged(int mtu) {
                Log.i(TAG, "onMtuChanged: " + mtu);
            }
        });
    }

    @Override
    public final void onRequestPermissionsResult(int requestCode,
                                                 @NonNull String[] permissions,
                                                 @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case REQUEST_CODE_PERMISSION_LOCATION:
                if (grantResults.length > 0) {
                    for (int i = 0; i < grantResults.length; i++) {
                        if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                            onPermissionGranted(permissions[i]);
                        }
                    }
                }
                break;
        }
    }

    private void checkPermissions() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, getString(R.string.please_open_blue), Toast.LENGTH_LONG).show();
            return;
        }

        String[] permissions = {Manifest.permission.ACCESS_FINE_LOCATION};
        List<String> permissionDeniedList = new ArrayList<>();
        for (String permission : permissions) {
            int permissionCheck = ContextCompat.checkSelfPermission(this, permission);
            if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                onPermissionGranted(permission);
            } else {
                permissionDeniedList.add(permission);
            }
        }
        if (!permissionDeniedList.isEmpty()) {
            String[] deniedPermissions = permissionDeniedList.toArray(new String[permissionDeniedList.size()]);
            ActivityCompat.requestPermissions(this, deniedPermissions, REQUEST_CODE_PERMISSION_LOCATION);
        }
    }

    private void onPermissionGranted(String permission) {
        switch (permission) {
            case Manifest.permission.ACCESS_FINE_LOCATION:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !checkGPSIsOpen()) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.notifyTitle)
                            .setMessage(R.string.gpsNotifyMsg)
                            .setNegativeButton(R.string.cancel,
                                    new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            finish();
                                        }
                                    })
                            .setPositiveButton(R.string.setting,
                                    new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                                            startActivityForResult(intent, REQUEST_CODE_OPEN_GPS);
                                        }
                                    })

                            .setCancelable(false)
                            .show();
                } else {
                    setScanRule();
                    startScan();
                }
                break;
        }
    }

    private boolean checkGPSIsOpen() {
        LocationManager locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null)
            return false;
        return locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER);
    }
    //获取电源锁，保持该服务在屏幕熄灭时仍然获取CPU时，保持运行
    private void acquireWakeLock()
    {
        if (null == wakeLock)
        {
            PowerManager pm = (PowerManager)this.getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK|PowerManager.ON_AFTER_RELEASE, "PostLocationService");
            if (null != wakeLock)
            {
                wakeLock.acquire();
            }
        }
    }

    //释放设备电源锁
    private void releaseWakeLock()
    {
        if (null != wakeLock)
        {
            wakeLock.release();
            wakeLock = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_OPEN_GPS) {
            if (checkGPSIsOpen()) {
                setScanRule();
                startScan();
            }
        }
    }

}
