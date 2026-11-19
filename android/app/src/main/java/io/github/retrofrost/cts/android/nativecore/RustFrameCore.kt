package io.github.retrofrost.cts.android.nativecore

import io.github.retrofrost.cts.android.BuildConfig

/** Primitive-only JNI bridge used by both preview and exported frames.
 * Host JVM tests and legacy non-native builds use the identical Kotlin reference.
 * Native Android builds fail explicitly if their required library is unavailable.
 */
internal object RustFrameCore {
    const val LINEAR = 0
    const val SMOOTH = 1
    const val CUBIC_IN = 2
    const val CUBIC_OUT = 3
    const val QUAD_IN = 4
    const val QUAD_OUT = 5
    const val HOLD = 6

    val nativeAvailable: Boolean by lazy {
        val loaded = try {
            System.loadLibrary("cts_render_core")
            nativeAbiVersion() == 1
        } catch (_: UnsatisfiedLinkError) {
            false
        }
        check(loaded || !BuildConfig.CTS_RUST_REQUIRED ||
            System.getProperty("java.vm.name") != "Dalvik") {
            "Required CTS Rust renderer library is missing or incompatible"
        }
        loaded
    }

    private external fun nativeAbiVersion(): Int
    private external fun nativeEase(value: Float, style: Int): Float
    private external fun nativeMaterial(value: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float
    private external fun nativeSequenceIndex(frame: Int, frames: Int, pause: Int, count: Int): Int

    fun ease(value: Float, style: Int): Float =
        if (nativeAvailable) nativeEase(value, style) else referenceEase(value, style)

    fun material(value: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float =
        if (nativeAvailable) nativeMaterial(value, x1, y1, x2, y2)
        else referenceMaterial(value, x1, y1, x2, y2)

    fun sequenceIndex(frame: Int, frames: Int, pause: Int, count: Int): Int =
        if (nativeAvailable) nativeSequenceIndex(frame, frames, pause, count)
        else referenceSequenceIndex(frame, frames, pause, count)

    internal fun referenceEase(value: Float, style: Int): Float {
        val t = value.coerceIn(0f, 1f)
        val q = 1f - t
        return when (style) {
            SMOOTH -> t * t * (3f - 2f * t)
            CUBIC_IN -> t * t * t
            CUBIC_OUT -> 1f - q * q * q
            QUAD_IN -> t * t
            QUAD_OUT -> 1f - q * q
            HOLD -> 0f
            else -> t
        }
    }

    internal fun referenceMaterial(value: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val x = value.coerceIn(0f, 1f)
        if (x <= 0f || x >= 1f) return x
        fun cubic(t: Float, a: Float, b: Float): Float {
            val q = 1f - t
            return 3f * q * q * t * a + 3f * q * t * t * b + t * t * t
        }
        var low = 0f
        var high = 1f
        repeat(12) {
            val t = (low + high) / 2f
            if (cubic(t, x1, x2) < x) low = t else high = t
        }
        return cubic((low + high) / 2f, y1, y2)
    }

    internal fun referenceSequenceIndex(frame: Int, frames: Int, pause: Int, count: Int): Int {
        if (frame < 0 || frames <= 0 || pause < 0 || count < 0) return -1
        val length = frames.toLong() + pause
        if (count != 0 && frame.toLong() >= length * count) return -1
        return (frame.toLong() % length).coerceAtMost(frames.toLong() - 1).toInt()
    }
}
