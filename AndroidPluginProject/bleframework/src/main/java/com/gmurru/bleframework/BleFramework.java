package com.gmurru.bleframework;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.pm.PackageManager;

import android.nfc.Tag;
import android.os.IBinder;
import android.os.Bundle;
import android.os.Handler;

import android.util.Log;
import com.unity3d.player.UnityPlayer;

import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;
import android.app.Activity;

/**
 * Created by Fenix on 24/09/2017.
 */

public class BleFramework
{
    private Activity _unityActivity;
    /*
    Singleton instance.
    */
    private static volatile BleFramework _instance;

    /*
    Definition of the BLE Unity message methods used to communicate back with Unity.
    */
    public static final String BLEUnityMessageName_OnBleDidInitialize = "OnBleDidInitialize";
    public static final String BLEUnityMessageName_OnBleDidConnect = "OnBleDidConnect";
    public static final String BLEUnityMessageName_OnBleDidCompletePeripheralScan = "OnBleDidCompletePeripheralScan";
    public static final String BLEUnityMessageName_OnBleDidDisconnect = "OnBleDidDisconnect";
    public static final String BLEUnityMessageName_OnBleDidReceiveData = "OnBleDidReceiveData";

    /*
    Static variables
    */
    private static final String TAG = BleFramework.class.getSimpleName();
    private static final int REQUEST_ENABLE_BT = 1;
    private static final long SCAN_PERIOD = 3000;
    public static final int REQUEST_CODE = 30;

    /*
    List containing all the discovered bluetooth devices
    */
    private List<BluetoothDevice> _mDevice = new ArrayList<BluetoothDevice>();

    /*
    The latest received data
    */
    private byte[] _dataRx = new byte[3];

    /*
    Bluetooth service
    */
    private RBLService _mBluetoothLeService;

    private Map<UUID, BluetoothGattCharacteristic> _map = new HashMap<UUID, BluetoothGattCharacteristic>();

    /*
    Bluetooth adapter
    */
    private BluetoothAdapter _mBluetoothAdapter;

    /*
    Bluetooth device address and name to which the app is currently connected
    */
    private BluetoothDevice _device;
    private String _mDeviceAddress;
    private String _mDeviceName;

    /*
    Boolean variables used to estabilish the status of the connection
    */
    private boolean _connState = false;
    private boolean _flag = true;
    private boolean _searchingDevice = false;

    private Intent _gattServiceIntent;

    /*
    The service connection containing the actions definition onServiceConnected and onServiceDisconnected
    */
    private final ServiceConnection _mServiceConnection = new ServiceConnection()
    {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service)
        {
            BleFramework.this._mBluetoothLeService = ((RBLService.LocalBinder)service).getService();
            if (!BleFramework.this._mBluetoothLeService.initialize())
            {
                Log.e(BleFramework.TAG, "onServiceConnected: Unable to initialize Bluetooth");
            }
            else
            {
                Log.d(BleFramework.TAG, "onServiceConnected: Bluetooth initialized correctly");
                BleFramework.this._mBluetoothLeService.connect(BleFramework.this._mDeviceAddress);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName)
        {
            Log.d(BleFramework.TAG, "onServiceDisconnected: Bluetooth disconnected");
            BleFramework.this._mBluetoothLeService = null;
        }
    };

    /*
    Callback called when the scan of bluetooth devices is finished
    */
    private BluetoothAdapter.LeScanCallback _mLeScanCallback = new BluetoothAdapter.LeScanCallback()
    {
        @Override
        public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord)
        {
            BleFramework.this._unityActivity.runOnUiThread(new Runnable()
            {
                public void run()
                {
                    Log.d(BleFramework.TAG, "onLeScan: run()");
                    if ((device != null) && (device.getName() != null))
                    {
                        Log.d(BleFramework.TAG, "onLeScan: device is not null");
                        if (BleFramework.this._mDevice.indexOf(device) == -1)
                        {
                            Log.d(BleFramework.TAG, "onLeScan: add device to _mDevice");
                            BleFramework.this._mDevice.add(device);
                        }
                    }
                    else
                    {
                        Log.e(BleFramework.TAG, "onLeScan: device is null");
                    }
                }
            });
        }
    };



    /*
    Callback called when the bluetooth device receive relevant updates about connection, disconnection, service discovery, data available, rssi update
    */
    private final BroadcastReceiver _mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String action = intent.getAction();
            if ("ACTION_GATT_CONNECTED".equals(action))
            {
                BleFramework.this._connState = true;

                Log.d(BleFramework.TAG, "Connection estabilished with: " + BleFramework.this._mDeviceAddress);
            }
            else if ("ACTION_GATT_DISCONNECTED".equals(action))
            {
                BleFramework.this._connState = false;

                UnityPlayer.UnitySendMessage("BLEControllerEventHandler", "OnBleDidDisconnect", "Success");
                BleFramework._instance._unityActivity.unbindService(BleFramework._instance._mServiceConnection);
                Log.d(BleFramework.TAG, "Connection lost");
            }
            else if ("ACTION_GATT_SERVICES_DISCOVERED".equals(action))
            {
                Log.d(BleFramework.TAG, "Service discovered! Registering GattService ACTION_GATT_SERVICES_DISCOVERED");
                BluetoothGattService service = BleFramework.this._mBluetoothLeService.getSupportedGattService();
                BleFramework.this.getGattService(BleFramework.this._mBluetoothLeService.getSupportedGattService());
                Log.d(BleFramework.TAG, "Registered UUID:" + service.getUuid().toString());
                Log.d(BleFramework.TAG, "Send BLEUnityMessageName_OnBleDidConnect success signal to Unity");
                UnityPlayer.UnitySendMessage("BLEControllerEventHandler", "OnBleDidConnect", "Success");
            }
            else if ("ACTION_DATA_AVAILABLE".equals(action))
            {
                Log.d(BleFramework.TAG, "New Data received by the server");
                BleFramework.this._dataRx = intent.getByteArrayExtra("EXTRA_DATA");

                UnityPlayer.UnitySendMessage("BLEControllerEventHandler", "OnBleDidReceiveData", new String(BleFramework.this._dataRx));
            }
            else if ("ACTION_GATT_RSSI".equals(action))
            {
                String rssiData = intent.getStringExtra("EXTRA_DATA");
                Log.d(BleFramework.TAG, "RSSI: " + rssiData);
            }
        }
    };

    /*
    METHODS DEFINITION
    */
    public static BleFramework getInstance(Activity activity)
    {
        if (_instance == null) {
            synchronized (BleFramework.class)
            {
                if (_instance == null)
                {
                    Log.d(TAG, "BleFramework: Creation of _instance");
                    _instance = new BleFramework(activity);
                }
            }
        }
        return _instance;
    }

    public BleFramework(Activity activity)
    {
        Log.d(TAG, "BleFramework: saving unityActivity in private var.");
        this._unityActivity = activity;
    }

    /*
    Method used to create a filter for the bluetooth actions that you like to receive
    */
    private static IntentFilter makeGattUpdateIntentFilter()
    {
        IntentFilter intentFilter = new IntentFilter();

        intentFilter.addAction("ACTION_GATT_CONNECTED");
        intentFilter.addAction("ACTION_GATT_DISCONNECTED");
        intentFilter.addAction("ACTION_GATT_SERVICES_DISCOVERED");
        intentFilter.addAction("ACTION_DATA_AVAILABLE");

        return intentFilter;
    }

    /*
    Start reading RSSI: information about bluetooth signal intensity
    */
    
    private void startReadRssi()
    {
        new Thread()
        {
            public void run()
            {
                while (_connState)
                {
                    _mBluetoothLeService.readRssi();
                    try
                    {
                        sleep(500);
                    }
                    catch (InterruptedException e)
                    {
                        e.printStackTrace();
                    }
                }
            };
        }.start();
    }
    
    /*
    Method used to initialize the characteristic for data transmission
    */

    private void getGattService(BluetoothGattService gattService)
    {
        if (gattService == null)
        {
            Log.d(TAG, "Service was null!");
            return;
        }
        Log.d(TAG, "Available Characteristics:");
        List<BluetoothGattCharacteristic> characteristics = gattService.getCharacteristics();
        for(BluetoothGattCharacteristic c : characteristics )
        {
            Log.d(TAG, "Found: " +  c.getUuid().toString());
            if(c.getUuid().toString().equalsIgnoreCase(RBLGattAttributes.BLE_SHIELD_RX))
            {
                Log.d(TAG, "Found characteristic. Adding characteristic to map");
                this._map.put(c.getUuid(), c);
                Log.d(TAG, "Setting Characteristic Notification");
                this._mBluetoothLeService.setCharacteristicNotification(c, true);
                Log.d(TAG, "Reading Characteristic");
                this._mBluetoothLeService.readCharacteristic(c);
                return;
            }
        }

        Log.d(BleFramework.TAG, "Characteristic failed :: Not found");

/*
        UUID uuid = RBLService.UUID_BLE_SHIELD_RX;
        Log.d(TAG, "Trying to get UUID: " +  uuid.toString());
        BluetoothGattCharacteristic characteristic = gattService.getCharacteristic(RBLService.UUID_BLE_SHIELD_RX);
        if (characteristic != null)
        {
            Log.d(TAG, "Found characteristic. Adding characteristic to map");
            this._map.put(characteristic.getUuid(), characteristic);
            Log.d(TAG, "Setting Characteristic Notification");
            this._mBluetoothLeService.setCharacteristicNotification(characteristic, true);
            Log.d(TAG, "Reading Characteristic");
            this._mBluetoothLeService.readCharacteristic(characteristic);
        }
        else
        {
            Log.d(BleFramework.TAG, "Characteristic failed :: Not found");
        }*/
    }


    /*
    Method used to scan for available bluetooth low energy devices
    */
    private void scanLeDevice()
    {
        new Thread()
        {
            public void run()
            {
                BleFramework.this._searchingDevice = true;
                Log.d(BleFramework.TAG, "scanLeDevice: _mBluetoothAdapter StartLeScan");
                BleFramework.this._mBluetoothAdapter.startLeScan(BleFramework.this._mLeScanCallback);
                try
                {
                    Log.d(BleFramework.TAG, "scanLeDevice: scan for 3 seconds then abort");
                    Thread.sleep(3000L);
                }
                catch (InterruptedException e)
                {
                    Log.d(BleFramework.TAG, "scanLeDevice: InterruptedException");
                    e.printStackTrace();
                }
                Log.d(BleFramework.TAG, "scanLeDevice: _mBluetoothAdapter StopLeScan");
                BleFramework.this._mBluetoothAdapter.stopLeScan(BleFramework.this._mLeScanCallback);
                BleFramework.this._searchingDevice = false;
                Log.d(BleFramework.TAG, "scanLeDevice: _mDevice size is " + BleFramework.this._mDevice.size());

                UnityPlayer.UnitySendMessage("BLEControllerEventHandler", "OnBleDidCompletePeripheralScan", "Success");
            }
        }.start();
    }


    private void unregisterBleUpdatesReceiver()
    {
        Log.d(TAG,"unregisterBleUpdatesReceiver:");
        _unityActivity.unregisterReceiver(_mGattUpdateReceiver);
    }

    private void registerBleUpdatesReceiver()
    {
        Log.d(TAG, "registerBleUpdatesReceiver:");
        if (!this._mBluetoothAdapter.isEnabled()) {
            Log.d(TAG, "registerBleUpdatesReceiver: WARNING: _mBluetoothAdapter is not enabled!");
        }
        Log.d(TAG, "registerBleUpdatesReceiver: registerReceiver");
        this._unityActivity.registerReceiver(this._mGattUpdateReceiver, makeGattUpdateIntentFilter());
    }

    //I need a reference to the Unity activity in order to use UnityPlayer.UnitySendMessage

    /*
    Singleton initialization. Create an instance of BleFramework class only if it doesn't exist yet.
    */
    /*
    public static BleFramework getInstance()
    {
        if (_instance == null )
        {
            synchronized (BleFramework.class)
            {
                if (_instance == null)
                {
                    Log.d(TAG, "BleFramework: Creation of _instance");
                    _instance = new BleFramework();
                }
            }
        }

        return _instance;
    }
    */

    /*
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // User chose not to enable Bluetooth.
        if (requestCode == REQUEST_ENABLE_BT
                && resultCode == Activity.RESULT_CANCELED) {
            //finish();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        Log.d(TAG,"onCreate is being launched");


        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
        {
            Log.d(TAG,"onCreate: fail: missing FEATURE_BLUETOOTH_LE");
            UnityPlayer.UnitySendMessage("BLEControllerEventHandler", BLEUnityMessageName_OnBleDidInitialize, "Fail: missing FEATURE_BLUETOOTH_LE");
            //finish();
        }

        final BluetoothManager mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        _mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (_mBluetoothAdapter == null)
        {
            Log.d(TAG,"onCreate: fail: _mBluetoothAdapter is null");
            UnityPlayer.UnitySendMessage("BLEControllerEventHandler", BLEUnityMessageName_OnBleDidInitialize, "Fail: Context.BLUETOOTH_SERVICE");
            //finish();
            return;
        }

        Log.d(TAG,"onCreate: _mBluetoothAdapter correctly initialized");
        Intent gattServiceIntent = new Intent(BleFramework.this, RBLService.class);
        bindService(gattServiceIntent, _mServiceConnection, BIND_AUTO_CREATE);

        Log.d(TAG,"onCreate: sending BLEUnityMessageName_OnBleDidInitialize success");
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG,"onStop: unregisterReceiver");
        _unityActivity.unregisterReceiver(_mGattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG,"onDestroy: unbindService");
        if (_mServiceConnection != null)
            unbindService(_mServiceConnection);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG,"onResume:");
        if (!_mBluetoothAdapter.isEnabled())
        {
            Log.d(TAG,"onResume: startActivityForResult: REQUEST_ENABLE_BT");
            Intent enableBtIntent = new Intent(
                    BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }
        Log.d(TAG,"onResume: registerReceiver");
        _unityActivity.registerReceiver(_mGattUpdateReceiver, makeGattUpdateIntentFilter());
    }
    */

    /*
    Public methods that can be directly called by Unity
    */
    public void _InitBLEFramework()
    {
        System.out.println("Android Executing: _InitBLEFramework");
        if (!this._unityActivity.getPackageManager().hasSystemFeature("android.hardware.bluetooth_le"))
        {
            Log.d(TAG, "onCreate: fail: missing FEATURE_BLUETOOTH_LE");
            UnityPlayer.UnitySendMessage("BLEControllerEventHandler", "OnBleDidInitialize", "Fail: missing FEATURE_BLUETOOTH_LE");

            return;
        }
        BluetoothManager mBluetoothManager = (BluetoothManager)this._unityActivity.getSystemService("bluetooth");
        this._mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (this._mBluetoothAdapter == null)
        {
            Log.d(TAG, "onCreate: fail: _mBluetoothAdapter is null");
            UnityPlayer.UnitySendMessage("BLEControllerEventHandler", "OnBleDidInitialize", "Fail: Context.BLUETOOTH_SERVICE");

            return;
        }
        registerBleUpdatesReceiver();

        Log.d(TAG, "onCreate: _mBluetoothAdapter correctly initialized");
        UnityPlayer.UnitySendMessage("BLEControllerEventHandler", "OnBleDidInitialize", "Success");

    }

    public void _ScanForPeripherals()
    {
        Log.d(TAG, "_ScanForPeripherals: Launching scanLeDevice");
        scanLeDevice();
    }

    public boolean _IsDeviceConnected()
    {
        Log.d(TAG, "_IsDeviceConnected");
        return this._connState;
    }

    public boolean _SearchDeviceDidFinish()
    {
        Log.d(TAG, "_SearchDeviceDidFinish");
        return !this._searchingDevice;
    }

    public String _GetListOfDevices()
    {
        String jsonListString;
        if (this._mDevice.size() > 0)
        {
            Log.d(TAG, "_GetListOfDevices");
            String[] uuidsArray = new String[this._mDevice.size()];
            for (int i = 0; i < this._mDevice.size(); i++)
            {
                BluetoothDevice bd = (BluetoothDevice)this._mDevice.get(i);

                uuidsArray[i] = bd.getAddress();
            }
            Log.d(TAG, "_GetListOfDevices: Building JSONArray");
            JSONArray uuidsJSON = new JSONArray(Arrays.asList(uuidsArray));
            Log.d(TAG, "_GetListOfDevices: Building JSONObject");
            JSONObject dataUuidsJSON = new JSONObject();
            try
            {
                Log.d(TAG, "_GetListOfDevices: Try inserting uuuidsJSON array in the JSONObject");
                dataUuidsJSON.put("data", uuidsJSON);
            }
            catch (JSONException e)
            {
                Log.e(TAG, "_GetListOfDevices: JSONException");
                e.printStackTrace();
            }
            jsonListString = dataUuidsJSON.toString();

            Log.d(TAG, "_GetListOfDevices: sending found devices in JSON: " + jsonListString);
        }
        else
        {
            jsonListString = "NO DEVICE FOUND";
            Log.d(TAG, "_GetListOfDevices: no device was found");
        }
        return jsonListString;
    }

    public boolean _ConnectPeripheralAtIndex(int peripheralIndex)
    {
        Log.d(TAG, "_ConnectPeripheralAtIndex: " + peripheralIndex);
        BluetoothDevice device = (BluetoothDevice)this._mDevice.get(peripheralIndex);

        this._mDeviceAddress = device.getAddress();
        this._mDeviceName = device.getName();

        Intent gattServiceIntent = new Intent(this._unityActivity, RBLService.class);
        this._gattServiceIntent = gattServiceIntent;
        this._unityActivity.bindService(gattServiceIntent, this._mServiceConnection, 1);

        return true;
    }

    public boolean _ConnectPeripheral(String peripheralID)
    {
        Log.d(TAG, "_ConnectPeripheral: " + peripheralID);
        for (BluetoothDevice device : this._mDevice) {
            if (device.getAddress().equals(peripheralID))
            {
                this._mDeviceAddress = device.getAddress();
                this._mDeviceName = device.getName();

                Intent gattServiceIntent = new Intent(this._unityActivity, RBLService.class);
                this._gattServiceIntent = gattServiceIntent;
                this._unityActivity.bindService(gattServiceIntent, this._mServiceConnection, 1);

                return true;
            }
        }
        return false;
    }

    public byte[] _GetData()
    {
        Log.d(TAG, "_GetData: ");
        return this._dataRx;
    }

    public boolean _Disconnect()
    {
        this._mBluetoothLeService.disconnect();
        this._mBluetoothLeService.close();
        this._unityActivity.unbindService(this._mServiceConnection);
        return true;
    }

    public void _SendData(byte[] data)
    {
        //Log.d(TAG, "_SendData: ");

        //Log.d(BleFramework.TAG, "Trying to get service with UUID:" + RBLService.UUID_BLE_SHIELD_RX);
        BluetoothGattCharacteristic characteristic = this._map.get(RBLService.UUID_BLE_SHIELD_RX);
        //Log.d(TAG, "Got characteristic: " + characteristic.getUuid().toString());

        //Log.d(TAG, "Set data in the _characteristicTx");
        //byte[] tx = hexStringToByteArray("fefefe");
        characteristic.setValue(data);

        //Log.d(TAG, "Write _characteristicTx in the _mBluetoothLeService: " + data);
        if (this._mBluetoothLeService == null) {
            Log.d(TAG, "_mBluetoothLeService is null");
        }
        boolean wasSuccessful = this._mBluetoothLeService.writeCharacteristic(characteristic);

        //Log.d(TAG, "Wrote to Characteristic successfully?: " + wasSuccessful);

    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[(i / 2)] = ((byte)((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16)));
        }
        return data;
    }
}
