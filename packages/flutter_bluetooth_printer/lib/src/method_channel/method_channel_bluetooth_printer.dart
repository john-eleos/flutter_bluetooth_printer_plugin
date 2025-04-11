part of flutter_bluetooth_printer;

class UnknownState extends DiscoveryState {}

class PermissionRestrictedState extends DiscoveryState {}

class BluetoothDisabledState extends DiscoveryState {}

class BluetoothEnabledState extends DiscoveryState {}

class UnsupportedBluetoothState extends DiscoveryState {}

class MethodChannelBluetoothPrinter extends FlutterBluetoothPrinterPlatform {
  MethodChannelBluetoothPrinter();

  static void registerWith() {
    FlutterBluetoothPrinterPlatform.instance = MethodChannelBluetoothPrinter();
  }

  final channel = const MethodChannel('maseka.dev/flutter_bluetooth_printer');
  final discoveryChannel =
      const EventChannel('maseka.dev/flutter_bluetooth_printer/discovery');

  ProgressCallback? _progressCallback;
  DataReceivedCallback? _dataReceivedCallback;
  ErrorCallback? _errorCallback;

  bool _isInitialized = false;
  bool _isBusy = false;

  void _init() {
    if (_isInitialized) return;
    _isInitialized = true;
    
    channel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'didUpdateState':
          final index = call.arguments as int;
          connectionStateNotifier.value =
              BluetoothConnectionState.values[index];
          break;

        case 'onPrintingProgress':
          final total = call.arguments['total'] as int;
          final progress = call.arguments['progress'] as int;
          _progressCallback?.call(total, progress);
          break;

        case 'onDataRead':
          final address = call.arguments['address'] as String;
          final data = call.arguments['data'] as Uint8List;
          _dataReceivedCallback?.call(address, data);
          break;

        case 'onReadError':
          final address = call.arguments['address'] as String;
          final error = call.arguments['error'] as String;
          final errorType = call.arguments['errorType'] as String?;
          _errorCallback?.call(address, error, errorType);
          break;

        case 'onConnectionStateChanged':
          final address = call.arguments['address'] as String;
          final state = call.arguments['state'] as int;
          // Handle connection state changes if needed
          break;
      }
      return null;
    });
  }

  BluetoothState _intToState(int value) {
    switch (value) {
      case 0:
        return BluetoothState.unknown;
      case 1:
        return BluetoothState.disabled;
      case 2:
        return BluetoothState.enabled;
      case 3:
        return BluetoothState.notPermitted;
      case 4:
        return BluetoothState.permitted;
      default:
        return BluetoothState.unknown;
    }
  }

  @override
  Stream<DiscoveryState> get discovery {
    return discoveryChannel
        .receiveBroadcastStream()
        .asyncExpand<DiscoveryState>((data) async* {
      final code = data['code'];
      final state = _intToState(code);

      if (state == BluetoothState.notPermitted) {
        yield PermissionRestrictedState();
      } else if (state == BluetoothState.disabled) {
        yield BluetoothDisabledState();
      } else if (state == BluetoothState.enabled) {
        yield BluetoothEnabledState();
      } else if (state == BluetoothState.permitted) {
        yield BluetoothDevice(
          address: data['address'],
          name: data['name'],
          type: data['type'],
        );
      } else {
        yield UnknownState();
      }
    });
  }

  @override
  Future<bool> connect(String address, {int timeout = 10000}) async {
    try {
      _isBusy = true;
      _init();

      final res = await channel.invokeMethod('connect', {
        'address': address,
        'timeout': timeout,
      });

      return res == true;
    } catch (e) {
      return false;
    } finally {
      _isBusy = false;
    }
  }

  @override
  Future<bool> disconnect(String address) async {
    try {
      final res = await channel.invokeMethod('disconnect', {
        'address': address,
      });
      return res == true;
    } catch (e) {
      return false;
    }
  }

  @override
  Future<bool> write({
    required String address,
    required Uint8List data,
    bool keepConnected = false,
    int maxBufferSize = 512,
    int delayTime = 0,
    ProgressCallback? onProgress,
  }) async {
    try {
      if (_isBusy) {
        throw Exception('Device is busy with another operation');
      }

      _isBusy = true;
      _init();
      _progressCallback = onProgress;

      final res = await channel.invokeMethod('write', {
        'address': address,
        'data': data,
        'keep_connected': keepConnected,
        'max_buffer_size': maxBufferSize,
        'delay_time': delayTime,
      });

      return res == true;
    } catch (e) {
      return false;
    } finally {
      _progressCallback = null;
      _isBusy = false;
    }
  }

  @override
  Future<bool> startReading(
    String address, {
    DataReceivedCallback? onDataReceived,
    ErrorCallback? onError,
  }) async {
    try {
      _init();
      _dataReceivedCallback = onDataReceived;
      _errorCallback = onError;

      final res = await channel.invokeMethod('read', {
        'address': address,
      });

      return res == true;
    } catch (e) {
      return false;
    }
  }

  @override
  Future<bool> stopReading(String address) async {
    try {
      final res = await channel.invokeMethod('stopReading', {
        'address': address,
      });
      
      // Clear callbacks when stopping
      _dataReceivedCallback = null;
      _errorCallback = null;
      
      return res == true;
    } catch (e) {
      return false;
    }
  }

  @override
  Future<BluetoothState> checkState() async {
    try {
      final result = await channel.invokeMethod('getState');
      return _intToState(result);
    } catch (e) {
      return BluetoothState.unknown;
    }
  }

  @override
  Future<void> enableBluetooth() async {
    await channel.invokeMethod('enableBluetooth');
  }

  @override
  Future<void> requestPermissions() async {
    await channel.invokeMethod('requestPermissions');
  }
}