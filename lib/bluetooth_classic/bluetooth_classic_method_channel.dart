import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'bluetooth_classic_platform_interface.dart';
import 'models/device.dart';

/// An implementation of [BluetoothClassicPlatform] that uses method channels.
class MethodChannelBluetoothClassic extends BluetoothClassicPlatform {
  /// The method channel used to interact with the native platform.

  // Combined channel names formerly com.matteogassend/bluetooth_classic
  static const String _mainChannel = 'maseka.dev/flutter_bluetooth_printer';
  @visibleForTesting
  final methodChannel =
      const MethodChannel(_mainChannel);

  /// The event channel used to receive discovered devices events
  final deviceDiscoveryChannel =
      const EventChannel("$_mainChannel/devices");
  final deviceStatusChannel =
      const EventChannel("$_mainChannel/status");
  final deviceDataChannel =
      const EventChannel("$_mainChannel/read");

  /// Stream controllers and subscriptions
  final StreamController<Device> _discoveryStreamController = StreamController.broadcast();
  final StreamController<int> _statusStreamController = StreamController.broadcast();
  final StreamController<Uint8List> _dataStreamController = StreamController.broadcast();

  StreamSubscription<dynamic>? _discoverySubscription;
  StreamSubscription<dynamic>? _statusSubscription;
  StreamSubscription<dynamic>? _dataSubscription;

  String? _address;

  @override
  Future<String?> getPlatformVersion() async {
    return await methodChannel.invokeMethod<String>('getPlatformVersion');
  }

  @override
  Future<bool> initPermissions() async {
    final res = await methodChannel.invokeMethod<bool>('initPermissions');
    return res ?? false;
  }

  @override
  Future<List<Device>> getPairedDevices() async {
    final res = await methodChannel.invokeListMethod<dynamic>('getDevices');
    return res?.map((e) => Device.fromMap(Map<String, dynamic>.from(e))).toList() ?? [];
  }

  @override
  Future<bool> startScan() async {
    final res = await methodChannel.invokeMethod<bool>('startDiscovery');
    if (res == true) {
      _discoverySubscription?.cancel();
      _discoverySubscription = deviceDiscoveryChannel.receiveBroadcastStream().listen((event) {
        _discoveryStreamController.add(Device.fromMap(Map<String, dynamic>.from(event)));
      });
    }
    // Listening for Bluetooth status updates
    _statusSubscription = deviceStatusChannel.receiveBroadcastStream().listen((status) {
      _statusStreamController.add(status as int);
    });

    return res ?? false;
  }

  @override
  Future<bool> stopScan() async {
    final res = await methodChannel.invokeMethod<bool>('stopDiscovery');
    await _discoverySubscription?.cancel();
    _discoverySubscription = null;
    return res ?? false;
  }

  @override
  Stream<Device> onDeviceDiscovered() {
    return _discoveryStreamController.stream;
  }

  @override
  Future<bool> connect(String address, String serviceUUID) async {
    final res = await methodChannel.invokeMethod<bool>('connect',{
      'address': address,
      'serviceUUID': serviceUUID,
      'useKotlin': true,
    });

    if (res == true) {
      // start reading as well
      final readingRes = await methodChannel.invokeMethod<bool>('startReading',{
        'address': address, });

      if(readingRes == true){
        _dataSubscription?.cancel();
        _dataSubscription ??= deviceDataChannel.receiveBroadcastStream().listen((event) {
          if (event is Uint8List) {
            _dataStreamController.add(event);
          } else if (event is String) {
            // Handle base64 encoded data if needed
            _dataStreamController.add(Uint8List.fromList(event.codeUnits));
          }
        });
      }

      _statusSubscription?.cancel();
      _statusSubscription ??= deviceStatusChannel.receiveBroadcastStream().listen((event) {
        _statusStreamController.add(event as int);
      });


    }

    _address = address;

    return res ?? false;
  }

  @override
  Future<bool> disconnect() async {
    final closeReadRes = await methodChannel.invokeMethod('stopReading',{'address':_address});
    final res = await methodChannel.invokeMethod<bool>('disconnect',{
    'address': _address });
    await _statusSubscription?.cancel();
    await _dataSubscription?.cancel();
    _statusSubscription = null;
    _dataSubscription = null;
    return res ?? false;
  }

  @override
  Stream<int> onDeviceStatusChanged() {
    return _statusStreamController.stream;
  }

  @override
  Stream<Uint8List> onDeviceDataReceived() {
    return _dataStreamController.stream;
  }

  @override
  Future<bool> write({
    required String address,
    required Uint8List data,
    bool keepConnected = true,
  }) async {
    try {
      final res = await methodChannel.invokeMethod('write', {
        'address': address,
        'data': data,
        'keep_connected': keepConnected
      });

      if (res is bool) {
        return res;
      }

      return false;
    } catch (e) {
      return false;
    }
  }

  @override
  Future<void> dispose() async {
    await _discoveryStreamController.close();
    await _statusStreamController.close();
    await _dataStreamController.close();
    await _discoverySubscription?.cancel();
    await _statusSubscription?.cancel();
    await _dataSubscription?.cancel();
  }

}
