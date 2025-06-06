// lib/services/device_info_service.dart
import 'dart:io' show Platform;
import 'package:device_info_plus/device_info_plus.dart';
import 'package:flutter/foundation.dart' show kIsWeb, debugPrint;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:uuid/uuid.dart'; // <<<--- إضافة Uuid
import '../utils/constants.dart'; // <<<--- إضافة للوصول إلى PREF_DEVICE_ID

class DeviceInfoService {
  final DeviceInfoPlugin _deviceInfoPlugin = DeviceInfoPlugin();
  final Uuid _uuid = Uuid(); // <<<--- إنشاء كائن Uuid

  /// Generates or retrieves a unique device ID.
  /// Prefers Android ID or identifierForVendor (iOS) if available and seems persistent.
  /// Falls back to a UUID stored in SharedPreferences.
  Future<String> getOrCreateUniqueDeviceId() async {
    final prefs = await SharedPreferences.getInstance();
    String? deviceId = prefs.getString(PREF_DEVICE_ID);

    if (deviceId != null && deviceId.isNotEmpty) {
      debugPrint("DeviceInfoService: Retrieved existing Device ID: $deviceId");
      return deviceId;
    }

    // Try to get a more "native" persistent ID first
    try {
      if (Platform.isAndroid) {
        final androidInfo = await _deviceInfoPlugin.androidInfo;
        // androidId is often unique but can be null or change on factory reset.
        // Consider using a combination or a more robust method if high persistence is critical.
        // For educational purposes, a UUID fallback is fine.
        if (androidInfo.id != null && androidInfo.id!.isNotEmpty) {
          // 'id' (SSAID) for Android. It can change on factory reset, or if user clears app data on some Android versions.
          // For more robust unique ID, you might need other strategies if this is not sufficient.
          deviceId =
              "android_${androidInfo.id}"; // Prefix to know it's an Android ID
        }
      } else if (Platform.isIOS) {
        final iosInfo = await _deviceInfoPlugin.iosInfo;
        // identifierForVendor is unique to the app's vendor on that device.
        // It changes if the user uninstalls all apps from that vendor and then reinstalls.
        if (iosInfo.identifierForVendor != null &&
            iosInfo.identifierForVendor!.isNotEmpty) {
          deviceId = "ios_${iosInfo.identifierForVendor!}";
        }
      }
    } catch (e) {
      debugPrint(
        "DeviceInfoService: Error getting native device ID: $e. Falling back to UUID.",
      );
    }

    // If native ID wasn't available or we prefer UUID always for consistency in this project
    if (deviceId == null || deviceId.isEmpty) {
      deviceId = _uuid.v4();
      debugPrint(
        "DeviceInfoService: Generated new UUID for Device ID: $deviceId",
      );
    }

    await prefs.setString(PREF_DEVICE_ID, deviceId!);
    debugPrint("DeviceInfoService: Saved Device ID: $deviceId");
    return deviceId!;
  }

  Future<Map<String, dynamic>> getDeviceInfo() async {
    final Map<String, dynamic> deviceData = {
      'deviceId': 'unknown_id', // <<<--- إضافة حقل جديد لمعرف الجهاز
      'platform': 'unknown_platform',
      'osVersion': 'unknown_os_version',
      'model': 'unknown_model',
      'deviceName':
          'unknown_device_name', // هذا عادة الاسم الذي يحدده المستخدم للجهاز
      'brand': 'unknown_brand', // e.g. "samsung"
      'isPhysicalDevice': 'unknown',
      'systemFeatures': <String>[], // For Android specific features
    };

    try {
      deviceData['deviceId'] =
          await getOrCreateUniqueDeviceId(); // <<<--- الحصول على المعرف الفريد

      if (kIsWeb) {
        deviceData['platform'] = 'web';
        final webBrowserInfo = await _deviceInfoPlugin.webBrowserInfo;
        deviceData['osVersion'] = webBrowserInfo.platform ?? 'N/A';
        deviceData['model'] =
            webBrowserInfo.browserName.toString().split('.').last;
        deviceData['deviceName'] =
            webBrowserInfo.userAgent?.substring(
              0,
              (webBrowserInfo.userAgent?.length ?? 0) > 200
                  ? 200
                  : (webBrowserInfo.userAgent?.length ?? 0),
            ) ??
            'N/A'; // Limit length
        deviceData['brand'] = webBrowserInfo.vendor ?? 'N/A';
        deviceData['isPhysicalDevice'] = 'false';
      } else if (Platform.isAndroid) {
        final androidInfo = await _deviceInfoPlugin.androidInfo;
        deviceData['platform'] = 'android';
        deviceData['osVersion'] = androidInfo.version.release; // e.g., "13"
        deviceData['brand'] = androidInfo.brand; // e.g., "samsung"
        deviceData['model'] = androidInfo.model; // e.g., "SM-G991U"
        // 'device' is often the codename, 'product' is the product name seen by user
        deviceData['deviceName'] =
            androidInfo.device; // e.g., "starqltesq" - often internal name
        // androidInfo.display might be better for "user-facing" name
        // Consider what the C2 panel expects for 'deviceName' from its logic
        deviceData['isPhysicalDevice'] =
            androidInfo.isPhysicalDevice.toString();
        // deviceData['androidId_debug'] = androidInfo.id; // For debugging if needed
        deviceData['systemFeatures'] = androidInfo.systemFeatures;
      } else if (Platform.isIOS) {
        final iosInfo = await _deviceInfoPlugin.iosInfo;
        deviceData['platform'] = 'ios';
        deviceData['osVersion'] = iosInfo.systemVersion; // e.g., "16.5"
        deviceData['model'] =
            iosInfo.model; // e.g., "iPhone14,5" (internal model name)
        deviceData['deviceName'] = iosInfo.name; // e.g., "John's iPhone"
        deviceData['brand'] = 'Apple';
        deviceData['isPhysicalDevice'] = iosInfo.isPhysicalDevice.toString();
        // deviceData['identifierForVendor_debug'] = iosInfo.identifierForVendor; // For debugging
      }
      // ... (يمكنك إضافة دعم لمنصات أخرى إذا أردت، بنفس الطريقة) ...
    } catch (e, s) {
      debugPrint(
        "DeviceInfoService: Error getting device info: $e\nStackTrace: $s",
      );
      deviceData['error_device_info'] = e.toString();
    }
    return deviceData;
  }
}
