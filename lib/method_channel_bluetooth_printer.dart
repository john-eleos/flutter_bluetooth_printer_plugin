part of '../flutter_bluetooth_printer_library.dart';

class MethodChannelBluetoothPrinter extends FlutterBluetoothPrinterPlatform {
  MethodChannelBluetoothPrinter();

  static void registerWith() {
    FlutterBluetoothPrinterPlatform.instance = MethodChannelBluetoothPrinter();
  }

  final _methodChannel = const MethodChannel('maseka.dev/flutter_bluetooth_printer');
  final _discoveryChannel = const EventChannel('maseka.dev/flutter_bluetooth_printer/discovery');
  final _readChannel = const EventChannel('maseka.dev/flutter_bluetooth_printer/read');

  // Track active streams
  final Map<String, StreamController<Uint8List>> _readControllers = {};
  final Map<String, StreamSubscription> _readSubscriptions = {};

  ProgressCallback? _progressCallback;
  DataReceivedCallback? _dataReceivedCallback;
  ErrorCallback? _errorCallback;

  bool _isInitialized = false;
  bool _isBusy = false;

  void _init() {
    if (_isInitialized) return;
    _isInitialized = true;

    _methodChannel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'didUpdateState':
          final index = call.arguments as int;
          connectionStateNotifier.value = BluetoothConnectionState.values[index];
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
          // Handle connection state changes
          break;
      }
      return null;
    });
  }

  BluetoothState _intToState(int value) {
    switch (value) {
      case 0: return BluetoothState.unknown;
      case 1: return BluetoothState.disabled;
      case 2: return BluetoothState.enabled;
      case 3: return BluetoothState.notPermitted;
      case 4: return BluetoothState.permitted;
      default: return BluetoothState.unknown;
    }
  }

  @override
  Stream<DiscoveryState> get discovery {
    return _discoveryChannel.receiveBroadcastStream().asyncExpand((data) async* {
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
      final res = await _methodChannel.invokeMethod('connect', {
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
      await _cleanupReadStream(address);
      final res = await _methodChannel.invokeMethod('disconnect', {
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
      if (_isBusy) throw Exception('Device is busy');
      _isBusy = true;
      _init();
      _progressCallback = onProgress;

      final res = await _methodChannel.invokeMethod('write', {
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
  Stream<Uint8List> createReadStream(String address, {int timeoutMs = 5000}) {
    final controller = StreamController<Uint8List>();
    _readControllers[address] = controller;

    _methodChannel.invokeMethod('startReading', {
      'address': address,
      'timeoutMs': timeoutMs,
    });
    // Listen to the unified read channel
    _readSubscriptions[address] = _readChannel.receiveBroadcastStream().listen(
          (data) {
        if (data is Map &&
            data['address'] == address &&
            data['data'] is Uint8List) {
          controller.add(data['data'] as Uint8List);
        }
      },
      onError: (error) {
        if (error is PlatformException) {
          controller.addError(BluetoothReadException(
            error.message ?? 'Read error',
            error.details?['errorType']?.toString(),
          ));
        } else {
          controller.addError(error);
        }
      },
    );

    return controller.stream;
  }

  Future<void> _cleanupReadStream(String address) async {
    await _readSubscriptions[address]?.cancel();
    _readSubscriptions.remove(address);

    await _readControllers[address]?.close();
    _readControllers.remove(address);

    await _methodChannel.invokeMethod('stopReading', {'address': address});
  }

  @override
  Future<bool> startReading(
      String address, {
        DataReceivedCallback? onDataReceived,
        ErrorCallback? onError,
        int timeoutMs = 5000,
      }) async {
    try {
      _init();
      _dataReceivedCallback = onDataReceived;
      _errorCallback = onError;
      final res = await _methodChannel.invokeMethod('startReading', {
        'address': address,
        'timeoutMs': timeoutMs,
      });
      return res == true;
    } catch (e) {
      return false;
    }
  }

  @override
  Future<bool> stopReading(String address) async {
    try {
      await _cleanupReadStream(address);
      _dataReceivedCallback = null;
      _errorCallback = null;
      final res = await _methodChannel.invokeMethod('stopReading', {
        'address': address,
      });
      return res == true;
    } catch (e) {
      return false;
    }
  }

  @override
  Future<BluetoothState> checkState() async {
    try {
      final result = await _methodChannel.invokeMethod('getState');
      return _intToState(result);
    } catch (e) {
      return BluetoothState.unknown;
    }
  }

  @override
  Future<void> writeData(String address, Uint8List data) async {
    try {
      if (_isBusy) throw Exception('Device is busy');
      _isBusy = true;
      _init();
      await _methodChannel.invokeMethod('writeData', {
        'address': address,
        'data': data,
      });
    } finally {
      _isBusy = false;
    }
  }

  @override
  Future<void> enableBluetooth() async {
    await _methodChannel.invokeMethod('enableBluetooth');
  }

  @override
  Future<void> requestPermissions() async {
    await _methodChannel.invokeMethod('requestPermissions');
  }

  @override
  void dispose() {
    for (final address in _readSubscriptions.keys.toList()) {
      _cleanupReadStream(address);
    }
    _methodChannel.setMethodCallHandler(null);
    _progressCallback = null;
    _dataReceivedCallback = null;
    _errorCallback = null;
    super.dispose();
  }
}