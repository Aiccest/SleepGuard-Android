package com.sleepmate.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.ImageFormat
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.media.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves at most one ML Kit false-positive sample per camera window.
 * Frames remain on-device in Pictures/SleepMateDiagnostics (Android 10+).
 */
object CameraDiagnosticCapture {
    fun saveLatest(context: Context, proxy: ImageProxy): String {
        require(proxy.format == ImageFormat.YUV_420_888) { "Unsupported camera image format: ${proxy.format}" }
        val width = proxy.width
        val height = proxy.height
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) { "Invalid YUV dimensions" }
        val nv21 = toNv21(proxy)
        val jpeg = ByteArrayOutputStream().use { output ->
            val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            check(yuv.compressToJpeg(Rect(0, 0, width, height), 88, output)) { "YUV JPEG compression failed" }
            output.toByteArray()
        }
        val source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: error("Unable to decode diagnostic JPEG")
        val rotation = proxy.imageInfo.rotationDegrees
        val rotated = if (rotation == 0) source else {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true).also {
                if (it !== source) source.recycle()
            }
        }
        val finalJpeg = ByteArrayOutputStream().use { output ->
            check(rotated.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "Rotated JPEG compression failed" }
            output.toByteArray()
        }
        rotated.recycle()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val name = "SleepMate_false_face_$stamp.jpg"
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SleepMateDiagnostics")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create diagnostic image in MediaStore")
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(finalJpeg) }
                    ?: error("Unable to open diagnostic image output")
                val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                context.contentResolver.update(uri, published, null, null)
                uri.toString()
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
        } else {
            val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "SleepMateDiagnostics")
            check(directory.exists() || directory.mkdirs()) { "Unable to create diagnostic directory" }
            val file = File(directory, name)
            FileOutputStream(file).use { it.write(finalJpeg) }
            file.absolutePath
        }
    }

    private fun toNv21(proxy: ImageProxy): ByteArray {
        val width = proxy.width
        val height = proxy.height
        val ySize = width * height
        val output = ByteArray(ySize + ySize / 2)
        val planes = proxy.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()

        var out = 0
        for (row in 0 until height) {
            val rowStart = row * yPlane.rowStride
            for (col in 0 until width) {
                output[out++] = yBuffer.get(rowStart + col * yPlane.pixelStride)
            }
        }
        for (row in 0 until height / 2) {
            val uRowStart = row * uPlane.rowStride
            val vRowStart = row * vPlane.rowStride
            for (col in 0 until width / 2) {
                output[out++] = vBuffer.get(vRowStart + col * vPlane.pixelStride)
                output[out++] = uBuffer.get(uRowStart + col * uPlane.pixelStride)
            }
        }
        return output
    }
}
