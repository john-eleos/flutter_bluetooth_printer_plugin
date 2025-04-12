part of '../flutter_bluetooth_printer_library.dart';

typedef ProgressCallback = void Function(int total, int progress);
typedef DataReceivedCallback = void Function(String address, Uint8List data);
typedef ErrorCallback = void Function(String address, String error, String? errorType);

enum BluetoothConnectionState {
  idle,
  connecting,
  connected,
  disconnecting,
  disconnected,
  printing,
}

enum BluetoothState {
  unknown,
  disabled,
  enabled,
  notPermitted,
  permitted,
}

abstract class DiscoveryState {}

abstract class FlutterBluetoothPrinterPlatform extends PlatformInterface {
  static final Object _token = Object();
  static late FlutterBluetoothPrinterPlatform _instance;

  FlutterBluetoothPrinterPlatform() : super(token: _token);

  static FlutterBluetoothPrinterPlatform get instance => _instance;

  static set instance(FlutterBluetoothPrinterPlatform instance) {
    PlatformInterface.verify(instance, _token);
    _instance = instance;
  }

  final connectionStateNotifier = ValueNotifier<BluetoothConnectionState>(
    BluetoothConnectionState.idle,
  );

  Stream<DiscoveryState> get discovery;

  /// Writes data to the connected Bluetooth device
  Future<bool> write({
    required String address,
    required Uint8List data,
    bool keepConnected = false,
    int maxBufferSize = 512,
    int delayTime = 0,
    ProgressCallback? onProgress,
  });

  /// Connects to a Bluetooth device with the specified address
  Future<bool> connect(String address, {int timeout = 10000});

  /// Disconnects from a Bluetooth device
  Future<bool> disconnect(String address);

  /// Checks the current Bluetooth state
  Future<BluetoothState> checkState();

  /// Starts reading data from the device using callback-based approach
  /// Consider using [createReadStream] for a more modern stream-based approach
  @Deprecated('Prefer using createReadStream for better stream handling')
  Future<bool> startReading(
      String address, {
        DataReceivedCallback? onDataReceived,
        ErrorCallback? onError,
      });

  /// Stops reading data from the device
  Future<bool> stopReading(String address);

  /// Creates a continuous stream of data from the Bluetooth device
  /// This is the preferred way to handle incoming data
  Stream<Uint8List> createReadStream(String address);


  /// Writes data to the connected Bluetooth device
  Future<void> writeData(String address, Uint8List data);

  /// Enables Bluetooth if it's currently disabled
  Future<void> enableBluetooth();

  /// Requests necessary Bluetooth permissions
  Future<void> requestPermissions();


  /// Disposes all resources and cleans up
  @mustCallSuper
  void dispose() {
    // Base implementation does nothing but should be called by subclasses
  }
}

class BluetoothDevice extends DiscoveryState {
  final String address;
  final String? name;
  final int? type;

  BluetoothDevice({
    required this.address,
    this.name,
    this.type,
  });

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
          other is BluetoothDevice &&
              runtimeType == other.runtimeType &&
              address == other.address;

  @override
  int get hashCode => address.hashCode;

  @override
  String toString() {
    return 'BluetoothDevice{address: $address, name: $name, type: $type}';
  }
}

class UnknownState extends DiscoveryState {}

class PermissionRestrictedState extends DiscoveryState {
  @override
  String toString() => 'PermissionRestrictedState';
}

class BluetoothDisabledState extends DiscoveryState {
  @override
  String toString() => 'BluetoothDisabledState';
}

class BluetoothEnabledState extends DiscoveryState {
  @override
  String toString() => 'BluetoothEnabledState';
}

class UnsupportedBluetoothState extends DiscoveryState {
  @override
  String toString() => 'UnsupportedBluetoothState';
}

class BluetoothReadException implements Exception {
  final String message;
  final String? errorType;

  BluetoothReadException(this.message, [this.errorType]);

  @override
  String toString() => errorType != null
      ? 'BluetoothReadException: $message ($errorType)'
      : 'BluetoothReadException: $message';
}