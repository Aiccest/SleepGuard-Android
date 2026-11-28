package com.sleepmate.app

import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * ML Kit-only second-stage face validation.
 *
 * Uses ML Kit's 16-point eye contours for an EAR-style geometric check.
 * This is a face/eye geometry quality gate, not a presentation-attack or
 * biometric liveness detector. Thresholds require calibration on real devices.
 */
internal class FaceValidationEngine {

    /**
     * Diagnostic-only snapshot. It never participates in acceptance decisions.
     * Coordinates are kept in ML Kit's input-image coordinate space.
     */
    fun diagnosticSnapshot(candidates: List<Face>): String {
        if (candidates.isEmpty()) return "[EAR_TEST] candidates=0"
        return candidates.take(2).mapIndexed { index, face ->
            val left = face.getContour(FaceContour.LEFT_EYE)?.points
            val right = face.getContour(FaceContour.RIGHT_EYE)?.points
            fun describe(points: List<android.graphics.PointF>?): String {
                if (points == null) return "missing"
                val rawEar = if (points.size == EYE_CONTOUR_POINT_COUNT) {
                    val width = distance(points[0], points[8])
                    if (width > MIN_EYE_WIDTH_PX) {
                        val v1 = distance(points[2], points[14])
                        val v2 = distance(points[3], points[13])
                        "%.3f".format(java.util.Locale.US, (v1 + v2) / (2f * width))
                    } else "bad_width"
                } else "n/a"
                val coords = points.mapIndexed { i, point ->
                    "$i:${point.x.toInt()},${point.y.toInt()}"
                }.joinToString("|")
                return "count=${points.size},rawEAR=$rawEar,points=[$coords]"
            }
            val box = face.boundingBox
            "face${index + 1}{box=${box.left},${box.top},${box.right},${box.bottom};left={${describe(left)}};right={${describe(right)}}}"
        }.joinToString(";")
    }
    fun validateFrame(candidates: List<Face>, frameWidth: Int, frameHeight: Int): Set<Int> {
        if (candidates.isEmpty() || frameWidth <= 0 || frameHeight <= 0) return emptySet()

        val accepted = linkedSetOf<Int>()
        candidates.forEachIndexed { index, face ->
            val box = face.boundingBox
            if (box.width() <= 0 || box.height() <= 0) return@forEachIndexed
            val faceWidthRatio = box.width().toFloat() / frameWidth
            if (faceWidthRatio < MIN_FACE_WIDTH_RATIO) return@forEachIndexed

            val boxRatio = box.width().toFloat() / box.height()
            if (boxRatio !in MIN_FACE_BOX_RATIO..MAX_FACE_BOX_RATIO) return@forEachIndexed

            val leftEye = face.getContour(FaceContour.LEFT_EYE)?.points
            val rightEye = face.getContour(FaceContour.RIGHT_EYE)?.points
            if (leftEye == null || rightEye == null) return@forEachIndexed

            val leftEar = calculateEar(leftEye)
            val rightEar = calculateEar(rightEye)
            if (leftEar == null || rightEar == null) return@forEachIndexed
            if (abs(leftEar - rightEar) > MAX_BILATERAL_EAR_DIFFERENCE) return@forEachIndexed

            val leftCenterX = leftEye.map { it.x }.average().toFloat()
            val leftCenterY = leftEye.map { it.y }.average().toFloat()
            val rightCenterX = rightEye.map { it.x }.average().toFloat()
            val rightCenterY = rightEye.map { it.y }.average().toFloat()
            val eyeSpanRatio = abs(leftCenterX - rightCenterX) / box.width().toFloat()
            if (eyeSpanRatio !in MIN_EYE_SPAN_RATIO..MAX_EYE_SPAN_RATIO) return@forEachIndexed
            if (abs(leftCenterY - rightCenterY) > box.height() * MAX_EYE_LEVEL_DIFFERENCE) return@forEachIndexed

            val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
            val mouth = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
            val eyeLine = (leftCenterY + rightCenterY) / 2f
            val lowerAnchor = nose ?: mouth ?: return@forEachIndexed
            if (lowerAnchor.y <= eyeLine - box.height() * MIN_LOWER_FACE_OFFSET) return@forEachIndexed

            val marginX = box.width() * LANDMARK_MARGIN
            val marginY = box.height() * LANDMARK_MARGIN
            val landmarks = listOfNotNull(nose, mouth)
            if (landmarks.any {
                    it.x < box.left - marginX || it.x > box.right + marginX ||
                    it.y < box.top - marginY || it.y > box.bottom + marginY
                }) return@forEachIndexed

            accepted += index
        }
        return accepted
    }

    /**
     * ML Kit eye contours contain 16 ordered points. The contour traverses
     * one eyelid arc from one eye corner to the other, then returns along the
     * opposite arc. Use two interior vertical spans and the corner-to-corner
     * width, matching the conventional EAR geometry as closely as this
     * contour representation permits.
     */
    private fun calculateEar(points: List<android.graphics.PointF>): Float? {
        if (points.size != EYE_CONTOUR_POINT_COUNT) return null
        val width = distance(points[0], points[8])
        if (width <= MIN_EYE_WIDTH_PX) return null
        val verticalOne = distance(points[2], points[14])
        val verticalTwo = distance(points[3], points[13])
        val ear = (verticalOne + verticalTwo) / (2f * width)
        return ear.takeIf { it.isFinite() && it in MIN_EAR..MAX_EAR }
    }

    private fun distance(a: android.graphics.PointF, b: android.graphics.PointF): Float =
        hypot(a.x - b.x, a.y - b.y)

    companion object {
        private const val MIN_FACE_WIDTH_RATIO = 0.05f
        private const val MIN_FACE_BOX_RATIO = 0.35f
        private const val MAX_FACE_BOX_RATIO = 2.2f
        private const val EYE_CONTOUR_POINT_COUNT = 16
        private const val MIN_EYE_WIDTH_PX = 1f
        private const val MIN_EAR = 0f
        private const val MAX_EAR = 0.8f
        private const val MAX_BILATERAL_EAR_DIFFERENCE = 0.45f
        private const val MIN_EYE_SPAN_RATIO = 0.12f
        private const val MAX_EYE_SPAN_RATIO = 0.75f
        private const val MAX_EYE_LEVEL_DIFFERENCE = 0.25f
        private const val MIN_LOWER_FACE_OFFSET = 0.08f
        private const val LANDMARK_MARGIN = 0.18f
    }
}
