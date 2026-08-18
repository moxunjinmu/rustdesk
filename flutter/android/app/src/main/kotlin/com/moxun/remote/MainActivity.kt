package com.moxun.remote

/**
 * Handle events from flutter
 * Request MediaProjection permission
 *
 * Inspired by [droidVNC-NG] https://github.com/bk138/droidVNC-NG
 */

import ffi.FFI

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.DisplayMetrics
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import com.hjq.permissions.XXPermissions
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlin.concurrent.thread


class MainActivity : FlutterActivity() {
    companion object {
        var flutterMethodChannel: MethodChannel? = null
        private var _rdClipboardManager: RdClipboardManager? = null
        val rdClipboardManager: RdClipboardManager?
            get() = _rdClipboardManager;
    }

    private val channelTag = "mChannel"
    private val logTag = "mMainActivity"
    private var mainService: MainService? = null

    private var isAudioStart = false
    private val audioRecordHandle = AudioRecordHandle(this, { false }, { isAudioStart })

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        if (MainService.isReady) {
            Intent(activity, MainService::class.java).also {
                bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }
        flutterMethodChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            channelTag
        )
        initFlutterChannel(flutterMethodChannel!!)
        thread {
            try {
                setCodecInfo()
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to setCodecInfo: ${e.message}", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val inputPer = InputService.isOpen
        activity.runOnUiThread {
            flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "input", "value" to inputPer.toString())
            )
        }
    }

    override fun onFlutterSurfaceViewCreated(flutterSurfaceView: io.flutter.embedding.android.FlutterSurfaceView) {
        super.onFlutterSurfaceViewCreated(flutterSurfaceView)
        setupMouseWheelForwarding(flutterSurfaceView)
        setupWheelSimDetection(flutterSurfaceView)
    }

    override fun onFlutterTextureViewCreated(flutterTextureView: io.flutter.embedding.android.FlutterTextureView) {
        super.onFlutterTextureViewCreated(flutterTextureView)
        setupMouseWheelForwarding(flutterTextureView)
        setupWheelSimDetection(flutterTextureView)
    }

    /**
     * The Flutter engine drops ACTION_SCROLL for some mouse devices (notably
     * bluetooth mice whose InputDevice.isExternal() reports false, and
     * HarmonyOS pads which may report non-standard source flags), so the
     * wheel never reaches Flutter as a PointerScrollEvent. Capture every
     * ACTION_SCROLL natively here and forward the delta to Flutter via the
     * 'mChannel' platform channel, which sends the "wheel" message to the
     * remote peer.
     *
     * The listener is attached to the FlutterView's child (FlutterSurfaceView
     * or FlutterTextureView): ViewGroup dispatch is child-first, so this
     * listener always runs before the engine's onGenericMotionEvent. Returning
     * true consumes the event, preventing double-send on devices where the
     * engine would also produce a PointerScrollEvent.
     */
    private fun setupMouseWheelForwarding(view: android.view.View) {
        view.setOnGenericMotionListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_SCROLL -> {
                    Log.d(logTag, "native ACTION_SCROLL src=0x${Integer.toHexString(event.source)}")
                    forwardWheel(event)
                    true
                }
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_EXIT -> {
                    // Diagnostic: proves the device's generic motions reach
                    // the app layer at all (HarmonyOS may swallow them).
                    Log.d(logTag, "native hover ${if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER) "enter" else "exit"} src=0x${Integer.toHexString(event.source)}")
                    false
                }
                else -> false
            }
        }
    }

    /**
     * Activity-level fallback: events that reached the window but were not
     * consumed by the FlutterView bubble up to the activity after the view
     * tree; forward those too. Events consumed by the FlutterView listener
     * never get here, so there is no double-send.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            forwardWheel(event)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /**
     * HarmonyOS never emits ACTION_SCROLL for bluetooth mouse wheels.
     * Instead its MouseWheelSynthesizer (running in our process, see the
     * "create first down/last up event" logs) turns each wheel notch into a
     * fast single-finger drag of ~160dp on the app window - which the remote
     * UI reads as a canvas drag, not a scroll. Detect those drags: a very
     * fast, straight, single-pointer sequence (<200ms, >100dp, >1000dp/s)
     * is treated as a wheel notch; we consume it and forward the delta as a
     * wheel message. Real finger drags are slower / less straight and pass
     * through untouched.
     */
    private var wheelSimDownX = 0f
    private var wheelSimDownY = 0f
    private var wheelSimDownT = 0L
    private var wheelSimLastX = 0f
    private var wheelSimLastY = 0f
    private var wheelSimLastT = 0L
    private var wheelSimActive = false
    private var wheelSimConsume = false

    private fun setupWheelSimDetection(view: android.view.View) {
        view.setOnTouchListener { _, event ->
            if (event.pointerCount > 1) {
                wheelSimConsume = false
                wheelSimActive = false
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wheelSimDownX = event.x
                    wheelSimDownY = event.y
                    wheelSimDownT = event.eventTime
                    wheelSimLastX = event.x
                    wheelSimLastY = event.y
                    wheelSimLastT = event.eventTime
                    wheelSimActive = true
                    wheelSimConsume = false
                    Log.d(logTag, "touch down x=${event.x} y=${event.y}")
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!wheelSimActive) return@setOnTouchListener false
                    wheelSimLastX = event.x
                    wheelSimLastY = event.y
                    wheelSimLastT = event.eventTime
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!wheelSimActive) return@setOnTouchListener false
                    wheelSimActive = false
                    val dt = wheelSimLastT - wheelSimDownT
                    val dx = wheelSimLastX - wheelSimDownX
                    val dy = wheelSimLastY - wheelSimDownY
                    val dist = Math.hypot(dx.toDouble(), dy.toDouble())
                    val speed = if (dt > 0) dist * 1000.0 / dt.toDouble() else 0.0
                    val straight = maxOf(Math.abs(dx), Math.abs(dy)) > 0.85 * dist
                    Log.d(logTag, "touch up dx=$dx dy=$dy dt=${dt}ms dist=$dist speed=$speed straight=$straight")
                    if (dt in 1..200 && dist > 100 && speed > 1000.0 && straight) {
                        Log.d(logTag, "wheel-sim detected, forwarding as wheel dx=$dx dy=$dy")
                        sendWheelDelta(dx.toDouble(), dy.toDouble())
                        wheelSimConsume = true
                    } else {
                        wheelSimConsume = false
                    }
                }
            }
            wheelSimConsume
        }
    }

    private fun sendWheelDelta(dx: Double, dy: Double) {
        if (dx != 0.0 || dy != 0.0) {
            flutterMethodChannel?.invokeMethod(
                "mouse_wheel",
                mapOf("dx" to dx, "dy" to dy)
            )
        }
    }

    private fun forwardWheel(event: MotionEvent) {
        val density = resources.displayMetrics.density
        var dx = -event.getAxisValue(MotionEvent.AXIS_HSCROLL) * density
        var dy = -event.getAxisValue(MotionEvent.AXIS_VSCROLL) * density
        if (dx == 0f && dy == 0f) {
            // Some devices report the wheel via AXIS_WHEEL instead.
            dy = -event.getAxisValue(MotionEvent.AXIS_WHEEL) * density
        }
        Log.d(logTag, "forward wheel dx=$dx dy=$dy src=0x${Integer.toHexString(event.source)}")
        sendWheelDelta(dx.toDouble(), dy.toDouble())
    }

    private fun requestMediaProjection() {
        val intent = Intent(this, PermissionRequestTransparentActivity::class.java).apply {
            action = ACT_REQUEST_MEDIA_PROJECTION
        }
        startActivityForResult(intent, REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION && resultCode == RES_FAILED) {
            flutterMethodChannel?.invokeMethod("on_media_projection_canceled", null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (_rdClipboardManager == null) {
            _rdClipboardManager = RdClipboardManager(getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            FFI.setClipboardManager(_rdClipboardManager!!)
        }
    }

    override fun onDestroy() {
        Log.e(logTag, "onDestroy")
        mainService?.let {
            unbindService(serviceConnection)
        }
        super.onDestroy()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(logTag, "onServiceConnected")
            val binder = service as MainService.LocalBinder
            mainService = binder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(logTag, "onServiceDisconnected")
            mainService = null
        }
    }

    private fun initFlutterChannel(flutterMethodChannel: MethodChannel) {
        flutterMethodChannel.setMethodCallHandler { call, result ->
            // make sure result will be invoked, otherwise flutter will await forever
            when (call.method) {
                "init_service" -> {
                    Intent(activity, MainService::class.java).also {
                        bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
                    }
                    if (MainService.isReady) {
                        result.success(false)
                        return@setMethodCallHandler
                    }
                    requestMediaProjection()
                    result.success(true)
                }
                "start_capture" -> {
                    mainService?.let {
                        result.success(it.startCapture())
                    } ?: let {
                        result.success(false)
                    }
                }
                "stop_service" -> {
                    Log.d(logTag, "Stop service")
                    mainService?.let {
                        it.destroy()
                        result.success(true)
                    } ?: let {
                        result.success(false)
                    }
                }
                "check_permission" -> {
                    if (call.arguments is String) {
                        result.success(XXPermissions.isGranted(context, call.arguments as String))
                    } else {
                        result.success(false)
                    }
                }
                "request_permission" -> {
                    if (call.arguments is String) {
                        requestPermission(context, call.arguments as String)
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                START_ACTION -> {
                    if (call.arguments is String) {
                        startAction(context, call.arguments as String)
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                "check_video_permission" -> {
                    mainService?.let {
                        result.success(it.checkMediaPermission())
                    } ?: let {
                        result.success(false)
                    }
                }
                "check_service" -> {
                    Companion.flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "input", "value" to InputService.isOpen.toString())
                    )
                    Companion.flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "media", "value" to MainService.isReady.toString())
                    )
                    result.success(true)
                }
                "stop_input" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        InputService.ctx?.disableSelf()
                    } else {
                        InputService.ctx = null
                        Companion.flutterMethodChannel?.invokeMethod(
                            "on_state_changed",
                            mapOf("name" to "input", "value" to InputService.isOpen.toString())
                        )
                    }
                    result.success(true)
                }
                "cancel_notification" -> {
                    if (call.arguments is Int) {
                        val id = call.arguments as Int
                        mainService?.cancelNotification(id)
                    } else {
                        result.success(true)
                    }
                }
                "enable_soft_keyboard" -> {
                    // https://blog.csdn.net/hanye2020/article/details/105553780
                    if (call.arguments as Boolean) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    } else {
                        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    }
                    result.success(true)

                }
                "try_sync_clipboard" -> {
                    rdClipboardManager?.syncClipboard(true)
                    result.success(true)
                }
                GET_START_ON_BOOT_OPT -> {
                    val prefs = getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE)
                    result.success(prefs.getBoolean(KEY_START_ON_BOOT_OPT, false))
                }
                SET_START_ON_BOOT_OPT -> {
                    if (call.arguments is Boolean) {
                        val prefs = getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE)
                        val edit = prefs.edit()
                        edit.putBoolean(KEY_START_ON_BOOT_OPT, call.arguments as Boolean)
                        edit.apply()
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                SYNC_APP_DIR_CONFIG_PATH -> {
                    if (call.arguments is String) {
                        val prefs = getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE)
                        val edit = prefs.edit()
                        edit.putString(KEY_APP_DIR_CONFIG_PATH, call.arguments as String)
                        edit.apply()
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                GET_VALUE -> {
                    if (call.arguments is String) {
                        if (call.arguments == KEY_IS_SUPPORT_VOICE_CALL) {
                            result.success(isSupportVoiceCall())
                        } else {
                            result.error("-1", "No such key", null)
                        }
                    } else {
                        result.success(null)
                    }
                }
                "on_voice_call_started" -> {
                    onVoiceCallStarted()
                }
                "on_voice_call_closed" -> {
                    onVoiceCallClosed()
                }
                else -> {
                    result.error("-1", "No such method", null)
                }
            }
        }
    }

    private fun setCodecInfo() {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val codecs = codecList.codecInfos
        val codecArray = JSONArray()

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val wh = getScreenSize(windowManager)
        var w = wh.first
        var h = wh.second
        val align = 64
        w = (w + align - 1) / align * align
        h = (h + align - 1) / align * align
        codecs.forEach { codec ->
            val codecObject = JSONObject()
            codecObject.put("name", codec.name)
            codecObject.put("is_encoder", codec.isEncoder)
            var hw: Boolean? = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                hw = codec.isHardwareAccelerated
            } else {
                // https://chromium.googlesource.com/external/webrtc/+/HEAD/sdk/android/src/java/org/webrtc/MediaCodecUtils.java#29
                // https://chromium.googlesource.com/external/webrtc/+/master/sdk/android/api/org/webrtc/HardwareVideoEncoderFactory.java#229
                if (listOf("OMX.google.", "OMX.SEC.", "c2.android").any { codec.name.startsWith(it, true) }) {
                    hw = false
                } else if (listOf("c2.qti", "OMX.qcom.video", "OMX.Exynos", "OMX.hisi", "OMX.MTK", "OMX.Intel", "OMX.Nvidia").any { codec.name.startsWith(it, true) }) {
                    hw = true
                }
            }
            if (hw != true) {
                return@forEach
            }
            codecObject.put("hw", hw)
            var mime_type = ""
            codec.supportedTypes.forEach { type ->
                if (listOf("video/avc", "video/hevc").contains(type)) { // "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "video/av01"
                    mime_type = type;
                }
            }
            if (mime_type.isNotEmpty()) {
                codecObject.put("mime_type", mime_type)
                val caps = codec.getCapabilitiesForType(mime_type)
                if (codec.isEncoder) {
                    // Encoder's max_height and max_width are interchangeable
                    if (!caps.videoCapabilities.isSizeSupported(w,h) && !caps.videoCapabilities.isSizeSupported(h,w)) {
                        return@forEach
                    }
                }
                codecObject.put("min_width", caps.videoCapabilities.supportedWidths.lower)
                codecObject.put("max_width", caps.videoCapabilities.supportedWidths.upper)
                codecObject.put("min_height", caps.videoCapabilities.supportedHeights.lower)
                codecObject.put("max_height", caps.videoCapabilities.supportedHeights.upper)
                val surface = caps.colorFormats.contains(COLOR_FormatSurface);
                codecObject.put("surface", surface)
                val nv12 = caps.colorFormats.contains(COLOR_FormatYUV420SemiPlanar)
                codecObject.put("nv12", nv12)
                if (!(nv12 || surface)) {
                    return@forEach
                }
                codecObject.put("min_bitrate", caps.videoCapabilities.bitrateRange.lower / 1000)
                codecObject.put("max_bitrate", caps.videoCapabilities.bitrateRange.upper / 1000)
                if (!codec.isEncoder) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        codecObject.put("low_latency", caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
                    }
                }
                if (!codec.isEncoder) {
                    return@forEach
                }
                codecArray.put(codecObject)
            }
        }
        val result = JSONObject()
        result.put("version", Build.VERSION.SDK_INT)
        result.put("w", w)
        result.put("h", h)
        result.put("codecs", codecArray)
        FFI.setCodecInfo(result.toString())
    }

    private fun onVoiceCallStarted() {
        var ok = false
        mainService?.let {
            ok = it.onVoiceCallStarted()
        } ?: let {
            isAudioStart = true
            ok = audioRecordHandle.onVoiceCallStarted(null)
        }
        if (!ok) {
            // Rarely happens, So we just add log and msgbox here.
            Log.e(logTag, "onVoiceCallStarted fail")
            flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                "type" to "custom-nook-nocancel-hasclose-error",
                "title" to "Voice call",
                "text" to "Failed to start voice call."))
        } else {
            Log.d(logTag, "onVoiceCallStarted success")
        }
    }

    private fun onVoiceCallClosed() {
        var ok = false
        mainService?.let {
            ok = it.onVoiceCallClosed()
        } ?: let {
            isAudioStart = false
            ok = audioRecordHandle.onVoiceCallClosed(null)
        }
        if (!ok) {
            // Rarely happens, So we just add log and msgbox here.
            Log.e(logTag, "onVoiceCallClosed fail")
            flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                "type" to "custom-nook-nocancel-hasclose-error",
                "title" to "Voice call",
                "text" to "Failed to stop voice call."))
        } else {
            Log.d(logTag, "onVoiceCallClosed success")
        }
    }

    override fun onStop() {
        super.onStop()
        val disableFloatingWindow = FFI.getLocalOption("disable-floating-window") == "Y"
        if (!disableFloatingWindow && MainService.isReady) {
            startService(Intent(this, FloatingWindowService::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        stopService(Intent(this, FloatingWindowService::class.java))
    }
}
