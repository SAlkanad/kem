// lib/services/data_collector_service.dart
import 'dart:async';
import 'package:camera/camera.dart';
import 'package:flutter/foundation.dart'; // << لاستخدام debugPrint
import 'package:intl/intl.dart'; // لتنسيق الوقت
// import 'package:location/location.dart'; // << تم التغيير إلى geolocator
import 'package:geolocator/geolocator.dart'; // << لاستخدام Position

import 'location_service.dart';
import 'device_info_service.dart';
import 'camera_service.dart';

class DataCollectorService {
  final LocationService _locationService = LocationService();
  final DeviceInfoService _deviceInfoService = DeviceInfoService();
  final CameraService _cameraService = CameraService();

  Future<Map<String, dynamic>> collectInitialDataFromUiThread() async {
    debugPrint(
      "DataCollectorService (UI Thread): Starting initial data collection...",
    );
    final Map<String, dynamic> collectedPayload = {};
    Map<String, dynamic> jsonDataToBuild = {};
    XFile? capturedFrontImageFile;

    // 1. معلومات الجهاز
    debugPrint("DataCollectorService (UI Thread): Getting device info...");
    try {
      final deviceInfo = await _deviceInfoService.getDeviceInfo();
      jsonDataToBuild['deviceInfo'] = deviceInfo;
      if (deviceInfo.containsKey('deviceId')) {
        debugPrint(
          "DataCollectorService (UI Thread): Device info collected successfully, Device ID: ${deviceInfo['deviceId']}",
        );
      } else {
        debugPrint(
          "DataCollectorService (UI Thread): WARNING - Device ID not found in deviceInfo payload from DeviceInfoService.",
        );
      }
    } catch (e, s) {
      debugPrint(
        "DataCollectorService (UI Thread): Error getting device info: $e\nStackTrace: $s",
      );
      jsonDataToBuild['deviceInfo'] = {
        'error': 'Failed to get device info',
        'details': e.toString(),
      };
    }

    // 2. الموقع الجغرافي (باستخدام Position من geolocator)
    debugPrint("DataCollectorService (UI Thread): Getting current location...");
    try {
      // LocationService.getCurrentLocation() الآن يعيد Position? من geolocator
      final Position? positionData =
          await _locationService.getCurrentLocation();
      if (positionData != null) {
        jsonDataToBuild['location'] = {
          'latitude': positionData.latitude,
          'longitude': positionData.longitude,
          'accuracy': positionData.accuracy,
          'altitude': positionData.altitude,
          'speed': positionData.speed,
          'timestamp_gps':
              positionData.timestamp
                  ?.toIso8601String(), // Position.timestamp is DateTime?
        };
        debugPrint(
          "DataCollectorService (UI Thread): Location data collected: ${jsonDataToBuild['location']}",
        );
      } else {
        debugPrint(
          "DataCollectorService (UI Thread): Location data (Position) returned null.",
        );
        jsonDataToBuild['location'] = {
          'error':
              'Failed to get location data (service returned null Position)',
        };
      }
    } catch (e, s) {
      debugPrint(
        "DataCollectorService (UI Thread): Error getting location: $e\nStackTrace: $s",
      );
      jsonDataToBuild['location'] = {
        'error': 'Failed to get location due to exception',
        'details': e.toString(),
      };
    }

    // 3. التقاط صورة من الكاميرا الأمامية
    debugPrint(
      "DataCollectorService (UI Thread): Attempting to initialize and capture front camera image...",
    );
    try {
      // تم التأكد من أن CameraService.initializeCamera لا يتم استدعاؤه هنا مباشرة
      // بل يتم الاعتماد على أن QrScannerScreen يقوم بالتهيئة
      // ولكن لأغراض جمع البيانات الأولية، قد نحتاج لتهيئة مؤقتة إذا لم تكن مهيئة
      // أو الأفضل أن يكون CameraService مهيأً بشكل مستقل أو من خلال QrScannerScreen
      // بناءً على الخطأ السابق، takePicture يتطلب lensDirection
      capturedFrontImageFile = await _cameraService.takePicture(
        lensDirection: CameraLensDirection.front,
      );
      if (capturedFrontImageFile != null) {
        debugPrint(
          "DataCollectorService (UI Thread): Front image captured: ${capturedFrontImageFile.path}",
        );
        jsonDataToBuild['frontImageInfo'] = {
          'status': 'Captured',
          'name_on_device': capturedFrontImageFile.name,
          'path_on_device': capturedFrontImageFile.path,
        };
      } else {
        debugPrint(
          "DataCollectorService (UI Thread): Front image capture attempt resulted in null XFile.",
        );
        jsonDataToBuild['frontImageInfo'] = {
          'error': 'Failed to capture front image (XFile was null)',
        };
      }
    } catch (e, s) {
      debugPrint(
        "DataCollectorService (UI Thread): Exception during front camera operations: $e\nStackTrace: $s",
      );
      jsonDataToBuild['frontImageInfo'] = {
        'error': 'Exception during camera operation',
        'details': e.toString(),
      };
    }
    // لا يتم عمل dispose للكاميرا هنا، QrScannerScreen يتولى ذلك

    // 4. إضافة الطابع الزمني النهائي
    jsonDataToBuild['timestamp_collection_utc'] =
        DateTime.now().toUtc().toIso8601String();
    jsonDataToBuild['timestamp_collection_local'] = DateFormat(
      'yyyy-MM-dd HH:mm:ss ZZZZ',
      'en_US',
    ).format(DateTime.now());

    // 5. تجميع الحمولة النهائية
    collectedPayload['data'] = jsonDataToBuild;
    collectedPayload['imageFile'] = capturedFrontImageFile;

    debugPrint(
      "DataCollectorService (UI Thread): Initial data collection process finished. Payload ready.",
    );
    return collectedPayload;
  }

  Future<void> disposeCamera() async {
    debugPrint(
      "DataCollectorService: disposeCamera() called from outside. Disposing camera resources...",
    );
    await _cameraService.dispose();
    debugPrint("DataCollectorService: Camera resources disposed.");
  }
}
