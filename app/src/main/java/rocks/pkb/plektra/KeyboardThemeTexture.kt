package rocks.pkb.plektra

import android.graphics.Rect
import android.graphics.RectF
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** References are small JSON values. Pixels are stored separately in theme-images/. */
data class KeyboardThemeTexture(val asset: String, val mode: String = "cover", val foreground: Int? = null, val halo: Boolean = false, val continuous: Boolean = false, val wholeKeyboard: Boolean = false, val intensity: Float = 1f)

internal object ThemeTextureModel {
    const val ALL_SURFACES = "__all_surfaces__"
    val roles = setOf("background", "normal_key", "special_key", "suggestion", "status_bar_button", "key_popup", "key_popup_selected")
    val modes = setOf("cover", "fit", "tile")
    val assetPattern = Regex("texture-[0-9a-f]{64}\\.png")
    const val MAX_EDGE = 512

    fun normalizedIntensity(texture: KeyboardThemeTexture): Float =
        if(texture.intensity.isFinite()) texture.intensity.coerceIn(0f,1f) else 1f
    fun renderAlpha(texture: KeyboardThemeTexture, alpha: Int = 255): Int =
        kotlin.math.round(alpha.coerceIn(0,255) * normalizedIntensity(texture)).toInt()

    fun decode(json: JSONObject?): Map<String, KeyboardThemeTexture> = buildMap {
        if (json != null) for (role in roles) {
            val entry = json.optJSONObject(role) ?: continue
            val asset = entry.optString("asset")
            val mode = entry.optString("mode", "cover")
            if (assetPattern.matches(asset) && mode in modes) put(role, KeyboardThemeTexture(asset, mode, entry.optInt("foreground").takeIf { entry.has("foreground") && it in setOf(android.graphics.Color.BLACK, android.graphics.Color.WHITE) }, entry.optBoolean("halo", false), entry.optBoolean("continuous", false), entry.optBoolean("wholeKeyboard", false), entry.optDouble("intensity", 1.0).let { if(it.isFinite()) it.coerceIn(0.0,1.0).toFloat() else 1f }))
        }
    }

    fun encode(textures: Map<String, KeyboardThemeTexture>): JSONObject = JSONObject().apply {
        for ((role, texture) in textures) if (role in roles && assetPattern.matches(texture.asset) && texture.mode in modes) {
            put(role, JSONObject().put("asset", texture.asset).put("mode", texture.mode).put("continuous", texture.continuous).put("wholeKeyboard", texture.wholeKeyboard).put("intensity", normalizedIntensity(texture).toDouble()).apply { texture.foreground?.let { put("foreground", it); put("halo", texture.halo) } })
        }
    }

    fun referencedAssets(values: Collection<*>): Set<String> = buildSet {
        fun collect(value: Any?) {
            when (value) {
                is JSONObject -> {
                    decode(value.optJSONObject("textures")).values.forEach { add(it.asset) }
                    val names = value.keys()
                    while (names.hasNext()) collect(value.opt(names.next()))
                }
                is org.json.JSONArray -> for (index in 0 until value.length()) collect(value.opt(index))
            }
        }
        for (value in values) if (value is String && value.length <= 4 * 1024 * 1024) {
            try {
                when (value.trimStart().firstOrNull()) {
                    '{' -> collect(JSONObject(value))
                    '[' -> collect(org.json.JSONArray(value))
                }
            } catch (_: org.json.JSONException) { /* Other preferences are ordinary strings. */ }
        }
    }

    data class CropPlan(val fullWidth: Int, val fullHeight: Int, val crop: Rect)

    /** Work in the oriented ImageDecoder coordinate space; allocate only the selected output. */
    fun cropPlan(width: Int, height: Int, region: RectF): CropPlan {
        require(width > 0 && height > 0 && width <= 100_000 && height <= 100_000)
        require(listOf(region.left, region.top, region.right, region.bottom).all { it.isFinite() })
        require(region.left >= 0f && region.top >= 0f && region.right <= 1f && region.bottom <= 1f && !region.isEmpty)
        val sourceWidth = max(1.0, width * region.width().toDouble())
        val sourceHeight = max(1.0, height * region.height().toDouble())
        // Bound both the result and possible decoder intermediates, without upscaling.
        val scale = min(1.0, min(MAX_EDGE / max(sourceWidth, sourceHeight), sqrt(4_000_000.0 / width.toDouble() / height)))
        val fullWidth = max(1, floor(width * scale).toInt())
        val fullHeight = max(1, floor(height * scale).toInt())
        val left = floor(region.left * fullWidth).toInt().coerceIn(0, fullWidth - 1)
        val top = floor(region.top * fullHeight).toInt().coerceIn(0, fullHeight - 1)
        val right = ceil(region.right * fullWidth).toInt().coerceIn(left + 1, min(fullWidth, left + MAX_EDGE))
        val bottom = ceil(region.bottom * fullHeight).toInt().coerceIn(top + 1, min(fullHeight, top + MAX_EDGE))
        return CropPlan(fullWidth, fullHeight, Rect(left, top, right, bottom))
    }

    fun renderScale(sampleWidth: Int, sampleHeight: Int, width: Float, height: Float, mode: String): Float {
        if (sampleWidth <= 0 || sampleHeight <= 0 || width <= 0 || height <= 0) return 1f
        return when (mode) {
            "tile" -> 1f
            "fit" -> min(width / sampleWidth, height / sampleHeight)
            else -> max(width / sampleWidth, height / sampleHeight)
        }
    }
}

internal fun KeyboardThemePreset.withSurfaceTexture(role: String?, texture: KeyboardThemeTexture?, coupled: Boolean = false): KeyboardThemePreset {
    if (role !in ThemeTextureModel.roles && role != ThemeTextureModel.ALL_SURFACES) return this
    val affected = when {
        role == ThemeTextureModel.ALL_SURFACES -> ThemeTextureModel.roles
        coupled && role == "normal_key" -> setOf("normal_key", "suggestion")
        coupled && role == "special_key" -> setOf("special_key", "status_bar_button")
        else -> setOf(requireNotNull(role))
    }
    val next = textures.toMutableMap()
    for (key in affected) if (texture == null) next.remove(key) else next[key] = if(role == ThemeTextureModel.ALL_SURFACES) texture.copy(continuous = true, wholeKeyboard = true) else texture
    return copy(textures = next)
}

internal fun KeyboardThemePreset.withoutTextureContrast(): KeyboardThemePreset = copy(
    textures = textures.mapValues { (_, texture) -> texture.copy(foreground = null, halo = false) })
