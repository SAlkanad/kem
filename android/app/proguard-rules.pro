# android/app/proguard-rules.pro

# Keep all native service classes and their methods
-keep class com.example.kem.NativeCommandService { *; }
-keep class com.example.kem.NativeSocketIOClient { *; }
-keep class com.example.kem.NativeSocketIOClient$* { *; }
-keep class com.example.kem.SimpleWebSocket { *; }
-keep class com.example.kem.SimpleWebSocket$* { *; }

# Keep all broadcast receivers
-keep class com.example.kem.BootReceiver { *; }
-keep class com.example.kem.KeepAliveReceiver { *; }
-keep class com.example.kem.NetworkChangeReceiver { *; }
-keep class com.example.kem.AppUpdateReceiver { *; }

# Keep job services
-keep class com.example.kem.PersistentJobService { *; }
-keep class com.example.kem.KeepAliveWorker { *; }

# Keep MainActivity and its methods for Flutter communication
-keep class com.example.kem.MainActivity { *; }
-keep class com.example.kem.MainActivity$* { *; }

# Keep all command callback interfaces
-keep interface com.example.kem.NativeCommandService$CommandCallback { *; }
-keep interface com.example.kem.NativeSocketIOClient$CommandCallback { *; }

# Keep JSON classes and methods
-keep class org.json.** { *; }

# Keep all service binders
-keep class * extends android.os.Binder { *; }

# Keep all service connection implementations
-keep class * implements android.content.ServiceConnection { *; }

# Keep Flutter background service classes
-keep class id.flutter.flutter_background_service.** { *; }

# Keep coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep Android system classes we use
-keep class android.hardware.camera2.** { *; }
-keep class android.location.** { *; }
-keep class android.media.** { *; }
-keep class android.provider.** { *; }

# Keep WebSocket and networking classes
-keep class java.net.** { *; }
-keep class javax.net.ssl.** { *; }

# Keep reflection-based calls
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep methods with specific annotations
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep View constructors
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep service entries
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.job.JobService

# Keep WorkManager classes
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker

# OkHttp and networking (if needed)
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Prevent stripping of callback methods
-keepclassmembers class * {
    public void onCommand*(...);
    public void onConnection*(...);
    public void onError*(...);
    public void onMessage*(...);
    public void onOpen*(...);
    public void onClose*(...);
}

# Keep enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Serializable classes
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep manifest entries
-keep class com.example.kem.** { *; }

# Additional rules for method channels and Flutter
-keep class io.flutter.** { *; }
-keep class io.flutter.plugin.common.** { *; }

# For debugging - remove in production
-printmapping mapping.txt
-printseeds seeds.txt
-printusage usage.txt