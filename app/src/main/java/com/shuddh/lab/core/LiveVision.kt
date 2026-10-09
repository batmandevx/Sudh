package com.shuddh.lab.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** A normalised (0..1) image point. */
data class P(val x: Float, val y: Float)

data class SeenObject(val label: String, val score: Float, val box: RectF)
data class SeenHand(val pts: List<P>, val side: String, val gesture: Gesture, val fingers: Int)
data class SeenFace(val pts: List<P>, val smile: Float, val blinkL: Float, val blinkR: Float, val jawOpen: Float, val yawDeg: Float, val box: RectF)
data class SeenPose(val pts: List<P>, val vis: List<Float>)

data class Scene(
    val objects: List<SeenObject> = emptyList(),
    val hands: List<SeenHand> = emptyList(),
    val faces: List<SeenFace> = emptyList(),
    val poses: List<SeenPose> = emptyList(),
    /** Inference time per model, ms. */
    val ms: Map<String, Long> = emptyMap(),
    val frameW: Int = 0,
    val frameH: Int = 0,
)

enum class Gesture(val emoji: String, val label: String) {
    NONE("🖐", "Hand"), OPEN("✋", "Open palm"), FIST("✊", "Fist"), THUMBS_UP("👍", "Thumbs up"), THUMBS_DOWN("👎", "Thumbs down"),
    VICTORY("✌️", "Victory"), POINT("☝️", "Pointing"), OK("👌", "OK"), ROCK("🤘", "Rock on"), CALL("🤙", "Call me"),
}

/**
 * Live machine vision, fully on-device with MediaPipe Tasks (VIDEO mode):
 * EfficientDet-Lite0 objects (80 COCO classes), 21-joint hands ×2, 478-point face mesh with
 * blendshapes, and 33-joint body pose. Each model is created lazily and only runs when enabled.
 */
class LiveVision(private val ctx: Context) {
    private var objects: ObjectDetector? = null
    private var hands: HandLandmarker? = null
    private var face: FaceLandmarker? = null
    private var pose: PoseLandmarker? = null
    private var lastTs = 0L

    /**
     * GPU first (several × faster on Adreno). A model that fails on the GPU — at creation or at
     * inference — is rebuilt on the CPU; the int8 object detector starts on CPU.
     */
    private val cpuOnly = mutableSetOf("objects")
    val backend get() = if (cpuOnly.size >= 4) "CPU" else "GPU+CPU"
    private fun base(asset: String, key: String) = BaseOptions.builder().setModelAssetPath(asset)
        .setDelegate(if (key in cpuOnly) com.google.mediapipe.tasks.core.Delegate.CPU else com.google.mediapipe.tasks.core.Delegate.GPU).build()

    private fun <T> make(key: String, f: () -> T): T = try { f() } catch (e: Throwable) {
        if (key in cpuOnly) throw e
        cpuOnly += key; f()
    }

    /** Runs one model; on a GPU failure drops that model so it is recreated on CPU next frame. */
    private fun <T> guard(key: String, empty: T, f: () -> T): T = try { f() } catch (e: Throwable) {
        if (key in cpuOnly) throw e
        cpuOnly += key
        when (key) {
            "objects" -> { runCatching { objects?.close() }; objects = null }
            "hands" -> { runCatching { hands?.close() }; hands = null }
            "face" -> { runCatching { face?.close() }; face = null }
            else -> { runCatching { pose?.close() }; pose = null }
        }
        empty
    }

    @Synchronized
    fun process(bmp: Bitmap, wantObjects: Boolean, wantHands: Boolean, wantFace: Boolean, wantPose: Boolean): Scene {
        val img = BitmapImageBuilder(bmp).build()
        // VIDEO mode demands strictly increasing timestamps.
        val ts = maxOf(System.nanoTime() / 1_000_000, lastTs + 1).also { lastTs = it }
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        val ms = mutableMapOf<String, Long>()
        fun <T> timed(name: String, f: () -> T): T { val t0 = System.nanoTime(); return f().also { ms[name] = (System.nanoTime() - t0) / 1_000_000 } }

        val objs = if (!wantObjects) emptyList() else timed("objects") { guard("objects", emptyList()) {
            val d = objects ?: make("objects") { ObjectDetector.createFromOptions(ctx, ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(base("efficientdet_lite0.tflite", "objects")).setRunningMode(RunningMode.VIDEO)
                .setMaxResults(8).setScoreThreshold(0.38f).build()) }.also { objects = it }
            d.detectForVideo(img, ts).detections().mapNotNull { det ->
                val c = det.categories().firstOrNull() ?: return@mapNotNull null
                val b = det.boundingBox()
                SeenObject(c.categoryName(), c.score(), RectF(b.left / w, b.top / h, b.right / w, b.bottom / h))
            }
        } }
        val hs = if (!wantHands) emptyList() else timed("hands") { guard("hands", emptyList()) {
            val d = hands ?: make("hands") { HandLandmarker.createFromOptions(ctx, HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base("hand_landmarker.task", "hands")).setRunningMode(RunningMode.VIDEO).setNumHands(2)
                .setMinHandDetectionConfidence(0.5f).setMinTrackingConfidence(0.5f).build()) }.also { hands = it }
            val r = d.detectForVideo(img, ts)
            r.landmarks().mapIndexed { i, lm ->
                val pts = lm.map { P(it.x(), it.y()) }
                val side = r.handedness().getOrNull(i)?.firstOrNull()?.categoryName() ?: ""
                val (g, n) = VisionCues.gesture(pts)
                SeenHand(pts, side, g, n)
            }
        } }
        val fs = if (!wantFace) emptyList() else timed("face") { guard("face", emptyList()) {
            val d = face ?: make("face") { FaceLandmarker.createFromOptions(ctx, FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(base("face_landmarker.task", "face")).setRunningMode(RunningMode.VIDEO).setNumFaces(2)
                .setOutputFaceBlendshapes(true).build()) }.also { face = it }
            val r = d.detectForVideo(img, ts)
            val shapes = r.faceBlendshapes().orElse(emptyList())
            r.faceLandmarks().mapIndexed { i, lm ->
                val pts = lm.map { P(it.x(), it.y()) }
                val bs = shapes.getOrNull(i)?.associate { it.categoryName() to it.score() } ?: emptyMap()
                SeenFace(
                    pts,
                    smile = ((bs["mouthSmileLeft"] ?: 0f) + (bs["mouthSmileRight"] ?: 0f)) / 2,
                    blinkL = bs["eyeBlinkLeft"] ?: 0f, blinkR = bs["eyeBlinkRight"] ?: 0f,
                    jawOpen = bs["jawOpen"] ?: 0f,
                    yawDeg = VisionCues.yaw(pts),
                    box = VisionCues.bounds(pts),
                )
            }
        } }
        val ps = if (!wantPose) emptyList() else timed("pose") { guard("pose", emptyList()) {
            val d = pose ?: make("pose") { PoseLandmarker.createFromOptions(ctx, PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(base("pose_landmarker_lite.task", "pose")).setRunningMode(RunningMode.VIDEO).setNumPoses(1).build()) }.also { pose = it }
            d.detectForVideo(img, ts).landmarks().map { lm ->
                SeenPose(lm.map { P(it.x(), it.y()) }, lm.map { it.visibility().orElse(1f) })
            }
        } }
        return Scene(objs, hs, fs, ps, ms, bmp.width, bmp.height)
    }

    @Synchronized
    fun close() {
        runCatching { objects?.close() }; runCatching { hands?.close() }; runCatching { face?.close() }; runCatching { pose?.close() }
        objects = null; hands = null; face = null; pose = null
    }
}

/** Pure geometry on landmarks — no Android, unit-tested. */
object VisionCues {
    private fun d(a: P, b: P) = hypot(a.x - b.x, a.y - b.y)

    /** Hand skeleton edges (MediaPipe 21-point topology). */
    val HAND_EDGES = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8, 5 to 9, 9 to 10, 10 to 11, 11 to 12,
        9 to 13, 13 to 14, 14 to 15, 15 to 16, 13 to 17, 0 to 17, 17 to 18, 18 to 19, 19 to 20,
    )

    /** Body skeleton edges (MediaPipe 33-point pose). */
    val POSE_EDGES = listOf(
        11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16, 11 to 23, 12 to 24, 23 to 24,
        23 to 25, 25 to 27, 24 to 26, 26 to 28, 27 to 31, 28 to 32, 15 to 19, 16 to 20, 0 to 11, 0 to 12,
    )

    /**
     * Which fingers are extended, judged rotation-invariantly: a finger is out when its tip is
     * farther from the wrist than its middle joint. The thumb is out when its tip is far from the
     * index knuckle relative to the palm size.
     */
    fun extended(p: List<P>): BooleanArray {
        val palm = d(p[0], p[9]).coerceAtLeast(1e-4f)
        val thumb = d(p[4], p[5]) / palm > 0.55f && d(p[4], p[17]) > d(p[3], p[17])
        fun f(tip: Int, pip: Int) = d(p[tip], p[0]) > d(p[pip], p[0]) * 1.1f
        return booleanArrayOf(thumb, f(8, 6), f(12, 10), f(16, 14), f(20, 18))
    }

    fun gesture(p: List<P>): Pair<Gesture, Int> {
        if (p.size < 21) return Gesture.NONE to 0
        val e = extended(p)
        val n = e.count { it }
        val palm = d(p[0], p[9]).coerceAtLeast(1e-4f)
        val (t, i, m, r, l) = listOf(e[0], e[1], e[2], e[3], e[4])
        val g = when {
            d(p[4], p[8]) / palm < 0.35f && m && r && l -> Gesture.OK
            t && !i && !m && !r && !l -> {
                val dy = p[4].y - p[2].y
                if (dy < -0.4f * palm) Gesture.THUMBS_UP else if (dy > 0.4f * palm) Gesture.THUMBS_DOWN else Gesture.NONE
            }
            n == 5 -> Gesture.OPEN
            n == 0 -> Gesture.FIST
            i && m && !r && !l -> Gesture.VICTORY
            i && !m && !r && !l -> Gesture.POINT
            i && l && !m && !r -> Gesture.ROCK
            t && l && !i && !m && !r -> Gesture.CALL
            else -> Gesture.NONE
        }
        return g to n
    }

    /** Head yaw from the nose tip's position between the two cheek edges, degrees (− = turned left in the image). */
    fun yaw(p: List<P>): Float {
        if (p.size < 455) return 0f
        val l = p[234]; val r = p[454]; val nose = p[1]
        val span = (r.x - l.x).takeIf { abs(it) > 1e-4f } ?: return 0f
        val f = (nose.x - l.x) / span // 0.5 = facing camera
        return ((f - 0.5f) * 180f).coerceIn(-90f, 90f)
    }

    fun bounds(p: List<P>): RectF = if (p.isEmpty()) RectF() else RectF(p.minOf { it.x }, p.minOf { it.y }, p.maxOf { it.x }, p.maxOf { it.y })

    /** Shoulder line tilt in degrees (0 = level). */
    fun shoulderTilt(p: List<P>): Float {
        if (p.size < 13) return 0f
        val a = p[11]; val b = p[12]
        val deg = Math.toDegrees(atan2((a.y - b.y).toDouble(), (a.x - b.x).toDouble())).toFloat()
        // Normalise so a level line reads 0 regardless of which shoulder is on the left.
        return if (deg > 90) deg - 180 else if (deg < -90) deg + 180 else deg
    }

    /** Both wrists above the nose. */
    fun armsUp(p: List<P>): Boolean = p.size >= 17 && p[15].y < p[0].y && p[16].y < p[0].y

    /** What Shuddh can do about an object it sees — ties vision to the lab. */
    fun suggestion(label: String): Pair<String, String>? = when (label) {
        "bottle" -> "Water or oil? Test it" to "SPECTRUM"
        "cup", "wine glass" -> "Milk or drink? Run a purity scan" to "SPECTRUM"
        "bowl" -> "Grain or spice? Check moisture" to "NAMI"
        "banana", "apple", "orange" -> "Fruit: knock-test ripeness" to "ECHO"
        "carrot", "broccoli" -> "Veg: check for dyes" to "STRIP"
        "cake", "donut", "sandwich", "pizza", "hot dog" -> "Read its label for additives" to "LENS"
        "spoon", "fork", "knife" -> "Steel utensil? Check grade" to "MAGNETO"
        "person" -> "Measure your heart rate" to "PULSE"
        else -> null
    }
}
