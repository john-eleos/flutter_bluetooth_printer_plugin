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

  Future<bool> write({
    required String address,
    required Uint8List data,
    bool keepConnected = false,
    required int maxBufferSize,
    required int delayTime,
    ProgressCallback? onProgress,
  });

  Future<bool> connect(String address, {int timeout = 10000});
  
  Future<bool> disconnect(String address);
  
  Future<BluetoothState> checkState();

  Future<bool> startReading(
    String address, {
    DataReceivedCallback? onDataReceived,
    ErrorCallback? onError,
  });

  Future<bool> stopReading(String address);

  Future<void> enableBluetooth();
  
  Future<void> requestPermissions();
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

class PermissionRestrictedState extends DiscoveryState {}

class BluetoothDisabledState extends DiscoveryState {}

class BluetoothEnabledState extends DiscoveryState {}

class UnsupportedBluetoothState extends DiscoveryState {}