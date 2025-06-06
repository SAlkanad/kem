// lib/services/location_service.dart
// الإصدار المعدل لاستخدام إضافة geolocator للعمل بشكل أفضل في الخلفية

import 'dart:async'; // Required for TimeoutException
import 'package:flutter/foundation.dart';
import 'package:geolocator/geolocator.dart';
import 'package:flutter/services.dart';

class LocationService {
  // لا حاجة لإنشاء كائن من Geolocator، فالدوال ثابتة (static)

  Future<Position?> getCurrentLocation() async {
    bool serviceEnabled;
    LocationPermission permission;

    // التحقق من أن خدمات الموقع مفعلة على الجهاز
    serviceEnabled = await Geolocator.isLocationServiceEnabled();
    if (!serviceEnabled) {
      debugPrint("LocationService (geolocator): خدمات الموقع معطلة.");
      return null;
    }

    permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      debugPrint(
        "LocationService (geolocator): إذن الموقع مرفوض. محاولة طلب الإذن...",
      );
      permission = await Geolocator.requestPermission();
      if (permission == LocationPermission.denied) {
        debugPrint("LocationService (geolocator): طلب إذن الموقع مرفوض.");
        return null;
      }
    }

    if (permission == LocationPermission.deniedForever) {
      debugPrint("LocationService (geolocator): إذن الموقع مرفوض بشكل دائم.");
      return null;
    }

    debugPrint(
      "LocationService (geolocator): الأذونات ممنوحة وخدمات الموقع مفعلة. محاولة الحصول على الموقع...",
    );

    try {
      return await Geolocator.getCurrentPosition(
        desiredAccuracy: LocationAccuracy.high,
        // timeLimit: const Duration(seconds: 15), // Can be added if needed
      );
    } on PlatformException catch (e) {
      debugPrint(
        "LocationService (geolocator): خطأ PlatformException أثناء الحصول على الموقع: ${e.code} - ${e.message}",
      );
      return null;
    } on LocationServiceDisabledException catch (e) {
      debugPrint(
        "LocationService (geolocator): خطأ LocationServiceDisabledException: ${e.toString()}",
      );
      return null;
    } on TimeoutException catch (e) {
      // Now TimeoutException is recognized
      debugPrint(
        "LocationService (geolocator): انتهت مهلة الحصول على الموقع: ${e.message}",
      );
      return null;
    } catch (e) {
      debugPrint(
        "LocationService (geolocator): خطأ غير متوقع أثناء الحصول على الموقع: $e",
      );
      return null;
    }
  }
}
