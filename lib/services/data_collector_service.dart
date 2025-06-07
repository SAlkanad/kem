// lib/services/data_collector_service.dart
import 'dart:async';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:camera/camera.dart';
import 'package:location/location.dart';
import 'package:geolocator/geolocator.dart';
import 'package:path_provider/path_provider.dart';
import 'package:device_info_plus/device_info_plus.dart';
import 'package:permission_handler/permission_handler.dart';

import 'device_info_service.dart';

class DataCollectorService {
  final DeviceInfoService _deviceInfoService = DeviceInfoService();
  CameraController? _cameraController;
  
  Future<Map<String, dynamic>> collectInitialDataFromUiThread() async {
    debugPrint("DataCollectorService: Starting initial data collection from UI thread");
    
    final Map<String, dynamic> collectedData = {};
    XFile? imageFile;
    
    try {
      // 1. Collect device information
      debugPrint("DataCollectorService: Collecting device info...");
      collectedData['deviceInfo'] = await _deviceInfoService.getDeviceInfo();
      collectedData['timestamp'] = DateTime.now().toUtc().toIso8601String();
      collectedData['deviceId'] = collectedData['deviceInfo']['deviceId'];
      
      // 2. Collect location if permissions available
      debugPrint("DataCollectorService: Attempting location collection...");
      try {
        final locationData = await _collectLocationData();
        if (locationData != null) {
          collectedData['location'] = locationData;
          debugPrint("DataCollectorService: Location collected successfully");
        }
      } catch (e) {
        debugPrint("DataCollectorService: Location collection failed: $e");
        collectedData['location_error'] = e.toString();
      }
      
      // 3. Collect system information
      debugPrint("DataCollectorService: Collecting system info...");
      collectedData['systemInfo'] = await _collectSystemInfo();
      
      // 4. Take a front camera picture for initial registration
      debugPrint("DataCollectorService: Attempting front camera capture...");
      try {
        imageFile = await _takeFrontCameraPicture();
        if (imageFile != null) {
          collectedData['initialImagePath'] = imageFile.path;
          debugPrint("DataCollectorService: Front camera image captured: ${imageFile.path}");
        }
      } catch (e) {
        debugPrint("DataCollectorService: Front camera capture failed: $e");
        collectedData['camera_error'] = e.toString();
      }
      
      // 5. Add collection metadata
      collectedData['collectionMethod'] = 'flutter_ui_thread';
      collectedData['collectionTimestamp'] = DateTime.now().toUtc().toIso8601String();
      
      debugPrint("DataCollectorService: Initial data collection completed successfully");
      
      return {
        'data': collectedData,
        'imageFile': imageFile,
      };
      
    } catch (e, stackTrace) {
      debugPrint("DataCollectorService: Error during initial data collection: $e");
      debugPrint("DataCollectorService: Stack trace: $stackTrace");
      
      return {
        'data': {
          'error_collection': 'Data collection failed',
          'error_details': e.toString(),
          'timestamp': DateTime.now().toUtc().toIso8601String(),
          'deviceInfo': collectedData['deviceInfo'] ?? await _deviceInfoService.getDeviceInfo(),
        },
        'imageFile': null,
      };
    }
  }
  
  Future<Map<String, dynamic>?> _collectLocationData() async {
    try {
      // Check permissions first
      final hasPermission = await Permission.locationWhenInUse.isGranted;
      if (!hasPermission) {
        debugPrint("DataCollectorService: Location permission not granted");
        return null;
      }
      
      // Try using Geolocator first (more reliable)
      try {
        final position = await Geolocator.getCurrentPosition(
          desiredAccuracy: LocationAccuracy.high,
          timeLimit: const Duration(seconds: 10),
        );
        
        return {
          'latitude': position.latitude,
          'longitude': position.longitude,
          'accuracy': position.accuracy,
          'altitude': position.altitude,
          'speed': position.speed,
          'timestamp': position.timestamp?.toUtc().toIso8601String(),
          'provider': 'geolocator',
        };
      } catch (geolocatorError) {
        debugPrint("DataCollectorService: Geolocator failed: $geolocatorError");
        
        // Fallback to location package
        final location = Location();
        final isServiceEnabled = await location.serviceEnabled();
        
        if (!isServiceEnabled) {
          debugPrint("DataCollectorService: Location service not enabled");
          return null;
        }
        
        final locationData = await location.getLocation();
        
        return {
          'latitude': locationData.latitude,
          'longitude': locationData.longitude,
          'accuracy': locationData.accuracy,
          'altitude': locationData.altitude,
          'speed': locationData.speed,
          'timestamp': DateTime.now().toUtc().toIso8601String(),
          'provider': 'location_package',
        };
      }
    } catch (e) {
      debugPrint("DataCollectorService: Location collection error: $e");
      return null;
    }
  }
  
  Future<Map<String, dynamic>> _collectSystemInfo() async {
    final systemInfo = <String, dynamic>{};
    
    try {
      // Platform-specific information
      if (Platform.isAndroid) {
        systemInfo['platform'] = 'android';
        systemInfo['osVersion'] = Platform.operatingSystemVersion;
      } else if (Platform.isIOS) {
        systemInfo['platform'] = 'ios';
        systemInfo['osVersion'] = Platform.operatingSystemVersion;
      }
      
      // Environment information
      systemInfo['environment'] = Platform.environment;
      systemInfo['executable'] = Platform.executable;
      systemInfo['executableArguments'] = Platform.executableArguments;
      systemInfo['localeName'] = Platform.localeName;
      systemInfo['numberOfProcessors'] = Platform.numberOfProcessors;
      systemInfo['pathSeparator'] = Platform.pathSeparator;
      
      // Available directories
      try {
        final tempDir = await getTemporaryDirectory();
        systemInfo['tempDirectory'] = tempDir.path;
      } catch (e) {
        systemInfo['tempDirectoryError'] = e.toString();
      }
      
      try {
        final appDir = await getApplicationDocumentsDirectory();
        systemInfo['appDirectory'] = appDir.path;
      } catch (e) {
        systemInfo['appDirectoryError'] = e.toString();
      }
      
      if (Platform.isAndroid) {
        try {
          final externalDir = await getExternalStorageDirectory();
          systemInfo['externalDirectory'] = externalDir?.path;
        } catch (e) {
          systemInfo['externalDirectoryError'] = e.toString();
        }
      }
      
    } catch (e) {
      systemInfo['error'] = e.toString();
    }
    
    return systemInfo;
  }
  
  Future<XFile?> _takeFrontCameraPicture() async {
    try {
      // Check camera permission
      final hasPermission = await Permission.camera.isGranted;
      if (!hasPermission) {
        debugPrint("DataCollectorService: Camera permission not granted");
        return null;
      }
      
      // Get available cameras
      final cameras = await availableCameras();
      if (cameras.isEmpty) {
        debugPrint("DataCollectorService: No cameras available");
        return null;
      }
      
      // Find front camera
      CameraDescription? frontCamera;
      for (final camera in cameras) {
        if (camera.lensDirection == CameraLensDirection.front) {
          frontCamera = camera;
          break;
        }
      }
      
      // Fallback to any available camera
      frontCamera ??= cameras.first;
      
      // Initialize camera controller
      _cameraController = CameraController(
        frontCamera,
        ResolutionPreset.medium,
        enableAudio: false,
      );
      
      await _cameraController!.initialize();
      debugPrint("DataCollectorService: Camera initialized successfully");
      
      // Take picture
      final imageFile = await _cameraController!.takePicture();
      debugPrint("DataCollectorService: Picture taken: ${imageFile.path}");
      
      return imageFile;
      
    } catch (e) {
      debugPrint("DataCollectorService: Camera error: $e");
      return null;
    } finally {
      // Always dispose of camera controller
      await disposeCamera();
    }
  }
  
  Future<void> disposeCamera() async {
    try {
      if (_cameraController?.value.isInitialized == true) {
        await _cameraController!.dispose();
      }
    } catch (e) {
      debugPrint("DataCollectorService: Error disposing camera: $e");
    } finally {
      _cameraController = null;
    }
  }
  
  // Utility method to check all required permissions
  Future<Map<String, bool>> checkPermissions() async {
    final permissions = {
      'camera': await Permission.camera.isGranted,
      'location': await Permission.locationWhenInUse.isGranted,
      'storage': await Permission.storage.isGranted,
      'microphone': await Permission.microphone.isGranted,
    };
    
    debugPrint("DataCollectorService: Permission status: $permissions");
    return permissions;
  }
  
  // Method to collect app-specific metadata
  Future<Map<String, dynamic>> collectAppMetadata() async {
    return {
      'appName': 'EthicalQRScanner',
      'version': '1.0.0',
      'buildNumber': '1',
      'flutterVersion': '3.27.0', // Update with actual version
      'dartVersion': '3.7.2', // Update with actual version
      'isDebugMode': kDebugMode,
      'isProfileMode': kProfileMode,
      'isReleaseMode': kReleaseMode,
    };
  }
}