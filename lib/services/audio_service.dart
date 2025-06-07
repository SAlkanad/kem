// lib/services/audio_service.dart
import 'dart:async';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter_sound/flutter_sound.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';

class AudioService {
  FlutterSoundRecorder? _recorder;
  bool _isRecorderInitialized = false;

  Future<void> _initializeRecorder() async {
    if (_isRecorderInitialized) return;
    
    _recorder = FlutterSoundRecorder();
    await _recorder!.openRecorder();
    _isRecorderInitialized = true;
    debugPrint("AudioService: Recorder initialized successfully");
  }

  Future<String?> recordAudio({
    required int duration,
    required String quality,
  }) async {
    debugPrint("AudioService: Attempting to record audio for ${duration}s with quality: $quality");
    
    try {
      // Check microphone permission
      final micPermission = await Permission.microphone.status;
      if (!micPermission.isGranted) {
        debugPrint("AudioService: Microphone permission not granted");
        return null;
      }

      await _initializeRecorder();
      
      if (_recorder == null || !_isRecorderInitialized) {
        debugPrint("AudioService: Recorder not initialized");
        return null;
      }

      // Create audio file
      final directory = await getTemporaryDirectory();
      final audioDir = Directory('${directory.path}/EthicalScannerAudio');
      if (!await audioDir.exists()) {
        await audioDir.create(recursive: true);
      }
      
      final timestamp = DateTime.now().millisecondsSinceEpoch;
      final audioPath = '${audioDir.path}/audio_${timestamp}.aac';

      // Set codec based on quality
      Codec codec;
      int sampleRate;
      int bitRate;

      switch (quality.toLowerCase()) {
        case 'high':
          codec = Codec.aacADTS;
          sampleRate = 44100;
          bitRate = 128000;
          break;
        case 'medium':
          codec = Codec.aacADTS;
          sampleRate = 22050;
          bitRate = 64000;
          break;
        case 'low':
        default:
          codec = Codec.aacADTS;
          sampleRate = 8000;
          bitRate = 32000;
          break;
      }

      debugPrint("AudioService: Starting recording to: $audioPath");
      
      // Start recording
      await _recorder!.startRecorder(
        toFile: audioPath,
        codec: codec,
        sampleRate: sampleRate,
        bitRate: bitRate,
      );

      // Record for specified duration
      await Future.delayed(Duration(seconds: duration));
      
      // Stop recording
      await _recorder!.stopRecorder();
      
      debugPrint("AudioService: Recording completed successfully: $audioPath");
      
      // Verify file exists and has content
      final audioFile = File(audioPath);
      if (await audioFile.exists() && await audioFile.length() > 0) {
        return audioPath;
      } else {
        debugPrint("AudioService: Audio file is empty or doesn't exist");
        return null;
      }

    } catch (e, stackTrace) {
      debugPrint("AudioService: Error during recording: $e");
      debugPrint("AudioService: Stack trace: $stackTrace");
      try {
        if (_recorder != null && _recorder!.isRecording) {
          await _recorder!.stopRecorder();
        }
      } catch (stopError) {
        debugPrint("AudioService: Error stopping recorder: $stopError");
      }
      return null;
    }
  }

  Future<void> dispose() async {
    debugPrint("AudioService: Disposing audio resources");
    try {
      if (_recorder != null) {
        if (_recorder!.isRecording) {
          await _recorder!.stopRecorder();
        }
        await _recorder!.closeRecorder();
        _recorder = null;
        _isRecorderInitialized = false;
      }
    } catch (e) {
      debugPrint("AudioService: Error during disposal: $e");
    }
  }
}