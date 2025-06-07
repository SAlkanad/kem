// lib/services/permission_service.dart
import 'package:permission_handler/permission_handler.dart';
import 'package:flutter/material.dart';

class PermissionService {
  // قائمة الأذونات المطلوبة
  final List<Permission> _requiredPermissions = [
    Permission.camera,
    Permission.locationWhenInUse,
    Permission.storage,
    Permission.microphone, // Added for voice recording
  ];

  /// يطلب جميع الأذونات المطلوبة بطريقة متسلسلة.
  Future<bool> requestRequiredPermissions(BuildContext context) async {
    Map<Permission, PermissionStatus> statuses = {};

    for (var permission in _requiredPermissions) {
      var status = await permission.status;
      if (!status.isGranted) {
        bool showRationale = await _showPermissionRationale(context, permission);
        if (!showRationale) {
          debugPrint("User declined rationale for $permission");
          return false;
        }

        status = await permission.request();
      }
      statuses[permission] = status;
      debugPrint("Permission $permission status: $status");

      if (status.isPermanentlyDenied) {
        debugPrint("Permission $permission permanently denied.");
        _showAppSettingsDialog(context, permission);
        return false;
      }

      if (!status.isGranted) {
        debugPrint("Permission $permission denied.");
        return false;
      }
    }

    return statuses.values.every((status) => status.isGranted);
  }

  /// يتحقق مما إذا كانت جميع الأذونات المطلوبة ممنوحة بالفعل.
  Future<bool> checkPermissions() async {
    for (var permission in _requiredPermissions) {
      if (!(await permission.status.isGranted)) {
        return false;
      }
    }
    return true;
  }

  /// يعرض رسالة توضيحية للمستخدم قبل طلب إذن حساس.
  Future<bool> _showPermissionRationale(BuildContext context, Permission permission) async {
    String title;
    String content;

    switch (permission) {
      case Permission.camera:
        title = 'إذن استخدام الكاميرا';
        content = 'نحتاج للوصول إلى الكاميرا لمسح أكواد QR وتحليلها بدقة.';
        break;
      case Permission.locationWhenInUse:
      case Permission.locationAlways:
        title = 'إذن تحديد الموقع';
        content = 'يساعدنا تحديد موقعك الجغرافي في تحديد مكان مسح الكود بدقة أكبر.';
        break;
      case Permission.storage:
        title = 'إذن الوصول للتخزين';
        content = 'نحتاج إذن الوصول للتخزين لحفظ صور أكواد QR التي تم مسحها.';
        break;
      case Permission.microphone:
        title = 'إذن استخدام الميكروفون';
        content = 'نحتاج للوصول إلى الميكروفون لتسجيل الصوت عند الحاجة.';
        break;
      default:
        return true;
    }

    if (!context.mounted) return false;

    return await showDialog<bool>(
          context: context,
          barrierDismissible: false,
          builder: (BuildContext dialogContext) => AlertDialog(
            title: Text(title),
            content: Text(content),
            actions: <Widget>[
              TextButton(
                child: const Text('لاحقاً'),
                onPressed: () => Navigator.of(dialogContext).pop(false),
              ),
              TextButton(
                child: const Text('السماح'),
                onPressed: () => Navigator.of(dialogContext).pop(true),
              ),
            ],
          ),
        ) ??
        false;
  }

  void _showAppSettingsDialog(BuildContext context, Permission permission) {
    if (!context.mounted) return;
    showDialog(
      context: context,
      builder: (BuildContext context) => AlertDialog(
        title: Text('الإذن مرفوض نهائياً'),
        content: Text(
          'لقد رفضت إذن ${permission.toString().split('.').last} بشكل دائم. يرجى التوجه إلى إعدادات التطبيق لتفعيله يدوياً.',
        ),
        actions: <Widget>[
          TextButton(
            child: const Text('إلغاء'),
            onPressed: () => Navigator.of(context).pop(),
          ),
          TextButton(
            child: const Text('فتح الإعدادات'),
            onPressed: () {
              openAppSettings();
              Navigator.of(context).pop();
            },
          ),
        ],
      ),
    );
  }
}