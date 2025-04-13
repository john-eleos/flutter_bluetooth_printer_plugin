package id.flutter.plugins;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.content.BroadcastReceiver;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.*;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;

public class FlutterBluetoothPrinterPlugin implements FlutterPlugin, ActivityAware, MethodCallHandler, PluginRegistry.RequestPermissionsResultListener, EventChannel.StreamHandler {

    // Channel setup
    private MethodChannel channel;
    private EventChannel discoveryChannel;
    private EventChannel readChannel;
    private EventChannel statusChannel;
    private MethodChannel printingProgressChannel;

    // State management
    private final Map<String, BluetoothSocket> connectedDevices = new ConcurrentHashMap<>();
    private final Map<Object, EventChannel.EventSink> discoverySinks = new HashMap<>();
    private EventChannel.EventSink readSink;
    private EventChannel.EventSink statusSink;
    private Activity activity;
    private Context context;
    private BluetoothAdapter bluetoothAdapter;
    private Handler mainThreadHandler;
    private Looper looper;
    private Result pendingPermissionResult;

    private ConnectedThread thread;
    private BluetoothDevice device;
    private BluetoothSocket socket;

    // Constants
    private static final String DEFAULT_SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb";
    private static final int PERMISSION_REQUEST_CODE = 34265;

    private EventChannel bluetoothDeviceChannel;
    private EventChannel.EventSink bluetoothDeviceChannelSink;

    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        private final byte[] mmBuffer = new byte[1024];
        private boolean readStream = true;

        public ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e("Bluetooth Connection", "Error getting streams", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void cancel() {
            readStream = false;
            interrupt();
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e("Bluetooth Connection", "Error closing socket", e);
            }
        }

        @Override
        public void run() {
            int numBytes;
            while (readStream) {
                try {
                    numBytes = mmInStream.read(mmBuffer);
                    byte[] readBuf = new byte[numBytes];
                    System.arraycopy(mmBuffer, 0, readBuf, 0, numBytes);
                    new Handler(Looper.getMainLooper()).post(() -> publishBluetoothData(readBuf));
                } catch (IOException e) {
                    Log.e("Bluetooth Read", "Input stream disconnected", e);
                    new Handler(looper).post(() -> publishBluetoothStatus(0));
                    readStream = false;
                }
            }
        }

        public void write(byte[] bytes) {
            try {
                mmOutStream.write(bytes);
//                mmOutStream.flush();
            } catch (IOException e) {
                readStream = false;
                Log.e("Bluetooth Write", "Could not send data to other device", e);
                new Handler(looper).post(() -> publishBluetoothStatus(0));
                cancel();
            }
        }
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        context = binding.getApplicationContext();
        mainThreadHandler = new Handler(Looper.getMainLooper());
        looper = binding.getApplicationContext().getMainLooper();

        // Initialize Bluetooth adapter
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            BluetoothManager bluetoothManager = context.getSystemService(BluetoothManager.class);
            bluetoothAdapter = bluetoothManager.getAdapter();
        } else {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        }

        // Setup channels
        channel = new MethodChannel(binding.getBinaryMessenger(), "maseka.dev/flutter_bluetooth_printer");
        channel.setMethodCallHandler(this);

        discoveryChannel = new EventChannel(binding.getBinaryMessenger(), "maseka.dev/flutter_bluetooth_printer/discovery");
        discoveryChannel.setStreamHandler(this);

        readChannel = new EventChannel(binding.getBinaryMessenger(), "maseka.dev/flutter_bluetooth_printer/read");
        readChannel.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                readSink = events;
            }

            @Override
            public void onCancel(Object arguments) {
                readSink = null;
            }
        });

        statusChannel = new EventChannel(binding.getBinaryMessenger(), "maseka.dev/flutter_bluetooth_printer/status");
        statusChannel.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                statusSink = events;
            }

            @Override
            public void onCancel(Object arguments) {
                statusSink = null;
            }
        });

        printingProgressChannel = new MethodChannel(binding.getBinaryMessenger(), "maseka.dev/flutter_bluetooth_printer/progress");

        bluetoothDeviceChannel = new EventChannel(binding.getBinaryMessenger(), "maseka.dev/flutter_bluetooth_printer/devices");
        bluetoothDeviceChannel.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                bluetoothDeviceChannelSink = events;
            }

            @Override
            public void onCancel(Object arguments) {
                bluetoothDeviceChannelSink = null;
            }
        });

        // Register receivers
        IntentFilter discoveryFilter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        context.registerReceiver(discoveryReceiver, discoveryFilter);

        IntentFilter stateFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        context.registerReceiver(stateReceiver, stateFilter);
    }

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                publishBluetoothDevice(device);
                if (device != null) {
                    Map<String, Object> deviceMap = new HashMap<>();
                    deviceMap.put("address", device.getAddress());
                    deviceMap.put("name", device.getName());
                    deviceMap.put("type", device.getType());
                    deviceMap.put("code", 4); // For Java plugin compatibility

                    for (EventChannel.EventSink sink : discoverySinks.values()) {
                        sink.success(deviceMap);
                    }
                }
            }
        }
    };

    private void cleanupAllResources() {
        try {
            // Stop and cleanup reading thread
            if (thread != null) {
                thread.readStream = false; // Signal thread to stop
                thread.interrupt();
                thread = null;
            }

            // Close and cleanup socket
            if (socket != null) {
                socket.close();
                socket = null;
            }

            // Clear device reference
            device = null;

            // Clear any connected devices from the map
            connectedDevices.clear();

            // 4. Cancel discovery
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
            // Notify status change
            publishBluetoothStatus(0); // Disconnected status

        } catch (IOException e) {
            Log.e("Bluetooth Cleanup", "Error during cleanup", e);
        }
    }

    private void publishBluetoothDevice(BluetoothDevice device) {
        if (device == null) return;

        Log.i("device_discovery", device.getAddress());
        if (bluetoothDeviceChannelSink != null) {
            HashMap<String, String> deviceMap = new HashMap<>();
            deviceMap.put("address", device.getAddress());
            deviceMap.put("name", device.getName());
            bluetoothDeviceChannelSink.success(deviceMap);
        }
    }

    private void publishBluetoothData(byte[] data) {
        if (readSink != null) {
            readSink.success(data);
        }
    }

    private void publishBluetoothStatus(int status) {
        Log.i("Bluetooth Device Status", "Status updated to " + status);
        if (statusSink != null) {
            statusSink.success(status);
        }
    }

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);

                if (statusSink != null) {
                    statusSink.success(state == BluetoothAdapter.STATE_ON ? 2 : 0);
                }

                Map<String, Object> stateMap = new HashMap<>();
                stateMap.put("code", state == BluetoothAdapter.STATE_ON ? 2 : 1);
                for (EventChannel.EventSink sink : discoverySinks.values()) {
                    sink.success(stateMap);
                }

                if (state == BluetoothAdapter.STATE_ON) {
                    startDiscovery(false, false);
                }
            }
        }
    };

    private boolean checkPermissions(boolean requestIfNeeded) {
        List<String> requiredPermissions = new ArrayList<>();
        requiredPermissions.add(Manifest.permission.BLUETOOTH);
        requiredPermissions.add(Manifest.permission.BLUETOOTH_ADMIN);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN);
        } else {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            requiredPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        for (String permission : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                if (requestIfNeeded && activity != null) {
                    ActivityCompat.requestPermissions(
                            activity,
                            requiredPermissions.toArray(new String[0]),
                            PERMISSION_REQUEST_CODE
                    );
                }
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (pendingPermissionResult != null) {
                if (allGranted) {
                    pendingPermissionResult.success(true);
                } else {
                    pendingPermissionResult.error("PERMISSION_DENIED", "Required permissions not granted", null);
                }
                pendingPermissionResult = null;
            }
            return true;
        }
        return false;
    }

    private void safeUnpairDevice(BluetoothDevice device) {
        try{
//            Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
//            for (BluetoothDevice device : bondedDevices) {
                if(device.getBondState() == BluetoothDevice.BOND_BONDED){
                    device.getClass().getMethod("removeBond").invoke(device);
                }
//            }
        } catch (Exception e) {
            Log.e("BluetoothPlugin", "Error unregistering receivers", e);
        }
    }

    private void startDiscovery(boolean requestPermission, boolean forKotlin) {
        if (!checkPermissions(requestPermission)) {
            return;
        }

        if (!forKotlin) {
            Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
            for (BluetoothDevice device : bondedDevices) {
                Map<String, Object> deviceMap = new HashMap<>();
                deviceMap.put("address", device.getAddress());
                deviceMap.put("name", device.getName());
                deviceMap.put("type", device.getType());
                deviceMap.put("code", 4);

                for (EventChannel.EventSink sink : discoverySinks.values()) {
                    sink.success(deviceMap);
                }
            }
        }

        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        bluetoothAdapter.startDiscovery();
    }

    private void stopDiscovery() {
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
    }

    private void connectDevice(String address, String uuidString, Result result, boolean useKotlin) {
        if (!checkPermissions(true)) {
            result.error("PERMISSION_DENIED", "Bluetooth permissions not granted", null);
            return;
        }

        // Clean up any existing connections first
        cleanupAllResources();



        publishBluetoothStatus(1);

            new Thread(() -> {
                synchronized (FlutterBluetoothPrinterPlugin.this) {
                    try {
                        device = bluetoothAdapter.getRemoteDevice(address);
                        Log.i("Bluetooth Connection", "device found");
                        if (device == null) {
                            throw new Exception("Device not found");
                        }
                        safeUnpairDevice(device);
                    UUID uuid = UUID.fromString(DEFAULT_SPP_UUID);
                    socket = device.createRfcommSocketToServiceRecord(uuid);
                    Log.i("Bluetooth Connection", "rfcommsocket found");
                    if (socket == null) {
                        throw new Exception("Failed to create socket");
                    }

                    socket.connect();
                        if(socket!=null&&socket.isConnected()&&useKotlin){
                            Log.i("Bluetooth Connection", "socket connected");

                            thread = new ConnectedThread(socket);
                            Log.i("Bluetooth Connection", "thread created");
                            thread.start();
                        }

                        mainThreadHandler.post(() -> {
                            // DONE
                            result.success(true);
                        });
                    } catch (Exception e) {
                        mainThreadHandler.post(() -> {
                            result.error("error", e.getMessage(), null);
                        });
                    }
                }
            }).start();

    }

    private void startReadingThread(String address) {
        new Thread(() -> {
            try {
                InputStream inputStream = socket.getInputStream();
                byte[] buffer = new byte[1024];
                int bytes;

                while (true) {
                    bytes = inputStream.read(buffer);
                    if (bytes > 0) {
                        byte[] data = Arrays.copyOf(buffer, bytes);
                        mainThreadHandler.post(() -> {
                            if (readSink != null) {
                                readSink.success(data);
                            }
                        });
                    }
                }
            } catch (IOException e) {
                mainThreadHandler.post(() -> {
//                    connectedDevices.remove(address);
                    if (statusSink != null) {
                        statusSink.success(0); // Disconnected
                    }
                });
            }
        }).start();
    }

    private void writeData(String address, byte[] data, boolean keepConnected, Result result) {
//        BluetoothSocket socket = connectedDevices.get(address);


        new Thread(() -> {
            synchronized (FlutterBluetoothPrinterPlugin.this) {
                try {


                    channel.invokeMethod("didUpdateState", 1);
                    if (socket == null) {
                        device = bluetoothAdapter.getRemoteDevice(address);
                        UUID uuid = UUID.fromString(DEFAULT_SPP_UUID);
                        socket = device.createRfcommSocketToServiceRecord(uuid);
                        socket.connect();
                    }

                    InputStream inputStream = socket.getInputStream();
                    OutputStream writeStream = socket.getOutputStream();

                    // PRINTING
                    mainThreadHandler.post(() -> channel.invokeMethod("didUpdateState", 2));
                    assert data != null;


                    updatePrintingProgress(data.length, 0);

                    // req get printer status
                    writeStream.write(data);
                    writeStream.flush();


                    updatePrintingProgress(data.length, data.length);

                    if (!keepConnected) {
                        inputStream.close();
                        writeStream.close();
                        socket.close();
                    }

                    mainThreadHandler.post(() -> {
                        // COMPLETED
                        channel.invokeMethod("didUpdateState", 3);

                        // DONE
                        result.success(true);
                    });
                } catch (IOException e) {
                    mainThreadHandler.post(() -> {
                        result.error("WRITE_FAILED", e.getMessage(), null);
                    });
                }finally {
                    if (!keepConnected) {
                        if (socket != null) {

                            socket = null; // Important cleanup
                            device = null;
                            Log.i("Bluetooth Disconnect", "rfcomm socket closed and nulled");
                        }
                        mainThreadHandler.post(() -> {
                            if (statusSink != null) {
                                statusSink.success(0); // Disconnected
                            }
                        });
                    }
                }
            }
        }).start();
    }


    private void updatePrintingProgress(int total, int progress) {
        mainThreadHandler.post(() -> {
            Map<String, Object> data = new HashMap<>();
            data.put("total", total);
            data.put("progress", progress);

            channel.invokeMethod("onPrintingProgress", data);
        });
    }

    private void write(Result result, String message) {
        Log.i("write_handle", "inside write handle");
        if (thread != null) {
            thread.write(message.getBytes());
            result.success(true);
        } else {
            result.error("write_impossible", "could not send message to unconnected device", null);
        }
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        switch (call.method) {
            case "connect":
                String address = call.argument("address");
                String useKotlin = call.argument("useKotlin");
                connectDevice(address, DEFAULT_SPP_UUID, result, useKotlin != null && useKotlin.equals("true"));
                break;

            case "write":
                String writeAddress = call.argument("address");
                byte[] data = call.argument("data");
                boolean keepConnected = call.argument("keep_connected");
                if (data != null) {
                    writeData(writeAddress, data, keepConnected, result);
                } else {
                    result.error("INVALID_ARGUMENT", "Data cannot be null", null);
                }
                break;

            case "kotlinWrite":
                write(result, call.argument("message"));
                break;

            case "disconnect":
                String disconnectAddress = call.argument("address");
                // Clean up any existing connections first
                cleanupAllResources();

                        mainThreadHandler.post(() -> {
                            if (statusSink != null) {
                                statusSink.success(0); // Disconnected
                            }
                            result.success(true);
                        });

                    publishBluetoothStatus(0);

                break;

            case "getState":
                if (!checkPermissions(false)) {
                    result.success(3); // Permissions not granted
                } else if (!bluetoothAdapter.isEnabled()) {
                    result.success(1); // Bluetooth off
                } else {
                    result.success(2); // Bluetooth on
                }
                break;

            case "initPermissions":
                pendingPermissionResult = result;
                checkPermissions(true);
                break;

            case "getPlatformVersion":
                result.success("Android " + Build.VERSION.RELEASE);
                break;

            case "getDevices":
                Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
                List<Map<String, String>> devicesList = new ArrayList<>();
                for (BluetoothDevice device : bondedDevices) {
                    Map<String, String> deviceMap = new HashMap<>();
                    deviceMap.put("address", device.getAddress());
                    deviceMap.put("name", device.getName());
                    devicesList.add(deviceMap);
                }
                result.success(devicesList);
                break;

            case "startDiscovery":
                startDiscovery(true, false);
                result.success(true);
                break;

            case "stopDiscovery":
                stopDiscovery();
                result.success(true);
                break;

            default:
                result.notImplemented();
        }
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        discoverySinks.put(arguments, events);
        startDiscovery(true, false);
    }

    @Override
    public void onCancel(Object arguments) {
        discoverySinks.remove(arguments);
        if (discoverySinks.isEmpty()) {
            stopDiscovery();
        }
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
        binding.addRequestPermissionsResultListener(this);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        activity = null;
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
    }

    @Override
    public void onDetachedFromActivity() {
        activity = null;
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        try {
            context.unregisterReceiver(discoveryReceiver);
            context.unregisterReceiver(stateReceiver);
        } catch (Exception e) {
            Log.e("BluetoothPlugin", "Error unregistering receivers", e);
        }
    }
}