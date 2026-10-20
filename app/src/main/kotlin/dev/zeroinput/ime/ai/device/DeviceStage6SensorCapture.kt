package dev.zeroinput.ime.ai.device

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import dev.zeroinput.ai.api.AiAttachment
import dev.zeroinput.ai.api.DeviceStage6Result
import dev.zeroinput.ai.api.DeviceStage6ResultStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** One-shot, task-approved sensor capture. Bytes remain in memory and are transferred to one provider request. */
internal object DeviceStage6SensorCapture {
    @SuppressLint("MissingPermission")
    fun microphone(context: Context, maxAttachmentBytes: Int, stillValid: () -> Boolean): DeviceStage6Result {
        val rate = 16_000
        val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return unavailable("Microphone service is unavailable")
        val pcm = ByteArray(MAX_AUDIO_BYTES)
        val recorder = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4_096))
        }.getOrNull() ?: return unavailable("Microphone service is unavailable")
        var size = 0
        var wav: ByteArray? = null
        val started = SystemClock.elapsedRealtime()
        return try {
            recorder.startRecording()
            while (size < pcm.size && SystemClock.elapsedRealtime() - started < MAX_CAPTURE_MS) {
                if (!stillValid()) return expired()
                val count = recorder.read(pcm, size, pcm.size - size)
                if (count <= 0) break
                size += count
            }
            if (!stillValid()) return expired()
            if (size == 0) return failed("Microphone returned no audio")
            wav = wav(pcm, size, rate)
            if (wav!!.size > maxAttachmentBytes) {
                return failed("Microphone sample is too large")
            }
            DeviceStage6Result(DeviceStage6ResultStatus.SUCCESS, "One-second microphone sample captured",
                AiAttachment("audio/wav", wav!!.copyOf(), "device-audio.wav"))
        } catch (_: Exception) {
            failed("Microphone capture failed")
        } finally {
            pcm.fill(0)
            wav?.fill(0)
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    @SuppressLint("MissingPermission")
    fun camera(context: Context, maxAttachmentBytes: Int, stillValid: () -> Boolean): DeviceStage6Result {
        val manager = context.getSystemService(CameraManager::class.java) ?: return unavailable("Camera service is unavailable")
        val cameraId = findBackCamera(manager) ?: return unavailable("No camera is available")
        val thread = HandlerThread("zeroinput-stage6-camera").apply { start() }
        val handler = Handler(thread.looper)
        val reader = createReader(manager, cameraId) ?: run {
            thread.quitSafely()
            return unavailable("Camera output is unavailable")
        }
        val completed = CountDownLatch(1)
        val image = TransientCaptureBuffer()
        val failure = AtomicReference<String?>()
        val camera = AtomicReference<CameraDevice?>()
        val session = AtomicReference<CameraCaptureSession?>()
        reader.setOnImageAvailableListener({ source -> readJpeg(source, image, failure, completed) }, handler)
        return try {
            openCamera(manager, cameraId, reader, handler, camera, session, failure, completed, image)
            val deadline = SystemClock.elapsedRealtime() + CAMERA_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline && !completed.await(50, TimeUnit.MILLISECONDS)) {
                if (!stillValid()) return expired()
            }
            if (!stillValid()) return expired()
            val bytes = image.take() ?: return failed(failure.get() ?: "Camera capture failed")
            try {
                if (bytes.size > maxAttachmentBytes) return failed("Camera frame is too large")
                DeviceStage6Result(DeviceStage6ResultStatus.SUCCESS, "One camera frame captured",
                    AiAttachment("image/jpeg", bytes.copyOf(), "device-camera.jpg"))
            } finally {
                bytes.fill(0)
            }
        } catch (_: Exception) {
            failed("Camera capture failed")
        } finally {
            image.close()
            session.getAndSet(null)?.close()
            camera.getAndSet(null)?.close()
            reader.close()
            thread.quitSafely()
        }
    }

    private fun findBackCamera(manager: CameraManager): String? = runCatching {
        manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()
    }.getOrNull()

    private fun createReader(manager: CameraManager, cameraId: String): ImageReader? = runCatching {
        val map = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val size = map?.getOutputSizes(ImageFormat.JPEG).orEmpty()
            .filter { it.width.toLong() * it.height <= MAX_IMAGE_PIXELS }
            .maxByOrNull { it.width.toLong() * it.height } ?: return null
        ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
    }.getOrNull()

    private fun readJpeg(
        reader: ImageReader,
        output: TransientCaptureBuffer,
        failure: AtomicReference<String?>,
        completed: CountDownLatch,
    ) {
        if (output.isClosed) return
        runCatching {
            if (output.isClosed) return@runCatching
            reader.acquireLatestImage()?.use { image ->
                if (output.isClosed) return@use
                val buffer = image.planes.firstOrNull()?.buffer ?: return@use
                if (buffer.remaining() > MAX_IMAGE_BYTES) {
                    output.clear()
                    failure.set("Camera frame is too large")
                } else {
                    val next = ByteArray(buffer.remaining()).also(buffer::get)
                    output.publish(next)
                }
            }
        }.onFailure { failure.set("Camera capture failed") }.also { completed.countDown() }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(
        manager: CameraManager,
        cameraId: String,
        reader: ImageReader,
        handler: Handler,
        camera: AtomicReference<CameraDevice?>,
        session: AtomicReference<CameraCaptureSession?>,
        failure: AtomicReference<String?>,
        completed: CountDownLatch,
        image: TransientCaptureBuffer,
    ) {
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (image.isClosed) {
                    device.close()
                    return
                }
                camera.set(device)
                if (image.isClosed && camera.compareAndSet(device, null)) {
                    device.close()
                    return
                }
                runCatching {
                    device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(value: CameraCaptureSession) {
                            if (image.isClosed) {
                                value.close()
                                completed.countDown()
                                return
                            }
                            session.set(value)
                            if (image.isClosed && session.compareAndSet(value, null)) {
                                value.close()
                                completed.countDown()
                                return
                            }
                            runCatching {
                                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                    addTarget(reader.surface)
                                }.build()
                                value.capture(request, null, handler)
                            }.onFailure { failure.set("Camera capture failed"); completed.countDown() }
                        }

                        override fun onConfigureFailed(value: CameraCaptureSession) {
                            failure.set("Camera capture failed")
                            completed.countDown()
                        }
                    }, handler)
                }.onFailure { failure.set("Camera capture failed"); completed.countDown() }
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close(); failure.set("Camera disconnected"); completed.countDown()
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close(); failure.set("Camera capture failed"); completed.countDown()
            }
        }, handler)
    }

    private fun wav(pcm: ByteArray, length: Int, sampleRate: Int): ByteArray {
        val output = ByteArray(44 + length)
        fun text(offset: Int, value: String) { value.toByteArray(Charsets.US_ASCII).copyInto(output, offset) }
        fun int(offset: Int, value: Int) {
            repeat(4) { output[offset + it] = (value shr (it * 8)).toByte() }
        }
        fun short(offset: Int, value: Int) { output[offset] = value.toByte(); output[offset + 1] = (value shr 8).toByte() }
        text(0, "RIFF"); int(4, 36 + length); text(8, "WAVE"); text(12, "fmt "); int(16, 16)
        short(20, 1); short(22, 1); int(24, sampleRate); int(28, sampleRate * 2)
        short(32, 2); short(34, 16); text(36, "data"); int(40, length); pcm.copyInto(output, 44, 0, length)
        return output
    }

    private fun unavailable(message: String) = DeviceStage6Result(DeviceStage6ResultStatus.UNAVAILABLE, message)
    private fun failed(message: String) = DeviceStage6Result(DeviceStage6ResultStatus.FAILED, message)
    private fun expired() = DeviceStage6Result(DeviceStage6ResultStatus.EXPIRED, "Task grant expired")

    private const val MAX_AUDIO_BYTES = 32_000
    private const val MAX_IMAGE_BYTES = 1_000_000
    private const val MAX_IMAGE_PIXELS = 1_000_000L
    private const val MAX_CAPTURE_MS = 1_000L
    private const val CAMERA_TIMEOUT_MS = 3_000L
}

/** Clears both replaced and late-arriving sensor frames when capture is revoked. */
internal class TransientCaptureBuffer {
    private val closed = AtomicBoolean()
    private val value = AtomicReference<ByteArray?>()

    val isClosed: Boolean get() = closed.get()

    fun publish(bytes: ByteArray) {
        if (closed.get()) {
            bytes.fill(0)
            return
        }
        value.getAndSet(bytes)?.fill(0)
        if (closed.get() && value.compareAndSet(bytes, null)) bytes.fill(0)
    }

    fun take(): ByteArray? {
        if (closed.get()) return null
        val bytes = value.getAndSet(null) ?: return null
        if (closed.get()) {
            bytes.fill(0)
            return null
        }
        return bytes
    }

    fun clear() {
        value.getAndSet(null)?.fill(0)
    }

    fun close() {
        if (closed.compareAndSet(false, true)) value.getAndSet(null)?.fill(0)
    }
}
