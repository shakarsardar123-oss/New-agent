package com.aura.aura_assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Surface
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/// Android-native implementation of the AURA screen-capture subsystem.
///
/// Uses [MediaProjection] + [VirtualDisplay] + [ImageReader] to capture
/// screen frames and delivers them via an [EventChannel] as raw byte
/// arrays. The Flutter side ([ScreenCaptureMethodChannel]) decodes
/// those bytes into [CapturedFrame] objects.
///
/// ### Lifecycle
/// 1. Flutter calls `requestProjection` → plugin launches the
///    system MediaProjection permission intent.
/// 2. User grants permission → `onActivityResult` receives the
///    result code + intent.
/// 3. Flutter calls `startCapture` → plugin creates a VirtualDisplay
///    + ImageReader and starts pushing frames on the EventChannel.
/// 4. Flutter calls `stopCapture` → plugin tears down VirtualDisplay
///    + ImageReader, releases MediaProjection.
///
/// ### Thread safety
/// All public methods are called on the Flutter main thread.
/// Frame processing happens on the ImageReader's handler thread.
class ScreenCapturePlugin(
    private val context: Context,
    private val methodChannelName: String = METHOD_CHANNEL_NAME,
    private val eventChannelName: String = EVENT_CHANNEL_NAME,
) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    // ── Channel names (mirror Dart constants) ────────────────────
    companion object {
        const val METHOD_CHANNEL_NAME = "com.aura.aura_assistant/screen_capture"
        const val EVENT_CHANNEL_NAME = "com.aura.aura_assistant/screen_capture_frames"

        private const val REQUEST_MEDIA_PROJECTION = 1001
    }

    // ── Method names (mirror Dart ScreenCaptureMethodNames) ──────
    private object Methods {
        const val REQUEST_PROJECTION = "requestProjection"
        const val START_CAPTURE = "startCapture"
        const val STOP_CAPTURE = "stopCapture"
        const val CAPTURE_SINGLE_FRAME = "captureSingleFrame"
        const val IS_SUPPORTED = "isSupported"
    }

    // ── MediaProjection state ─────────────────────────────────────
    private var projectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionResultCode: Int = 0
    private var projectionResultIntent: Intent? = null

    // ── Capture state ─────────────────────────────────────────────
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureHandler: Handler? = null
    private var captureThread: android.os.HandlerThread? = null

    // ── Foreground service state ──────────────────────────────────
    private var foregroundServiceRunning: Boolean = false

    // ── Frame delivery ────────────────────────────────────────────
    private var eventSink: EventChannel.EventSink? = null
    private var lastFrameTimestamp: Long = 0L
    private var minFrameIntervalMs: Long = 100L  // default ~10 fps

    // ── Configuration from Flutter ─────────────────────────────────
    private var captureWidth: Int = 720
    private var captureHeight: Int = 1280
    private var captureDpi: Int = 160
    private var pixelFormatStr: String = "rgba"

    // ── Registration ──────────────────────────────────────────────

    /// Registers this plugin with the [FlutterEngine].
    /// Call from [MainActivity.configureFlutterEngine].
    fun registerWith(flutterEngine: FlutterEngine) {
        val messenger = flutterEngine.dartExecutor.binaryMessenger

        MethodChannel(messenger, methodChannelName)
            .setMethodCallHandler(this)

        EventChannel(messenger, eventChannelName)
            .setStreamHandler(this)

        projectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
    }

    // ── MethodCallHandler ─────────────────────────────────────────

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            Methods.REQUEST_PROJECTION -> handleRequestProjection(result)
            Methods.START_CAPTURE -> handleStartCapture(call, result)
            Methods.STOP_CAPTURE -> handleStopCapture(result)
            Methods.CAPTURE_SINGLE_FRAME -> handleCaptureSingleFrame(result)
            Methods.IS_SUPPORTED -> handleIsSupported(result)
            else -> result.notImplemented()
        }
    }

    // ── requestProjection ─────────────────────────────────────────
    //
    // Launches the system MediaProjection permission intent.
    // The result comes back via onActivityResult in the Activity.

    private fun handleRequestProjection(result: MethodChannel.Result) {
        val pm = projectionManager
        if (pm == null) {
            result.error(
                "PROJECTION_NOT_AVAILABLE",
                "MediaProjectionManager is not available on this device",
                null,
            )
            return
        }

        // We need an Activity to launch the intent.
        val activity = context as? android.app.Activity
        if (activity == null) {
            result.error(
                "NO_ACTIVITY",
                "An Activity is required to request MediaProjection",
                null,
            )
            return
        }

        // Store the result callback to invoke after onActivityResult.
        pendingRequestResult = result

        val intent = pm.createScreenCaptureIntent()
        activity.startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
        // Do NOT call result.success/failure here — we defer to onActivityResult.
    }

    // Temporarily stores the MethodChannel.Result for requestProjection.
    private var pendingRequestResult: MethodChannel.Result? = null

    /// Call this from [Activity.onActivityResult] to deliver the
    /// MediaProjection permission result to Flutter.
    ///
    /// Returns `true` if the result was consumed by this plugin.
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_MEDIA_PROJECTION) return false

        val pending = pendingRequestResult
        pendingRequestResult = null

        if (pending == null) return true  // already handled / timed out

        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            projectionResultCode = resultCode
            projectionResultIntent = data
            pending.success(mapOf(
                "granted" to true,
                "resultCode" to resultCode,
            ))
        } else {
            projectionResultCode = 0
            projectionResultIntent = null
            pending.success(mapOf(
                "granted" to false,
                "resultCode" to resultCode,
            ))
        }
        return true
    }

    // ── startCapture ──────────────────────────────────────────────

    @SuppressLint("NewApi")
    private fun handleStartCapture(call: MethodCall, result: MethodChannel.Result) {
        // Parse config from Flutter.
        val args = call.arguments as? Map<*, *>
        if (args != null) {
            captureWidth = (args["width"] as? Int) ?: 720
            captureHeight = (args["height"] as? Int) ?: 1280
            captureDpi = (args["dpi"] as? Int) ?: 160
            pixelFormatStr = (args["pixelFormat"] as? String) ?: "rgba"
            minFrameIntervalMs = (args["minFrameIntervalMs"] as? Int)?.toLong() ?: 100L
        }

        // Validate projection permission.
        if (projectionResultIntent == null) {
            result.error(
                "NO_PROJECTION_PERMISSION",
                "requestProjection must be called and granted before startCapture",
                null,
            )
            return
        }

        val pm = projectionManager
        if (pm == null) {
            result.error(
                "PROJECTION_NOT_AVAILABLE",
                "MediaProjectionManager is not available",
                null,
            )
            return
        }

        // Tear down any existing capture session.
        tearDownCapture()

        // On Android 14+ (API 34), a foreground service with type
        // mediaProjection MUST be running before getMediaProjection().
        val fgStarted = startForegroundService()
        if (Build.VERSION.SDK_INT >= 34 && !fgStarted) {
            result.error(
                "FOREGROUND_SERVICE_FAILED",
                "Failed to start foreground service required for MediaProjection on API 34+",
                null,
            )
            return
        }

        // Obtain MediaProjection.
        mediaProjection = pm.getMediaProjection(projectionResultCode, projectionResultIntent!!)

        if (mediaProjection == null) {
            result.error(
                "PROJECTION_FAILED",
                "Failed to acquire MediaProjection",
                null,
            )
            return
        }

        // Register a callback so we know when the projection is stopped
        // (e.g. user revoked permission via system settings).
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                tearDownCapture()
                eventSink?.error(
                    "PROJECTION_STOPPED",
                    "MediaProjection was stopped (user revoked or system killed)",
                    null,
                )
            }
        }, Handler(Looper.getMainLooper()))

        // Create a handler thread for the ImageReader.
        captureThread = android.os.HandlerThread("ScreenCaptureThread").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        // Determine pixel format for ImageReader.
        // ImageReader only supports PixelFormat.RGBA_8888 and
        // ImageFormat.JPEG on most devices. We use RGBA_8888.
        val imageReaderFormat = PixelFormat.RGBA_8888

        imageReader = ImageReader.newInstance(
            captureWidth,
            captureHeight,
            imageReaderFormat,
            2,  // maxImages: double-buffer
        )

        // Set up the frame listener.
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastFrameTimestamp < minFrameIntervalMs) {
                    return@setOnImageAvailableListener  // throttle
                }
                lastFrameTimestamp = now

                val frameBytes = imageToBytes(image)
                val frameMap = mapOf(
                    "width" to image.width,
                    "height" to image.height,
                    "bytes" to frameBytes,
                    "timestamp" to now,
                    "rotation" to 0,
                    "pixelFormat" to pixelFormatStr,
                )

                // Deliver on main thread so EventChannel is safe.
                Handler(Looper.getMainLooper()).post {
                    eventSink?.success(frameMap)
                }
            } finally {
                image.close()
            }
        }, captureHandler)

        // Create VirtualDisplay.
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AURA Screen Capture",
            captureWidth,
            captureHeight,
            captureDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler,
        )

        result.success(mapOf(
            "status" to "active",
            "width" to captureWidth,
            "height" to captureHeight,
            "pixelFormat" to pixelFormatStr,
        ))
    }

    // ── stopCapture ───────────────────────────────────────────────

    private fun handleStopCapture(result: MethodChannel.Result) {
        tearDownCapture()
        result.success(mapOf(
            "status" to "idle",
        ))
    }

    // ── captureSingleFrame ────────────────────────────────────────

    private fun handleCaptureSingleFrame(result: MethodChannel.Result) {
        if (imageReader == null || virtualDisplay == null) {
            result.error(
                "NOT_CAPTURING",
                "No active capture session. Call startCapture first.",
                null,
            )
            return
        }

        // The ImageReader listener is already running. We read the
        // latest available image directly.
        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            result.error(
                "NO_FRAME_AVAILABLE",
                "No frame is currently available from the ImageReader",
                null,
            )
            return
        }

        try {
            val now = System.currentTimeMillis()
            val frameBytes = imageToBytes(image)
            result.success(mapOf(
                "width" to image.width,
                "height" to image.height,
                "bytes" to frameBytes,
                "timestamp" to now,
                "rotation" to 0,
                "pixelFormat" to pixelFormatStr,
            ))
        } finally {
            image.close()
        }
    }

    // ── isSupported ───────────────────────────────────────────────

    private fun handleIsSupported(result: MethodChannel.Result) {
        // MediaProjection requires API 21+ (Android 5.0).
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                && projectionManager != null
        result.success(supported)
    }

    // ── EventChannel.StreamHandler ────────────────────────────────

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    // ── Internal helpers ──────────────────────────────────────────

    /// Converts an [Image] (RGBA_8888) to a byte array.
    ///
    /// ImageReader with RGBA_8888 produces a single [Image.Plane]
    /// whose row stride may include padding. We copy row-by-row
    /// to produce a tightly-packed RGBA buffer.
    private fun imageToBytes(image: Image): ByteArray {
        val planes = image.planes
        if (planes.isEmpty()) {
            return ByteArray(0)
        }

        val plane = planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height

        val buffer = plane.buffer
        val remaining = buffer.remaining()

        // If the image is tightly packed (no padding), read all at once.
        if (rowStride == width * pixelStride) {
            val bytes = ByteArray(remaining)
            buffer.get(bytes)
            return bytes
        }

        // Otherwise, copy row by row to strip padding.
        val rowLength = width * pixelStride
        val output = ByteArray(rowLength * height)
        var offset = 0
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            buffer.get(output, offset, rowLength)
            offset += rowLength
        }
        return output
    }

    /// Tears down VirtualDisplay, ImageReader, and MediaProjection.
    /// Safe to call multiple times.
    private fun tearDownCapture() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) { }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) { }
        imageReader = null

        try {
            captureThread?.quitSafely()
        } catch (_: Exception) { }
        captureThread = null
        captureHandler = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) { }
        mediaProjection = null

        stopForegroundService()
        lastFrameTimestamp = 0L
    }

    // ── Foreground service for MediaProjection (API 34+) ─────────

    /// Starts [ScreenCaptureService] as a foreground service.
    /// Required on API 34+ before calling [getMediaProjection].
    /// Returns `true` if the service was started (or not needed on <34).
    @SuppressLint("NewApi")
    private fun startForegroundService(): Boolean {
        if (Build.VERSION.SDK_INT < 34) {
            // Not required below Android 14.
            return true
        }
        if (foregroundServiceRunning) return true

        try {
            val intent = Intent(context, ScreenCaptureService::class.java)
            context.startForegroundService(intent)
            foregroundServiceRunning = true
            return true
        } catch (e: Exception) {
            foregroundServiceRunning = false
            return false
        }
    }

    /// Stops [ScreenCaptureService] if it is running.
    private fun stopForegroundService() {
        if (!foregroundServiceRunning) return
        try {
            val intent = Intent(context, ScreenCaptureService::class.java)
            context.stopService(intent)
        } catch (_: Exception) { }
        foregroundServiceRunning = false
    }

    /// Clean up all resources.
    fun dispose() {
        tearDownCapture()
        projectionResultIntent = null
        projectionResultCode = 0
        projectionManager = null
        pendingRequestResult = null
        eventSink = null
    }
}