package android.src.main.java.id.flutter.plugins;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

public class BluetoothStreamReader {
    private static final UUID SERIAL_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private final BluetoothDevice device;
    private final DataReceivedCallback dataCallback;
    private final ErrorCallback errorCallback;
    private BluetoothSocket socket;
    private InputStream inputStream;
    private boolean isReading;
    private Thread readingThread;

    public interface DataReceivedCallback {
        void onDataReceived(byte[] data);
    }

    public interface ErrorCallback {
        void onError(Exception e);
    }

    public BluetoothStreamReader(BluetoothDevice device,
                                 DataReceivedCallback dataCallback,
                                 ErrorCallback errorCallback) {
        this.device = device;
        this.dataCallback = dataCallback;
        this.errorCallback = errorCallback;
    }

    public void startReading() {
        if (isReading) return;

        isReading = true;
        readingThread = new Thread(() -> {
            try {
                // Create and connect socket
                socket = device.createRfcommSocketToServiceRecord(SERIAL_UUID);
                socket.connect();
                inputStream = socket.getInputStream();

                byte[] buffer = new byte[1024];
                int bytes;

                while (isReading) {
                    try {
                        bytes = inputStream.read(buffer);
                        if (bytes > 0) {
                            byte[] data = new byte[bytes];
                            System.arraycopy(buffer, 0, data, 0, bytes);
                            dataCallback.onDataReceived(data);
                        }
                    } catch (IOException e) {
                        Log.e("BluetoothStreamReader", "Error reading stream", e);
                        errorCallback.onError(e);
                        stopReading();
                    }
                }
            } catch (Exception e) {
                Log.e("BluetoothStreamReader", "Connection failed", e);
                errorCallback.onError(e);
                stopReading();
            }
        });
        readingThread.start();
    }

    public void stopReading() {
        isReading = false;
        if (readingThread != null) {
            readingThread.interrupt();
            readingThread = null;
        }

        try {
            if (inputStream != null) {
                inputStream.close();
                inputStream = null;
            }
        } catch (IOException e) {
            Log.e("BluetoothStreamReader", "Error closing input stream", e);
        }

        try {
            if (socket != null) {
                socket.close();
                socket = null;
            }
        } catch (IOException e) {
            Log.e("BluetoothStreamReader", "Error closing socket", e);
        }
    }

    public void readOnce(ReadOnceCallback callback, long timeout) {
        new Thread(() -> {
            BluetoothSocket tempSocket = null;
            InputStream tempInputStream = null;

            try {
                // Create and connect socket
                tempSocket = device.createRfcommSocketToServiceRecord(SERIAL_UUID);
                tempSocket.connect();
                tempInputStream = tempSocket.getInputStream();

                byte[] buffer = new byte[1024];
                long startTime = System.currentTimeMillis();
                boolean dataReceived = false;

                while (System.currentTimeMillis() - startTime < timeout && !dataReceived) {
                    int available = tempInputStream.available();
                    if (available > 0) {
                        int bytes = tempInputStream.read(buffer);
                        if (bytes > 0) {
                            byte[] data = new byte[bytes];
                            System.arraycopy(buffer, 0, data, 0, bytes);
                            callback.onComplete(data);
                            dataReceived = true;
                        }
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                if (!dataReceived) {
                    callback.onComplete(null);
                }
            } catch (Exception e) {
                Log.e("BluetoothStreamReader", "Error in readOnce", e);
                callback.onComplete(null);
            } finally {
                try {
                    if (tempInputStream != null) {
                        tempInputStream.close();
                    }
                } catch (IOException e) {
                    Log.e("BluetoothStreamReader", "Error closing input stream", e);
                }

                try {
                    if (tempSocket != null) {
                        tempSocket.close();
                    }
                } catch (IOException e) {
                    Log.e("BluetoothStreamReader", "Error closing socket", e);
                }
            }
        }).start();
    }

    public interface ReadOnceCallback {
        void onComplete(byte[] data);
    }
}
