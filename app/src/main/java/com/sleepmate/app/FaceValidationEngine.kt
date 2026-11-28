package com.sleepmate.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mlkit.vision.face.Face
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

/**
 * On-device second-stage validation. The 5% floor and 5%-8% strict band are
 * experiment thresholds, not calibrated guarantees for every camera.
 */
internal class FaceValidationEngine(context: Context) : AutoCloseable {
    private val landmarker: FaceLandmarker

    init {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(false)
            .build()
        landmarker = FaceLandmarker.createFromOptions(context.applicationContext, options)
    }

    fun validateFrame(bitmap: Bitmap, rotationDegrees: Int, candidates: List<Face>, frameWidth: Int, frameHeight: Int): Set<Int> {
        if (candidates.isEmpty() || frameWidth <= 0 || frameHeight <= 0) return emptySet()
        val safeBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        val orientedBitmap = if (normalizedRotation == 0) safeBitmap else Bitmap.createBitmap(
            safeBitmap, 0, 0, safeBitmap.width, safeBitmap.height,
            Matrix().apply { postRotate(normalizedRotation.toFloat()) }, true
        )
        val mpImage = BitmapImageBuilder(orientedBitmap).build()
        val result = try {
            landmarker.detect(mpImage)
        } finally {
            mpImage.close()
            if (orientedBitmap !== safeBitmap) orientedBitmap.recycle()
            if (safeBitmap !== bitmap) safeBitmap.recycle()
        }
        val landmarks = result.faceLandmarks().firstOrNull() ?: return emptySet()
        if (landmarks.size < 468) return emptySet()

        // MediaPipe Face Mesh: eye corners 33/133 and 362/263; nose tip 1;
        // inner upper/lower lip 13/14. These are semantic facial landmarks,
        // not arbitrary points selected from the candidate rectangle.
        val eyeA = midpoint(landmarks[33], landmarks[133])
        val eyeB = midpoint(landmarks[362], landmarks[263])
        val nose = landmarks[1]
        val upperLip = landmarks[13]
        val lowerLip = landmarks[14]
        val xs = landmarks.map { it.x() }
        val ys = landmarks.map { it.y() }
        val meshLeft = xs.minOrNull() ?: return emptySet()
        val meshTop = ys.minOrNull() ?: return emptySet()
        val meshRight = xs.maxOrNull() ?: return emptySet()
        val meshBottom = ys.maxOrNull() ?: return emptySet()
        val meshWidth = meshRight - meshLeft
        val meshHeight = meshBottom - meshTop
        if (meshWidth <= 0f || meshHeight <= 0f) return emptySet()

        val eyeY = (eyeA.second + eyeB.second) / 2f
        val eyeLevel = (eyeY - meshTop) / meshHeight
        val noseLevel = (nose.y() - meshTop) / meshHeight
        val mouthLevel = ((upperLip.y() + lowerLip.y()) / 2f - meshTop) / meshHeight
        val eyeSpanRatio = abs(eyeA.first - eyeB.first) / meshWidth

        if (eyeLevel !in 0.12f..0.68f) return emptySet()
        if (noseLevel <= eyeLevel + 0.015f) return emptySet()
        if (mouthLevel <= noseLevel + 0.015f || mouthLevel > 0.98f) return emptySet()
        if (eyeSpanRatio !in 0.055f..0.72f) return emptySet()
        if (abs(eyeA.second - eyeB.second) > meshHeight * 0.24f) return emptySet()

        val meshBox = floatArrayOf(meshLeft, meshTop, meshRight, meshBottom)
        val accepted = linkedSetOf<Int>()
        candidates.forEachIndexed { index, face ->
            val box = face.boundingBox
            if (box.width() <= 0 || box.height() <= 0) return@forEachIndexed
            val faceWidthRatio = box.width().toFloat() / frameWidth
            if (faceWidthRatio < MIN_FACE_WIDTH_RATIO) return@forEachIndexed

            val candidateBox = floatArrayOf(
                box.left.toFloat() / frameWidth,
                box.top.toFloat() / frameHeight,
                box.right.toFloat() / frameWidth,
                box.bottom.toFloat() / frameHeight
            )
            if (intersectionOverUnion(candidateBox, meshBox) < MIN_MESH_BOX_IOU) return@forEachIndexed

            if (faceWidthRatio < NORMAL_FACE_WIDTH_RATIO) {
                if (eyeSpanRatio < STRICT_BAND_MIN_EYE_SPAN_RATIO) return@forEachIndexed
                if (noseLevel - eyeLevel < 0.045f || mouthLevel - noseLevel < 0.045f) return@forEachIndexed
            }
            accepted += index
        }
        return accepted
    }

    private fun midpoint(a: NormalizedLandmark, b: NormalizedLandmark): Pair<Float, Float> =
        ((a.x() + b.x()) / 2f) to ((a.y() + b.y()) / 2f)

    private fun intersectionOverUnion(a: FloatArray, b: FloatArray): Float {
        val left = max(a[0], b[0])
        val top = max(a[1], b[1])
        val right = min(a[2], b[2])
        val bottom = min(a[3], b[3])
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val areaA = max(0f, a[2] - a[0]) * max(0f, a[3] - a[1])
        val areaB = max(0f, b[2] - b[0]) * max(0f, b[3] - b[1])
        val union = areaA + areaB - intersection
        return if (union > 0f) intersection / union else 0f
    }

    override fun close() = landmarker.close()

    companion object {
        private const val MIN_FACE_WIDTH_RATIO = 0.05f
        private const val NORMAL_FACE_WIDTH_RATIO = 0.08f
        private const val STRICT_BAND_MIN_EYE_SPAN_RATIO = 0.12f
        private const val MIN_MESH_BOX_IOU = 0.18f
    }
}
