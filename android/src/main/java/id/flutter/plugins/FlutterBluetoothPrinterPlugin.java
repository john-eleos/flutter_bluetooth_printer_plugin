package id.flutter.plugins;

import static android.os.Build.VERSION.SDK_INT;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

public class FlutterBluetoothPrinterPlugin implements FlutterPlugin, ActivityAware, MethodCallHandler {
    private MethodChannel channel;
    private Activity activity;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private OutputStream writeStream;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "id.flutter.plugins/bluetooth_printer");
        channel.setMethodCallHandler(this);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        flutterPluginBinding.getApplicationContext().registerReceiver(receiver, filter);
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                final HashMap<String, Object> map = new HashMap<>();
                map.put("name", device.getName());
                map.put("address", device.getAddress());
                map.put("type", device.getType());
                channel.invokeMethod("onDiscovered", map);
            }
        }
    };

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        final String method = call.method;
        switch (method) {
            case "isEnabled":
                isEnabled(result);
                break;

            case "startScan":
                if (!bluetoothAdapter.isDiscovering()){
                    bluetoothAdapter.startDiscovery();
                }
                result.success(true);
                break;

            case "stopScan":
                if (bluetoothAdapter.isDiscovering()){
                    bluetoothAdapter.cancelDiscovery();
                }
                result.success(true);
                break;

            case "connect":
                try {
                    String address = call.argument("address");
                    final BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                    UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
                    bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid);
                    bluetoothSocket.connect();
                    writeStream = bluetoothSocket.getOutputStream();

                    final HashMap<String, Object> map = new HashMap<>();
                    map.put("id", 1);
                    channel.invokeMethod("onStateChanged", map);
                    result.success(true);
                }catch (Exception e){
                    result.error("error", e.getMessage(), null);
                }
                break;

            case "disconnect":
                try {
                    writeStream.close();
                    bluetoothSocket.close();
                    final HashMap<String, Object> map = new HashMap<>();
                    map.put("id", 3);
                    channel.invokeMethod("onStateChanged", map);
                    result.success(true);
                } catch (Exception e) {
                    result.error("error", e.getMessage(), null);
                }
                break;

            case "print": {
                final String str = call.argument("bytes");
                AsyncTask.execute(() -> {
                    try {
                        byte[] bytes = Base64.decode(str, Base64.DEFAULT);
                        writeStream.write(bytes);
                        writeStream.flush();

                        new Handler(Looper.getMainLooper()).post(()->{
                            result.success(true);

                            final HashMap<String, Object> map = new HashMap<>();
                            map.put("total", bytes.length);
                            map.put("progress", bytes.length);
                            channel.invokeMethod("onPrintingProgress", map);
                        });
                    } catch (Exception e) {
                        new Handler(Looper.getMainLooper()).post(()->{
                            result.error("error", e.getMessage(), null);
                        });
                        e.printStackTrace();
                    }
                });
            }
            break;

            default:
                result.notImplemented();
                break;
        }
    }

    private void isEnabled(MethodChannel.Result result){
        if (!isPermitted(result)) {
            return;
        }

        final boolean isEnabled = bluetoothAdapter.isEnabled();
        result.success(isEnabled);
    }

    private boolean isPermitted(MethodChannel.Result result) {
        if (SDK_INT < 23) {
            return true;
        }

        final int res = activity.checkSelfPermission("android.permission.BLUETOOTH");
        if (res != PackageManager.PERMISSION_GRANTED){
            result.error("permission_denied", "Permission denied", null);
            return false;
        }

        return true;
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
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
}
