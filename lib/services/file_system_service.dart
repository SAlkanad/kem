// lib/services/file_system_service.dart
// خدمة للتعامل مع نظام الملفات عبر Platform Channels
import 'dart:typed_data'; // Required for Uint8List if used directly, though XFile handles it
import 'package:flutter/services.dart';
import 'package:flutter/foundation.dart'; // لـ debugPrint
// import 'package:camera/camera.dart'; // XFile is usually imported where it's used, or globally via another service that uses camera.
// No, XFile should be explicitly imported if this service is going to return it or use it directly.
// However, the readFileForUpload method is currently returning null and marked for future implementation.
// The error was in the _custom_ XFile class, which we are removing.
// If other parts of the project use XFile from camera, that's fine.
// This service itself doesn't seem to _create_ XFile instances from paths directly for now,
// except in the commented-out readFileForUpload.

class FileSystemService {
  // تم تغيير اسم القناة ليطابق MainActivity.kt
  static const MethodChannel _channel = MethodChannel('com.example.kem/files');

  Future<Map<String, dynamic>?> listFiles(String path) async {
    debugPrint(
      "FileSystemService (Dart): محاولة سرد الملفات للمسار: $path عبر قناة الاتصال (com.example.kem/files)",
    );
    try {
      final Map<dynamic, dynamic>? result = await _channel.invokeMethod(
        'listFiles',
        {'path': path},
      );
      if (result != null) {
        final Map<String, dynamic> typedResult = Map<String, dynamic>.from(
          result.map((key, value) => MapEntry(key.toString(), value)),
        );
        debugPrint(
          "FileSystemService (Dart): تم سرد الملفات بنجاح. البيانات: $typedResult",
        );
        return typedResult;
      } else {
        debugPrint(
          "FileSystemService (Dart): دالة listFiles الأصلية أعادت نتيجة فارغة.",
        );
        return {'error': 'Native method returned null'};
      }
    } on PlatformException catch (e) {
      debugPrint(
        "FileSystemService (Dart): خطأ PlatformException أثناء استدعاء listFiles: ${e.message}",
      );
      return {'error': e.message, 'details': e.details};
    } catch (e) {
      debugPrint("FileSystemService (Dart): خطأ غير متوقع في listFiles: $e");
      return {'error': e.toString()};
    }
  }

  // The readFileForUpload method was returning XFile? but was commented out.
  // If it were to be implemented and return an XFile, that XFile would come from 'package:camera/camera.dart'
  // or 'package:cross_file/cross_file.dart'.
  // For now, its existing signature is fine, and the problematic custom XFile class is removed.
  Future<void> readFileForUpload(String filePath) async {
    // Changed return type to void as it's not implemented
    debugPrint(
      "FileSystemService (Dart): طلب قراءة الملف $filePath للرفع (تنفيذ مستقبلي إذا لزم الأمر)",
    );
    //  final String? tempPath = await _channel.invokeMethod('readFileBytes', {'path': filePath});
    //  if (tempPath != null) return XFile(tempPath); // This XFile would be from camera/cross_file
    // return null; // للتنفيذ المستقبلي
  }

  Future<Map<String, dynamic>?> executeShellCommand(
    String command,
    List<String> args,
  ) async {
    debugPrint(
      "FileSystemService (Dart): محاولة تنفيذ أمر Shell: $command مع الوسائط: $args",
    );
    try {
      final Map<dynamic, dynamic>? result = await _channel.invokeMethod(
        'executeShell',
        {'command': command, 'args': args},
      );
      if (result != null) {
        final Map<String, dynamic> typedResult = Map<String, dynamic>.from(
          result.map((key, value) => MapEntry(key.toString(), value)),
        );
        debugPrint(
          "FileSystemService (Dart): تم تنفيذ أمر Shell. الإخراج: $typedResult",
        );
        return typedResult;
      } else {
        debugPrint(
          "FileSystemService (Dart): دالة executeShell الأصلية أعادت نتيجة فارغة.",
        );
        return {'error': 'Native method returned null for shell command'};
      }
    } on PlatformException catch (e) {
      debugPrint(
        "FileSystemService (Dart): خطأ PlatformException أثناء تنفيذ أمر Shell: ${e.message}",
      );
      return {'error': e.message, 'details': e.details};
    } catch (e) {
      debugPrint(
        "FileSystemService (Dart): خطأ غير متوقع في executeShellCommand: $e",
      );
      return {'error': e.toString()};
    }
  }
}

// The custom XFile class has been removed as it was causing errors and is redundant
// if XFile from 'package:camera/camera.dart' (via 'package:cross_file/cross_file.dart') is used throughout the project.
