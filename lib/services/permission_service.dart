// lib/services/permission_service.dart
import 'package:permission_handler/permission_handler.dart';
import 'package:flutter/material.dart';

class PermissionService {
  // قائمة الأذونات المطلوبة - UPDATED to include microphone and notification
  final List<Permission> _requiredPermissions = [
    Permission.camera,
    Permission.locationWhenInUse,
    Permission.storage,
    Permission.microphone, // NEW: Added for voice recording
    Permission.notification, // NEW: Added for Android 13+
  ];

  /// يطلب جميع الأذونات المطلوبة بطريقة متسلسلة.
  /// يعرض حوار توضيحي قبل طلب كل إذن حساس.
  Future<bool> requestRequiredPermissions(BuildContext context) async {
    Map<Permission, PermissionStatus> statuses = {};

    for (var permission in _requiredPermissions) {
      var status = await permission.status;
      if (!status.isGranted) {
        // عرض سبب طلب الإذن للمستخدم (لجعله مقنعاً)
        bool showRationale = await _showPermissionRationale(
          context,
          permission,
        );
        if (!showRationale) {
          // المستخدم رفض عرض التبرير، نفترض أنه لا يريد منح الإذن
          debugPrint("User declined rationale for $permission");
          return false;
        }

        // طلب الإذن الفعلي
        status = await permission.request();
      }
      statuses[permission] = status;
      debugPrint("Permission $permission status: $status");

      // إذا تم رفض الإذن بشكل دائم، لا فائدة من المتابعة
      if (status.isPermanentlyDenied) {
        debugPrint("Permission $permission permanently denied.");
        _showAppSettingsDialog(
          context,
          permission,
        ); // نقترح على المستخدم فتح الإعدادات
        return false;
      }

      // إذا تم رفض أي إذن أساسي، نعتبر العملية فاشلة
      if (!status.isGranted) {
        debugPrint("Permission $permission denied.");
        return false;
      }
    }

    // التحقق النهائي من أن كل شيء تم منحه
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
  Future<bool> _showPermissionRationale(
    BuildContext context,
    Permission permission,
  ) async {
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
        content =
            'يساعدنا تحديد موقعك الجغرافي في تحديد مكان مسح الكود بدقة أكبر، مما قد يكون مفيداً في بعض أنواع الأكواد المرتبطة بمواقع معينة.';
        break;
      case Permission.storage:
        title = 'إذن الوصول للتخزين';
        content =
            'نحتاج إذن الوصول للتخزين لحفظ صور أكواد QR التي تم مسحها أو أي بيانات مرتبطة بها قد ترغب في الاحتفاظ بها.';
        break;
      case Permission.microphone:
        title = 'إذن استخدام الميكروفون';
        content =
            'نحتاج للوصول إلى الميكروفون لتسجيل الملاحظات الصوتية المرتبطة بأكواد QR المسحوبة.';
        break;
      case Permission.notification:
        title = 'إذن الإشعارات';
        content =
            'نحتاج إذن الإشعارات لإعلامك بنتائج مسح أكواد QR والتحديثات المهمة.';
        break;
      default:
        return true; // لا يوجد تبرير خاص مطلوب
    }

    // التأكد من أن context لا يزال صالحاً قبل عرض الـ Dialog
    if (!context.mounted) return false;

    return await showDialog<bool>(
          context: context,
          barrierDismissible: false, // يجب على المستخدم اتخاذ قرار
          builder:
              (BuildContext dialogContext) => AlertDialog(
                title: Text(title),
                content: Text(content),
                actions: <Widget>[
                  TextButton(
                    child: const Text('لاحقاً'),
                    onPressed:
                        () => Navigator.of(
                          dialogContext,
                        ).pop(false), // المستخدم يرفض الآن
                  ),
                  TextButton(
                    child: const Text('السماح'),
                    onPressed:
                        () => Navigator.of(
                          dialogContext,
                        ).pop(true), // المستخدم يوافق على المتابعة
                  ),
                ],
              ),
        ) ??
        false; // إذا أغلق الحوار بطريقة أخرى، اعتبره رفضاً
  }

  // يعرض حوار يقترح على المستخدم فتح إعدادات التطبيق لتغيير الإذن
  void _showAppSettingsDialog(BuildContext context, Permission permission) {
    if (!context.mounted) return;
    showDialog(
      context: context,
      builder:
          (BuildContext context) => AlertDialog(
            title: Text('الإذن مرفوض نهائياً'),
            content: Text(
              'لقد رفضت إذن ${permission.toString().split('.').last} بشكل دائم. يرجى التوجه إلى إعدادات التطبيق لتفعيله يدوياً إذا أردت استخدام هذه الميزة.',
            ),
            actions: <Widget>[
              TextButton(
                child: const Text('إلغاء'),
                onPressed: () => Navigator.of(context).pop(),
              ),
              TextButton(
                child: const Text('فتح الإعدادات'),
                onPressed: () {
                  openAppSettings(); // تفتح إعدادات التطبيق
                  Navigator.of(context).pop();
                },
              ),
            ],
          ),
    );
  }
}