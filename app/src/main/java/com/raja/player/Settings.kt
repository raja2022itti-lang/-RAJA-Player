package com.raja.player

import android.content.Context

enum class ViewMode(
    val key: String,
    val labelRes: Int,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val sharpness: Float
) {
    BRIGHT_ROOM("bright", R.string.mode_bright, 0.02f, 1.10f, 1.10f, 0.30f),
    DARK_ROOM("dark", R.string.mode_dark, -0.02f, 0.95f, 0.95f, 0f),
    NATURAL("natural", R.string.mode_natural, 0f, 1f, 1f, 0f);

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: NATURAL
    }
}

enum class EnhanceMode(val key: String, val labelRes: Int) {
    AUTO("auto", R.string.enh_auto),
    ON("on", R.string.enh_on),
    OFF("off", R.string.enh_off);

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

enum class Aspect(val key: String, val labelRes: Int) {
    FIT("fit", R.string.aspect_fit),
    FILL("fill", R.string.aspect_fill),
    STRETCH("stretch", R.string.aspect_stretch);

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: FIT
    }
}

/** Stored picture settings. Brightness/contrast/saturation/sharpness only change the video inside the player. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("raja_player", Context.MODE_PRIVATE)

    var enhance: EnhanceMode
        get() = EnhanceMode.from(prefs.getString("enhance", null))
        set(value) = prefs.edit().putString("enhance", value.key).apply()

    var aspect: Aspect
        get() = Aspect.from(prefs.getString("aspect", null))
        set(value) = prefs.edit().putString("aspect", value.key).apply()

    var brightness: Float
        get() = prefs.getFloat("brightness", 0f)
        set(value) = prefs.edit().putFloat("brightness", value).apply()

    var contrast: Float
        get() = prefs.getFloat("contrast", 1f)
        set(value) = prefs.edit().putFloat("contrast", value).apply()

    var saturation: Float
        get() = prefs.getFloat("saturation", 1f)
        set(value) = prefs.edit().putFloat("saturation", value).apply()

    var sharpness: Float
        get() = prefs.getFloat("sharpness", 0f)
        set(value) = prefs.edit().putFloat("sharpness", value).apply()

    /** 0 = small, 1 = medium, 2 = large */
    var subsSize: Int
        get() = prefs.getInt("subsSize", 1)
        set(value) = prefs.edit().putInt("subsSize", value).apply()

    fun applyMode(mode: ViewMode) {
        brightness = mode.brightness
        contrast = mode.contrast
        saturation = mode.saturation
        sharpness = mode.sharpness
    }
}
