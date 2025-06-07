// android/app/src/main/kotlin/com/example/kem/BootReceiver.kt
package com.example.kem

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.view.FlutterMain

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                Log.d(TAG, "Boot completed or package replaced, starting background service")
                try {
                    // Initialize Flutter engine for background service
                    FlutterMain.startInitialization(context)
                    FlutterMain.ensureInitializationComplete(context, null)
                    
                    // Start the background service
                    val serviceIntent = Intent(context, id.flutter.flutter_background_service.BackgroundService::class.java)
                    context.startForegroundService(serviceIntent)
                    
                    Log.d(TAG, "Background service start command sent")
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting background service from boot receiver", e)
                }
            }
        }
    }
}