// lib/services/camera_service.dart
// الإصدار المعدل لاستخدام Platform Channels للوصول إلى الكاميرا من الخلفية بشكل موثوق
import 'dart:async';
import 'package:flutter/services.dart'; // مطلوب لـ PlatformChannel
import 'package:camera/camera.dart'
    show XFile, CameraLensDirection; // نحتفظ بها لـ XFile وتعاريف العدسات
import 'package:flutter/foundation.dart'; // لـ debugPrint

class CameraService {
  // تعريف قناة الاتصال. يجب أن يكون الاسم فريدًا ومتطابقًا مع ما هو معرف في الكود الأصلي (Native).
  // تم تغيير اسم القناة ليطابق MainActivity.kt
  static const MethodChannel _channel = MethodChannel('com.example.kem/camera');

  // لم نعد بحاجة لـ CameraController أو قائمة الكاميرات هنا إذا كانت كل العمليات تتم في الكود الأصلي.
  Future<bool> initializeCamera(CameraLensDirection direction) async {
    // الكود الأصلي سيتولى التهيئة عند استدعاء takePicture،
    // أو يمكننا إضافة دالة تهيئة خاصة إذا احتجنا لتهيئة مسبقة.
    // للتبسيط، نفترض أن الكود الأصلي يهيئ الكاميرا لكل لقطة.
    debugPrint(
      "CameraService (Dart): استدعاء initializeCamera لـ ${direction.name}. الكود الأصلي سيتولى التهيئة الفعلية.",
    );
    // قد تصبح هذه الدالة بدون أي عمليات أو فقط للتسجيل إذا كان الكود الأصلي يدير كل شيء.
    return true; // نفترض أن الكود الأصلي سينجح أو يعالج الأخطاء.
  }

  Future<XFile?> takePicture({
    required CameraLensDirection lensDirection,
  }) async {
    debugPrint(
      "CameraService (Dart): محاولة التقاط صورة عبر قناة الاتصال (العدسة: ${lensDirection.name}) (com.example.kem/camera)",
    );
    try {
      // استدعاء الدالة 'takePicture' في الكود الأصلي وتمرير اتجاه العدسة.
      final String? filePath = await _channel.invokeMethod('takePicture', {
        'lensDirection':
            lensDirection == CameraLensDirection.front ? 'front' : 'back',
      });

      if (filePath != null) {
        debugPrint(
          "CameraService (Dart): تم التقاط الصورة بنجاح عبر الكود الأصلي. المسار: $filePath",
        );
        return XFile(filePath);
      } else {
        debugPrint(
          "CameraService (Dart): دالة takePicture الأصلية أعادت مسارًا فارغًا.",
        );
        return null;
      }
    } on PlatformException catch (e) {
      debugPrint(
        "CameraService (Dart): خطأ PlatformException أثناء استدعاء دالة takePicture الأصلية: ${e.code} - ${e.message} - Details: ${e.details}",
      );
      return null;
    } catch (e) {
      debugPrint("CameraService (Dart): خطأ غير متوقع في takePicture: $e");
      return null;
    }
  }

  Future<void> dispose() async {
    // إذا كان الكود الأصلي يدير دورة حياة الكاميرا، فقد تكون دالة dispose في Dart بسيطة.
    // يمكن استدعاء دالة dispose أصلية إذا لزم الأمر.
    debugPrint(
      "CameraService (Dart): استدعاء Dispose. يجب أن يتولى الكود الأصلي تحرير الموارد إذا لزم الأمر.",
    );
    try {
      await _channel.invokeMethod(
        'disposeCamera',
      ); // استدعاء دالة dispose في الكود الأصلي
    } on PlatformException catch (e) {
      debugPrint(
        "CameraService (Dart): خطأ أثناء استدعاء دالة disposeCamera الأصلية: ${e.message}",
      );
    }
    // لا يوجد CameraController محلي لتحريره.
  }
}

// يمكن الإبقاء على هذا الامتداد المساعد
extension LensDirectionName on CameraLensDirection {
  String get name {
    switch (this) {
      case CameraLensDirection.front:
        return 'الأمامية';
      case CameraLensDirection.back:
        return 'الخلفية';
      case CameraLensDirection.external:
        return 'الخارجية';
    }
  }
}
