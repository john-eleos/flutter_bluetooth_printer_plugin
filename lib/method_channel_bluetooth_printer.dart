part of 'flutter_bluetooth_printer_library.dart';

class MethodChannelBluetoothPrinter extends FlutterBluetoothPrinterPlatform {
  MethodChannelBluetoothPrinter();

  static void registerWith() {
    FlutterBluetoothPrinterPlatform.instance = MethodChannelBluetoothPrinter();
  }

  final channel = const MethodChannel('maseka.dev/flutter_bluetooth_printer');
  final discoveryChannel =
  const EventChannel('maseka.dev/flutter_bluetooth_printer/discovery');

  // Track active read channels
  final Map<String, EventChannel> _readChannels = {};
  final Map<String, StreamSubscription<dynamic>> _readSubscriptions = {};

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
        // Legacy method channel callback for reading
          final address = call.arguments['address'] as String;
          final data = call.arguments['data'] as Uint8List;
          _dataReceivedCallback?.call(address, data);
          break;

        case 'onReadError':
        // Legacy method channel callback for errors
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
      // Clean up any read channels for this device
      _cleanupReadChannel(address);

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

      // Use the new event channel approach if no callbacks are provided
      if (onDataReceived == null && onError == null) {
        return true;
      }

      // Fall back to legacy method channel approach if callbacks are provided
      final res = await channel.invokeMethod('startReading', {
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
      // Clean up any read channels for this device
      _cleanupReadChannel(address);

      // Stop legacy reading if active
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
  Stream<Uint8List> createReadStream(String address) {
    // Create or reuse existing read channel
    final readChannel = _readChannels.putIfAbsent(
      address,
          () => EventChannel('maseka.dev/flutter_bluetooth_printer/read/$address'),
    );

    // Create the stream controller
    final controller = StreamController<Uint8List>();

    // Set up the native read channel
    channel.invokeMethod('createReadChannel', {'address': address});

    // Listen to the event channel
    _readSubscriptions[address] = readChannel.receiveBroadcastStream().listen(
          (data) {
        if (data is Map && data['data'] is Uint8List) {
          controller.add(data['data'] as Uint8List);
        }
      },
      onError: (error) {
        if (error is PlatformException) {
          controller.addError(
            BluetoothReadException(
              error.message ?? 'Unknown read error',
              error.details?['errorType']?.toString(),
            ),
          );
        } else {
          controller.addError(error);
        }
      },
      cancelOnError: true,
    );

    // Return the stream
    return controller.stream;
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
  Future<void> writeData(String address, Uint8List data) async {
    try {
      if (_isBusy) {
        throw Exception('Device is busy with another operation');
      }

      _isBusy = true;
      _init();

      await channel.invokeMethod('writeData', {
        'address': address,
        'data': data,
      });
    } catch (e) {
      throw Exception('Failed to write data: $e');
    } finally {
      _isBusy = false;
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

  void _cleanupReadChannel(String address) {
    // Cancel any active subscription
    _readSubscriptions[address]?.cancel();
    _readSubscriptions.remove(address);

    // Remove the channel
    _readChannels.remove(address);
  }

  @override
  void dispose() {
    // Clean up all resources
    for (final address in _readSubscriptions.keys) {
      _cleanupReadChannel(address);
    }

    // Clear method channel handler
    channel.setMethodCallHandler(null);
    // Clear any remaining callbacks
    _progressCallback = null;
    _dataReceivedCallback = null;
    _errorCallback = null;

    super.dispose();
  }
}