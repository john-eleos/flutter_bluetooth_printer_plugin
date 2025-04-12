part of '../flutter_bluetooth_printer_library.dart';

class DiscoveryResult extends DiscoveryState {
  final List<BluetoothDevice> devices;

  DiscoveryResult({required this.devices});

  @override
  String toString() => 'DiscoveryResult(devices: $devices)';
}

enum PaperSize {
  mm58(360, 58, 'Roll Paper 58mm'),
  mm80(576, 80, 'Roll Paper 80mm');

  final int width;
  final double paperWidthMM;
  final String name;

  const PaperSize(this.width, this.paperWidthMM, this.name);

  @override
  String toString() => name;
}

class FlutterBluetoothPrinter {
  static final MethodChannelBluetoothPrinter _reader = MethodChannelBluetoothPrinter();

  static Stream<DiscoveryState> _discovery() async* {
    final result = <BluetoothDevice>[];

    await for (final state in FlutterBluetoothPrinterPlatform.instance.discovery) {
      if (state is BluetoothDevice) {
        result.add(state);
        yield DiscoveryResult(devices: result.toSet().toList());
      } else {
        result.clear();
        yield state;
      }
    }
  }

  static ValueNotifier<BluetoothConnectionState> get connectionStateNotifier =>
      FlutterBluetoothPrinterPlatform.instance.connectionStateNotifier;

  static Stream<DiscoveryState> get discovery => _discovery();

  /// [Deprecated] Prefer using [readStream] for a more modern stream-based approach
  @Deprecated('Prefer using readStream for better stream handling')
  static Future<bool> startReading({
    required String address,
    required void Function(String address, Uint8List data) onDataReceived,
    void Function(String address, String error, String? errorType)? onError,
  }) {
    return _reader.startReading(
      onDataReceived: onDataReceived,
      onError: onError, address: address,
    );
  }

  /// Stops reading data from the device
  static Future<bool> stopReading(String address) {
    return _reader.stopReading(address);
  }

  /// Creates a continuous stream of data from the Bluetooth device
  static Stream<Uint8List> readStream(String address) {
    return _reader.createReadStream(address);
  }

  /// Sends raw data to the connected Bluetooth device
  static Future<void> writeData({
    required String address,
    required Uint8List data,
  }) {
    return _reader.writeData(address, data);
  }

  /// Sends a string command to the connected Bluetooth device
  static Future<void> sendCommand({
    required String address,
    required String command,
  }) {
    return writeData(
      address: address,
      data: Uint8List.fromList(command.codeUnits),
    );
  }

  /* PRINTING FUNCTIONALITY */
  /// Prints raw bytes to the printer
  static Future<bool> printBytes({
    required String address,
    required Uint8List data,
    bool keepConnected = false,
    int maxBufferSize = 512,
    int delayTime = 120,
    ProgressCallback? onProgress,
  }) {
    return FlutterBluetoothPrinterPlatform.instance.write(
      address: address,
      data: data,
      keepConnected: keepConnected,
      maxBufferSize: maxBufferSize,
      delayTime: delayTime,
      onProgress: onProgress,
    );
  }

  /// Calculates estimated printing duration in milliseconds
  static double calculatePrintingDurationInMilliseconds(
      int heightInDots,
      double printSpeed,
      int dotsPerLine,
      double paperWidth,
      int dotsPerLineHeight,
      ) {
    final numberOfLines = heightInDots / dotsPerLineHeight;
    final linesPerSecond = printSpeed / paperWidth;
    final durationSeconds = numberOfLines / linesPerSecond;
    return durationSeconds * 1000;
  }

  /// Prints an image to the printer
  static Future<bool> printImageSingle({
    required String address,
    required Uint8List imageBytes,
    required int imageWidth,
    required int imageHeight,
    PaperSize paperSize = PaperSize.mm58,
    ProgressCallback? onProgress,
    int addFeeds = 0,
    bool useImageRaster = true,
    bool keepConnected = false,
    int maxBufferSize = 512,
    int delayTime = 120,
  }) async {
    try {
      final generator = Generator();
      final reset = generator.reset();

      final imageData = await generator.encode(
        bytes: imageBytes,
        dotsPerLine: paperSize.width,
        useImageRaster: useImageRaster,
      );

      await _initialize(address: address);

      // Wait for printer initialization
      await Future.delayed(const Duration(milliseconds: 400));

      final additional = paperSize == PaperSize.mm58
          ? <int>[for (int i = 0; i < addFeeds; i++) ...Commands.carriageReturn]
          : <int>[for (int i = 0; i < addFeeds; i++) ...Commands.lineFeed];

      return await printBytes(
        address: address,
        data: Uint8List.fromList([...imageData, ...reset, ...additional]),
        keepConnected: keepConnected,
        onProgress: onProgress,
        maxBufferSize: maxBufferSize,
        delayTime: delayTime,
      );
    } catch (e) {
      return false;
    } finally {
      if (!keepConnected) {
        await disconnect(address);
      }
    }
  }

  /* DEVICE MANAGEMENT */
  /// Enables Bluetooth if disabled
  static Future<void> enableBluetooth() {
    return FlutterBluetoothPrinterPlatform.instance.enableBluetooth();
  }

  /// Requests necessary permissions
  static Future<void> requestPermissions() {
    return FlutterBluetoothPrinterPlatform.instance.requestPermissions();
  }

  static Future<bool> _initialize({required String address}) async {
    final isConnected = await connect(address);
    if (!isConnected) return false;

    final generator = Generator();
    final reset = generator.reset();
    return printBytes(
      address: address,
      data: Uint8List.fromList(reset),
      keepConnected: true,
    );
  }

  /// Shows a device selection dialog
  static Future<BluetoothDevice?> selectDevice(BuildContext context) async {
    final selected = await showModalBottomSheet(
      context: context,
      builder: (context) => const BluetoothDeviceSelector(),
    );
    return selected is BluetoothDevice ? selected : null;
  }

  /// Disconnects from a device
  static Future<bool> disconnect(String address) {
    return FlutterBluetoothPrinterPlatform.instance.disconnect(address);
  }

  /// Connects to a device with optional timeout
  static Future<bool> connect(String address, {int timeout = 10000}) {
    return FlutterBluetoothPrinterPlatform.instance.connect(
      address,
      timeout: timeout,
    );
  }

  /// Checks the current Bluetooth state
  static Future<BluetoothState> getState() {
    return FlutterBluetoothPrinterPlatform.instance.checkState();
  }

  /// Disposes all resources
  static void dispose() {
    _reader.dispose();
  }
}