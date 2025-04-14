package id.flutter.plugins;

import static android.os.Build.VERSION.SDK_INT;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;


import android.annotation.SuppressLint;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;



public class FlutterBluetoothPrinterPlugin implements FlutterPlugin, ActivityAware, MethodCallHandler,
        PluginRegistry.RequestPermissionsResultListener, EventChannel.StreamHandler {

    // THIS IS FOR KOTLIN SOME THINGS WILL BE ADDED AND REMOVED HERE FOR IT

    private EventChannel bluetoothDeviceChannel;
    private EventChannel bluetoothReadChannel;
    private EventChannel bluetoothStatusChannel;

    private EventChannel.EventSink bluetoothDeviceChannelSink;
    private EventChannel.EventSink bluetoothReadChannelSink;
    private EventChannel.EventSink bluetoothStatusChannelSink;

    private BluetoothAdapter ba;
    private Activity pluginActivity;
    private Context application;
    private Looper looper;
    // private final int myPermissionCode = 34264;
    private final int myPermissionCode = 919191;
    private MethodChannel.Result activeResult;
    private boolean permissionGranted = false;

    private ConnectedThread thread;
    private BluetoothSocket socket;
    private BluetoothDevice device;

    private class ConnectedThread extends Thread {
        private final BluetoothSocket socket;
        private final java.io.InputStream inputStream;
        private final java.io.OutputStream outputStream;
        private final byte[] buffer = new byte[1024];
        public boolean readStream = true;

        public ConnectedThread(BluetoothSocket socket) throws IOException {
            this.socket = socket;
            this.inputStream = socket.getInputStream();
            this.outputStream = socket.getOutputStream();
        }


        @Override
        public void run() {
            int numBytes;
            while (readStream) {
                try {
                    numBytes = inputStream.read(buffer);
                    if (numBytes > 0) {
                        Log.i("Bluetooth Read", "read " + numBytes + " bytes");
                        final byte[] receivedData = Arrays.copyOf(buffer, numBytes);
                        new Handler(Looper.getMainLooper())
                                .post(() -> publishBluetoothData(receivedData));
                    }
                } catch (IOException e) {
                    Log.e("Bluetooth Read", "input stream disconnected", e);
                    new Handler(Looper.getMainLooper()).post(() -> publishBluetoothStatus(0));
                    readStream = false;
                    break;
                }
            }
        }

        public void write(byte[] bytes) {
            try {
                outputStream.write(bytes);
                outputStream.flush();
            } catch (IOException e) {
                readStream = false;
                Log.e("Bluetooth Write", "could not send data to other device", e);
                new Handler(Looper.getMainLooper()).post(() -> publishBluetoothStatus(0));
            }
        }
    }

    // END OF KOTLIN CODE

    private final Map<Object, EventChannel.EventSink> sinkList = new HashMap<>();
    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                final Map<String, Object> map = deviceToMap(device);

                for (EventChannel.EventSink sink : sinkList.values()) {
                    sink.success(map);
                }
            }
        }
    };
    private MethodChannel channel;
    private Activity activity;
    private BluetoothAdapter bluetoothAdapter;
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                final int value = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
                if (value == BluetoothAdapter.STATE_OFF) {
                    Map<String, Object> data = new HashMap<>();
                    data.put("code", 1);
                    for (EventChannel.EventSink sink : sinkList.values()) {
                        sink.success(data);
                    }
                } else if (value == BluetoothAdapter.STATE_ON) {
                    startDiscovery(false);
                }
            }
        }
    };
    private FlutterPluginBinding flutterPluginBinding;
    private final Map<String, BluetoothSocket> connectedDevices = new HashMap<>();
    private Handler mainThread;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        this.flutterPluginBinding = flutterPluginBinding;
        this.mainThread = new Handler(Looper.getMainLooper());

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            BluetoothManager bluetoothManager = flutterPluginBinding.getApplicationContext()
                    .getSystemService(BluetoothManager.class);
            bluetoothAdapter = bluetoothManager.getAdapter();
        } else {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        }

        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "maseka.dev/flutter_bluetooth_printer");
        channel.setMethodCallHandler(this);

        EventChannel discoveryChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(),
                "maseka.dev/flutter_bluetooth_printer/discovery");
        discoveryChannel.setStreamHandler(this);

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        flutterPluginBinding.getApplicationContext().registerReceiver(discoveryReceiver, filter);

        IntentFilter stateFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        flutterPluginBinding.getApplicationContext().registerReceiver(stateReceiver, stateFilter);

        // THIS IS KOTLIN SECTION
        bluetoothDeviceChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(),
                "maseka.dev/flutter_bluetooth_printer/devices");
        bluetoothReadChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(),
                "maseka.dev/flutter_bluetooth_printer/read");
        bluetoothStatusChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(),
                "maseka.dev/flutter_bluetooth_printer/status");

        ba = BluetoothAdapter.getDefaultAdapter();
        looper = flutterPluginBinding.getApplicationContext().getMainLooper();
        application = flutterPluginBinding.getApplicationContext();

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

        bluetoothReadChannel.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                bluetoothReadChannelSink = events;
            }

            @Override
            public void onCancel(Object arguments) {
                bluetoothReadChannelSink = null;
            }
        });

        bluetoothStatusChannel.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                bluetoothStatusChannelSink = events;
            }

            @Override
            public void onCancel(Object arguments) {
                bluetoothStatusChannelSink = null;
            }
        });

        // THIS IS END OF KOTLIN SECTION

    }

    private Map<String, Object> deviceToMap(BluetoothDevice device) {
        final HashMap<String, Object> map = new HashMap<>();
        map.put("code", 4);
        map.put("name", device.getName());
        map.put("address", device.getAddress());
        map.put("type", device.getType());
        return map;
    }

    private boolean ensurePermission(boolean request) {
        if (SDK_INT >= Build.VERSION_CODES.M) {
            if (SDK_INT >= 31) {
                final boolean bluetooth = activity
                        .checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED;
                final boolean bluetoothScan = activity
                        .checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
                final boolean bluetoothConnect = activity.checkSelfPermission(
                        Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;

                if (bluetooth && bluetoothScan && bluetoothConnect) {
                    return true;
                }

                if (!request)
                    return false;
                activity.requestPermissions(
                        new String[] { Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN,
                                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT },
                        myPermissionCode);
            } else {
                if (activity == null) {
                    return false;
                }

                boolean bluetooth = activity
                        .checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED;
                boolean fineLocation = activity.checkSelfPermission(
                        Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                boolean coarseLocation = activity.checkSelfPermission(
                        Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

                if (bluetooth && (fineLocation || coarseLocation)) {
                    return true;
                }

                if (!request)
                    return false;
                activity.requestPermissions(
                        new String[] { Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN,
                                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION },
                        myPermissionCode);
            }

            return false;
        }

        return true;
    }

    private void startDiscovery(boolean requestPermission) {
        if (!ensurePermission(requestPermission)) {
            return;
        }

        // immediately return bonded devices
        Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
        for (BluetoothDevice device : bonded) {
            final Map<String, Object> map = deviceToMap(device);
            for (EventChannel.EventSink sink : sinkList.values()) {
                sink.success(map);
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

    private void updatePrintingProgress(int total, int progress) {
        mainThread.post(() -> {
            Map<String, Object> data = new HashMap<>();
            data.put("total", total);
            data.put("progress", progress);

            channel.invokeMethod("onPrintingProgress", data);
        });
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        final String method = call.method;
        switch (method) {
            case "connect": {
                new Thread(() -> {
                    synchronized (FlutterBluetoothPrinterPlugin.this) {
                        try {
                            String address = call.argument("address");
                            BluetoothSocket bluetoothSocket = connectedDevices.get(address);
                            if (bluetoothSocket == null) {
                                final BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                                UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
                                bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid);
                                bluetoothSocket.connect();
                                connectedDevices.put(address, bluetoothSocket);
                            }

                            mainThread.post(() -> {
                                // DONE
                                result.success(true);
                            });
                        } catch (Exception e) {
                            mainThread.post(() -> {
                                result.error("error", e.getMessage(), null);
                            });
                        }
                    }
                }).start();
                return;
            }
            case "getState": {
                if (!ensurePermission(false)) {
                    result.success(3);
                    return;
                }

                if (!bluetoothAdapter.isEnabled()) {
                    result.success(1);
                    return;
                }

                final int state = bluetoothAdapter.getState();
                if (state == BluetoothAdapter.STATE_OFF) {
                    result.success(1);
                    return;
                }

                if (state == BluetoothAdapter.STATE_ON) {
                    result.success(2);
                    return;
                }
                return;
            }

            case "disconnect": {
                new Thread(() -> {
                    synchronized (FlutterBluetoothPrinterPlugin.this) {
                        try {
                            String address = call.argument("address");
                            BluetoothSocket socket = connectedDevices.remove(address);
                            if (socket != null) {
                                OutputStream out = socket.getOutputStream();
                                out.flush();
                                out.close();
                                socket.close();
                            }

                            mainThread.post(() -> {
                                result.success(true);
                            });
                        } catch (Exception e) {
                            mainThread.post(() -> {
                                result.error("error", e.getMessage(), null);
                            });
                        }
                    }
                }).start();
                return;
            }

            case "write": {
                // CONNECTING
                channel.invokeMethod("didUpdateState", 1);
                new Thread(() -> {
                    synchronized (FlutterBluetoothPrinterPlugin.this) {
                        try {
                            String address = call.argument("address");
                            boolean keepConnected = call.argument("keep_connected");
                            byte[] data = call.argument("data");

                            BluetoothSocket bluetoothSocket = connectedDevices.get(address);
                            if (bluetoothSocket == null) {
                                final BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                                UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
                                bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid);
                                bluetoothSocket.connect();
                            }

                            try {
                                if (keepConnected && !connectedDevices.containsKey(address)) {
                                    connectedDevices.put(address, bluetoothSocket);
                                }

                                InputStream inputStream = bluetoothSocket.getInputStream();
                                OutputStream writeStream = bluetoothSocket.getOutputStream();

                                // PRINTING
                                mainThread.post(() -> channel.invokeMethod("didUpdateState", 2));
                                assert data != null;

                                updatePrintingProgress(data.length, 0);

                                // req get printer status
                                writeStream.write(data);
                                writeStream.flush();

                                updatePrintingProgress(data.length, data.length);

                                if (!keepConnected) {
                                    inputStream.close();
                                    writeStream.close();
                                }

                                mainThread.post(() -> {
                                    // COMPLETED
                                    channel.invokeMethod("didUpdateState", 3);

                                    // DONE
                                    result.success(true);
                                });
                            } finally {
                                if (!keepConnected) {
                                    bluetoothSocket.close();
                                    connectedDevices.remove(address);
                                }
                            }
                        } catch (Exception e) {
                            mainThread.post(() -> {
                                result.error("error", e.getMessage(), null);
                            });
                        }
                    }
                }).start();
                return;
            }

            // KOTLIN STARTS HERE 

            case "getPlatformVersionKotlin":
                result.success("Android " + Build.VERSION.RELEASE);
                break;
            case "initPermissionsKotlin":
                initPermissions(result);
                break;
            case "getDevicesKotlin":
                getDevices(result);
                break;
            case "startDiscoveryKotlin":
                startScan(result);
                break;
            case "stopDiscoveryKotlin":
                stopScan(result);
                break;
            case "connectKotlin":
                connect(result, call.argument("deviceId"), call.argument("serviceUUID"));
                break;
            case "disconnectKotlin":
                disconnect(result);
                break;
            case "writeKotlin":
                write(result, call.argument("message"));
                break;

            // KOTLIN ENDS HERE

            default:
                result.notImplemented();
                break;
        }
    }

    // START KOTLIN HERE 
    private void write(MethodChannel.Result result, String message) {
        if (thread != null) {
            thread.write(message.getBytes());
            result.success(true);
        } else {
            result.error("write_impossible", "could not send message to unconnected device", null);
        }
    }

    private void disconnect(MethodChannel.Result result) {
        try {
            device = null;
            if (thread != null) {
                thread.interrupt();
                thread = null;
            }
            if (socket != null) {
                socket.close();
                socket = null;
            }
            publishBluetoothStatus(0);
            result.success(true);
        } catch (IOException e) {
            result.error("disconnect_failed", e.getMessage(), null);
        }
    }

    private void connect(MethodChannel.Result result, String deviceId, String serviceUuid) {
        try {
            if (deviceId == null || serviceUuid == null) {
                result.error("invalid_args", "deviceId and serviceUuid must not be null", null);
                return;
            }
            
            publishBluetoothStatus(1);
            device = ba.getRemoteDevice(deviceId);
            socket = device.createRfcommSocketToServiceRecord(UUID.fromString(serviceUuid));
            socket.connect();
            thread = new ConnectedThread(socket);
            thread.start();
            publishBluetoothStatus(2);
            result.success(true);
        } catch (IOException e) {
            publishBluetoothStatus(0);
            result.error("connection_failed", "could not connect to device " + deviceId + ": " + e.getMessage(), null);
        } catch (IllegalArgumentException e) {
            publishBluetoothStatus(0);
            result.error("invalid_uuid", "invalid service UUID: " + serviceUuid, null);
        }
    }

    private void startScan(MethodChannel.Result result) {
        application.registerReceiver(receiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
        ba.startDiscovery();
        result.success(true);
    }

    private void stopScan(MethodChannel.Result result) {
        ba.cancelDiscovery();
        result.success(true);
    }



    private void initPermissions(MethodChannel.Result result) {
        if (activeResult != null) {
            result.error("init_running", "only one initialize call allowed at a time", null);
            return;
        }
        activeResult = result;
        checkPermissions(application);
    }

    private void arePermissionsGranted(Context context) {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.BLUETOOTH);
        permissions.add(Manifest.permission.BLUETOOTH_ADMIN);
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
        }

        permissionGranted = true;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
                permissionGranted = false;
                break;
            }
        }
    }

    private void checkPermissions(Context context) {
        arePermissionsGranted(context);
        if (!permissionGranted) {
            List<String> permissions = new ArrayList<>();
            permissions.add(Manifest.permission.BLUETOOTH);
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN);
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
                permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            ActivityCompat.requestPermissions(pluginActivity, permissions.toArray(new String[0]), myPermissionCode);
        } else {
            completeCheckPermissions();
        }
    }

    private void completeCheckPermissions() {
        if (permissionGranted) {
            if (activeResult != null) activeResult.success(true);
        } else {
            if (activeResult != null)
                activeResult.error("permissions_not_granted", "Permissions required", null);
        }
        activeResult = null;
    }

    public boolean onRequestPermissionsResults(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == myPermissionCode) {
            permissionGranted = grantResults.length > 0;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    permissionGranted = false;
                    break;
                }
            }
            completeCheckPermissions();
            return true;
        }
        return false;
    }

    @SuppressLint("MissingPermission")
    private void getDevices(MethodChannel.Result result) {
        Set<BluetoothDevice> devices = ba.getBondedDevices();
        List<HashMap<String, String>> list = new ArrayList<>();
        for (BluetoothDevice device : devices) {
            HashMap<String, String> map = new HashMap<>();
            map.put("address", device.getAddress());
            map.put("name", device.getName());
            list.add(map);
        }
        result.success(list);
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                publishBluetoothDevice(device);
            }
        }
    };

    private void publishBluetoothData(byte[] data) {
        if (bluetoothReadChannelSink != null) {
            bluetoothReadChannelSink.success(data);
        }
    }

    private void publishBluetoothStatus(int status) {
        Log.i("Bluetooth Device Status", "Status updated to " + status);
        if (bluetoothStatusChannelSink != null) {
            bluetoothStatusChannelSink.success(status);
        }
    }

    private void publishBluetoothDevice(BluetoothDevice device) {
        Log.i("device_discovery", device.getAddress());
        if (bluetoothDeviceChannelSink != null) {
            HashMap<String, String> map = new HashMap<>();
            map.put("address", device.getAddress());
            map.put("name", device.getName());
            bluetoothDeviceChannelSink.success(map);
        }
    }

    // KOTLIN ENDS HERE

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        flutterPluginBinding.getApplicationContext().unregisterReceiver(discoveryReceiver);
        flutterPluginBinding.getApplicationContext().unregisterReceiver(stateReceiver);

        // Unregister Kotlin receiver
        try {
            flutterPluginBinding.getApplicationContext().unregisterReceiver(receiver);
        } catch (IllegalArgumentException e) {
            // Receiver was not registered
        }
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
        pluginActivity = binding.getActivity(); // Add this for Kotlin part
        binding.addRequestPermissionsResultListener(this);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {

    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {

    }

    @Override
    public void onDetachedFromActivity() {
        activity = null;
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        if (requestCode == myPermissionCode) {
            for (final int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Map<String, Object> data = new HashMap<>();
                    data.put("code", 3);

                    for (EventChannel.EventSink sink : sinkList.values()) {
                        sink.success(data);
                    }

                    return true;
                }
            }

            if (!bluetoothAdapter.isEnabled()) {
                Map<String, Object> data = new HashMap<>();
                data.put("code", 1);

                for (EventChannel.EventSink sink : sinkList.values()) {
                    sink.success(data);
                }
                return true;
            }

            final int state = bluetoothAdapter.getState();
            if (state == BluetoothAdapter.STATE_OFF) {
                Map<String, Object> data = new HashMap<>();
                data.put("code", 1);

                for (EventChannel.EventSink sink : sinkList.values()) {
                    sink.success(data);
                }
                return true;
            }

            startDiscovery(false);
            return true;
        }

        return false;
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        sinkList.put(arguments, events);
        startDiscovery(true);
    }

    @Override
    public void onCancel(Object arguments) {
        sinkList.remove(arguments);
        if (sinkList.isEmpty()) {
            stopDiscovery();
        }
    }
}