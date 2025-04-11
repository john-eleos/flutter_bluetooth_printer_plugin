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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;

public class FlutterBluetoothPrinterPlugin implements FlutterPlugin, ActivityAware, MethodCallHandler, 
        PluginRegistry.RequestPermissionsResultListener, EventChannel.StreamHandler {

    // Constants
    private static final String TAG = "BluetoothPrinterPlugin";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final int PERMISSION_REQUEST_CODE = 919191;
    private static final int READ_TIMEOUT_MS = 5000;
    
    // Connection states
    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;
    private static final int STATE_DISCONNECTING = 3;
    
    // Error codes
    private static final int ERROR_PERMISSION_DENIED = 100;
    private static final int ERROR_BLUETOOTH_OFF = 101;
    private static final int ERROR_DEVICE_NOT_FOUND = 102;
    private static final int ERROR_CONNECTION_FAILED = 103;
    private static final int ERROR_IO_EXCEPTION = 104;
    private static final int ERROR_READ_TIMEOUT = 105;

    // Thread management
    private final ExecutorService threadPool = Executors.newCachedThreadPool();
    private final Handler mainThread = new Handler(Looper.getMainLooper());

    // Bluetooth components
    private BluetoothAdapter bluetoothAdapter;
    private final Map<String, BluetoothSocket> connectedDevices = new HashMap<>();
    private final Map<String, ReadThread> readThreads = new HashMap<>();
    private final Map<String, Integer> connectionStates = new HashMap<>();

    // Flutter components
    private MethodChannel channel;
    private Activity activity;
    private FlutterPluginBinding flutterPluginBinding;
    private final Map<Object, EventChannel.EventSink> sinkList = new HashMap<>();

    // Broadcast receivers
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

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        this.flutterPluginBinding = binding;
        
        // Initialize Bluetooth adapter
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            BluetoothManager bluetoothManager = binding.getApplicationContext().getSystemService(BluetoothManager.class);
            bluetoothAdapter = bluetoothManager.getAdapter();
        } else {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        }

        // Set up method channel
        channel = new MethodChannel(binding.getBinaryMessenger(), "maseka.dev/flutter_bluetooth_printer");
        channel.setMethodCallHandler(this);

        // Set up event channel for discovery
        EventChannel discoveryChannel = new EventChannel(binding.getBinaryMessenger(), 
                "maseka.dev/flutter_bluetooth_printer/discovery");
        discoveryChannel.setStreamHandler(this);

        // Register broadcast receivers
        IntentFilter discoveryFilter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        binding.getApplicationContext().registerReceiver(discoveryReceiver, discoveryFilter);

        IntentFilter stateFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        binding.getApplicationContext().registerReceiver(stateReceiver, stateFilter);
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        // Clean up all resources
        cleanupResources();
        
        channel.setMethodCallHandler(null);
        flutterPluginBinding.getApplicationContext().unregisterReceiver(discoveryReceiver);
        flutterPluginBinding.getApplicationContext().unregisterReceiver(stateReceiver);
    }

    private void cleanupResources() {
        // Stop all read threads
        for (ReadThread thread : readThreads.values()) {
            thread.cancel();
        }
        readThreads.clear();
        
        // Close all sockets
        for (BluetoothSocket socket : connectedDevices.values()) {
            try {
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing socket", e);
            }
        }
        connectedDevices.clear();
        
        // Shutdown thread pool
        threadPool.shutdownNow();
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        switch (call.method) {
            case "connect":
                connectDevice(call, result);
                break;
            case "disconnect":
                disconnectDevice(call, result);
                break;
            case "read":
                startReading(call, result);
                break;
            case "stopReading":
                stopReading(call, result);
                break;
            case "write":
                writeData(call, result);
                break;
            case "getState":
                getBluetoothState(result);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    private void connectDevice(MethodCall call, Result result) {
        threadPool.execute(() -> {
            synchronized (this) {
                try {
                    String address = call.argument("address");
                    int timeout = call.argument("timeout") != null ? 
                            (int) call.argument("timeout") : 10000; // Default 10 seconds

                    updateConnectionState(address, STATE_CONNECTING);
                    
                    BluetoothSocket socket = connectedDevices.get(address);
                    if (socket == null) {
                        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                        socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                        
                        try {
                            socket.connect();
                            connectedDevices.put(address, socket);
                            updateConnectionState(address, STATE_CONNECTED);
                            mainThread.post(() -> result.success(true));
                        } catch (IOException e) {
                            try {
                                socket.close();
                            } catch (IOException e2) {
                                Log.e(TAG, "Error closing socket after failed connection", e2);
                            }
                            handleConnectionError(result, "Connection failed", e, ERROR_CONNECTION_FAILED);
                        }
                    } else {
                        mainThread.post(() -> result.success(true));
                    }
                } catch (Exception e) {
                    handleConnectionError(result, "Connection error", e, ERROR_CONNECTION_FAILED);
                }
            }
        });
    }

    private void disconnectDevice(MethodCall call, Result result) {
        threadPool.execute(() -> {
            synchronized (this) {
                try {
                    String address = call.argument("address");
                    updateConnectionState(address, STATE_DISCONNECTING);
                    
                    // Stop any active reading
                    stopReadingThread(address);
                    
                    // Close socket
                    BluetoothSocket socket = connectedDevices.remove(address);
                    if (socket != null) {
                        try {
                            OutputStream out = socket.getOutputStream();
                            out.flush();
                            out.close();
                            socket.close();
                        } catch (IOException e) {
                            Log.e(TAG, "Error closing socket", e);
                        }
                    }
                    
                    updateConnectionState(address, STATE_DISCONNECTED);
                    mainThread.post(() -> result.success(true));
                } catch (Exception e) {
                    mainThread.post(() -> result.error("disconnect_error", e.getMessage(), null));
                }
            }
        });
    }

    private void startReading(MethodCall call, Result result) {
        threadPool.execute(() -> {
            synchronized (this) {
                try {
                    String address = call.argument("address");
                    BluetoothSocket socket = connectedDevices.get(address);
                    
                    if (socket == null) {
                        mainThread.post(() -> result.error("not_connected", "Device not connected", null));
                        return;
                    }

                    // Stop any existing read thread
                    stopReadingThread(address);

                    // Create and start new read thread
                    ReadThread readThread = new ReadThread(socket, address);
                    readThreads.put(address, readThread);
                    readThread.start();

                    mainThread.post(() -> result.success(true));
                } catch (Exception e) {
                    mainThread.post(() -> result.error("read_error", e.getMessage(), null));
                }
            }
        });
    }

    private void stopReading(MethodCall call, Result result) {
        threadPool.execute(() -> {
            String address = call.argument("address");
            stopReadingThread(address);
            mainThread.post(() -> result.success(true));
        });
    }

    private void writeData(MethodCall call, Result result) {
        threadPool.execute(() -> {
            synchronized (this) {
                try {
                    String address = call.argument("address");
                    boolean keepConnected = call.argument("keep_connected");
                    byte[] data = call.argument("data");

                    if (data == null || data.length == 0) {
                        mainThread.post(() -> result.error("invalid_data", "Data cannot be empty", null));
                        return;
                    }

                    BluetoothSocket socket = connectedDevices.get(address);
                    if (socket == null) {
                        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                        socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                        socket.connect();
                    }

                    try {
                        if (keepConnected && !connectedDevices.containsKey(address)) {
                            connectedDevices.put(address, socket);
                        }

                        InputStream inputStream = socket.getInputStream();
                        OutputStream outputStream = socket.getOutputStream();

                        // Send progress update
                        updatePrintingProgress(data.length, 0);

                        // Write data
                        outputStream.write(data);
                        outputStream.flush();

                        // Update progress
                        updatePrintingProgress(data.length, data.length);

                        if (!keepConnected) {
                            inputStream.close();
                            outputStream.close();
                        }

                        mainThread.post(() -> result.success(true));
                    } finally {
                        if (!keepConnected) {
                            socket.close();
                            connectedDevices.remove(address);
                        }
                    }
                } catch (Exception e) {
                    mainThread.post(() -> result.error("write_error", e.getMessage(), null));
                }
            }
        });
    }

    private void getBluetoothState(Result result) {
        if (!ensurePermission(false)) {
            result.success(3); // Permission denied
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            result.success(1); // Bluetooth off
            return;
        }

        final int state = bluetoothAdapter.getState();
        if (state == BluetoothAdapter.STATE_OFF) {
            result.success(1); // Bluetooth off
        } else if (state == BluetoothAdapter.STATE_ON) {
            result.success(2); // Bluetooth on
        } else {
            result.success(0); // Unknown state
        }
    }

    private class ReadThread extends Thread {
        private final BluetoothSocket socket;
        private final InputStream inputStream;
        private volatile boolean isRunning = true;
        private final String address;

        public ReadThread(BluetoothSocket socket, String address) throws IOException {
            this.socket = socket;
            this.address = address;
            this.inputStream = socket.getInputStream();
        }

        @Override
        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;

            while (isRunning) {
                try {
                    long startTime = System.currentTimeMillis();
                    bytes = inputStream.read(buffer);
                    
                    if (System.currentTimeMillis() - startTime > READ_TIMEOUT_MS) {
                        throw new IOException("Read operation timed out");
                    }
                    
                    if (bytes > 0) {
                        final byte[] data = Arrays.copyOf(buffer, bytes);
                        mainThread.post(() -> {
                            Map<String, Object> result = new HashMap<>();
                            result.put("address", address);
                            result.put("data", data);
                            channel.invokeMethod("onDataRead", result);
                        });
                    }
                } catch (IOException e) {
                    if (isRunning) {
                        mainThread.post(() -> {
                            Map<String, Object> errorResult = new HashMap<>();
                            errorResult.put("address", address);
                            errorResult.put("error", e.getMessage());
                            errorResult.put("errorType", e.getClass().getSimpleName());
                            channel.invokeMethod("onReadError", errorResult);
                        });
                        cancel();
                    }
                    break;
                }
            }
        }

        public void cancel() {
            isRunning = false;
            try {
                inputStream.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing input stream", e);
            }
        }
    }

    // Helper methods
    private void stopReadingThread(String address) {
        ReadThread thread = readThreads.remove(address);
        if (thread != null) {
            thread.cancel();
        }
    }

    private void updateConnectionState(String address, int state) {
        connectionStates.put(address, state);
        mainThread.post(() -> {
            Map<String, Object> data = new HashMap<>();
            data.put("address", address);
            data.put("state", state);
            channel.invokeMethod("onConnectionStateChanged", data);
        });
    }

    private void updatePrintingProgress(int total, int progress) {
        mainThread.post(() -> {
            Map<String, Object> data = new HashMap<>();
            data.put("total", total);
            data.put("progress", progress);
            channel.invokeMethod("onPrintingProgress", data);
        });
    }

    private void handleConnectionError(Result result, String message, Exception e, int errorCode) {
        Log.e(TAG, message, e);
        Map<String, Object> error = new HashMap<>();
        error.put("message", e.getMessage());
        error.put("code", errorCode);
        mainThread.post(() -> result.error("connection_error", message, error));
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
                boolean hasBluetooth = activity.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED;
                boolean hasScan = activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
                boolean hasConnect = activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;

                if (hasBluetooth && hasScan && hasConnect) {
                    return true;
                }

                if (!request) return false;
                activity.requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH,
                        Manifest.permission.BLUETOOTH_ADMIN,
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                }, PERMISSION_REQUEST_CODE);
            } else {
                if (activity == null) {
                    return false;
                }

                boolean hasBluetooth = activity.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED;
                boolean hasFineLocation = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                boolean hasCoarseLocation = activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

                if (hasBluetooth && (hasFineLocation || hasCoarseLocation)) {
                    return true;
                }

                if (!request) return false;
                activity.requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH,
                        Manifest.permission.BLUETOOTH_ADMIN,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, PERMISSION_REQUEST_CODE);
            }
            return false;
        }
        return true;
    }

    private void startDiscovery(boolean requestPermission) {
        if (!ensurePermission(requestPermission)) {
            return;
        }

        // Return bonded devices immediately
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

    // ActivityAware methods
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
        binding.addRequestPermissionsResultListener(this);
    }

    @Override
    public void onDetachedFromActivity() {
        activity = null;
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (final int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Map<String, Object> data = new HashMap<>();
                    data.put("code", 3); // Permission denied
                    notifySinks(data);
                    return true;
                }
            }

            if (!bluetoothAdapter.isEnabled()) {
                Map<String, Object> data = new HashMap<>();
                data.put("code", 1); // Bluetooth off
                notifySinks(data);
                return true;
            }

            startDiscovery(false);
            return true;
        }
        return false;
    }

    private void notifySinks(Map<String, Object> data) {
        for (EventChannel.EventSink sink : sinkList.values()) {
            sink.success(data);
        }
    }

    // EventChannel.StreamHandler methods
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