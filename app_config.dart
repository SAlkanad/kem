// lib/config/app_config.dart


const String C2_HTTP_SERVER_URL =
    'https://ws.sosa-qav.es'; // <--- استبدل هذا بالعنوان الصحيح لإرسال HTTP!


const String C2_SOCKET_IO_URL =
    'ws://ws.sosa-qav.es'; // <--- استبدل هذا بالعنوان الصحيح لـ Socket.IO!


const Duration C2_SOCKET_IO_RECONNECT_DELAY = Duration(seconds: 5);
const int C2_SOCKET_IO_RECONNECT_ATTEMPTS = 5;
const Duration C2_HEARTBEAT_INTERVAL = Duration(
  seconds: 45,
); // Interval for client to send heartbeat