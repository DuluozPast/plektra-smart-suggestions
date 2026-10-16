package rocks.pkb.plektra

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Independent of upward suggestion calibration. Coordinates are normalized to the
 * physical touch surface; distance/velocity remain in that device's native units. */
object DeleteSwipeCalibration {
    const val KEY = "trackpad_delete_calibration"
    data class Profile(val minX: Float, val maxX: Float, val distance: Float, val speed: Float) {
        fun accepts(startX: Float, left: Float, vertical: Float, durationMs: Long) =
            startX in minX..maxX && left >= distance && kotlin.math.abs(vertical) < left * .45f &&
                left / durationMs.coerceAtLeast(1) >= speed
    }
    private fun session(context: Context) = context.getSharedPreferences("delete_swipe_calibration_session", Context.MODE_PRIVATE)
    fun start(context: Context) { session(context).edit().clear().putBoolean("active", true).commit() }
    fun cancel(context: Context) { session(context).edit().clear().commit() }
    fun active(context: Context) = session(context).getBoolean("active", false)
    private fun samples(context: Context) = runCatching { JSONArray(session(context).getString("samples", "[]")) }.getOrDefault(JSONArray())
    fun count(context: Context) = samples(context).length()
    fun record(context: Context, x: Float, left: Float, vertical: Float, duration: Long) {
        if (!active(context) || !x.isFinite() || x !in 0f..1f || !left.isFinite() || left < 15f ||
            kotlin.math.abs(vertical) >= left * .6f || duration !in 30L..2000L) return
        val data = samples(context)
        if (data.length() >= 5) return
        data.put(JSONArray().put(x.toDouble()).put(left.toDouble()).put((left / duration).toDouble()))
        session(context).edit().putString("samples", data.toString()).commit()
    }
    fun save(context: Context): Boolean {
        val data = samples(context)
        if (data.length() != 5) return false
        val xs = (0 until 5).map { data.getJSONArray(it).getDouble(0).toFloat() }
        val distances = (0 until 5).map { data.getJSONArray(it).getDouble(1).toFloat() }.sorted()
        val speeds = (0 until 5).map { data.getJSONArray(it).getDouble(2).toFloat() }.sorted()
        val json = JSONObject().put("minX", (xs.min() - .08f).coerceAtLeast(0f).toDouble())
            .put("maxX", (xs.max() + .08f).coerceAtMost(1f).toDouble())
            .put("distance", (distances[2] * .65f).coerceAtLeast(15f).toDouble())
            .put("speed", (speeds[2] * .5f).coerceAtLeast(.05f).toDouble())
        SettingsManager.getPreferences(context).edit().putString(KEY, json.toString()).commit()
        SettingsManager.setSwipeToDelete(context, true)
        SettingsManager.setSwipeToDeleteProvider(context, SettingsManager.SWIPE_TO_DELETE_PROVIDER_NATIVE_IME)
        cancel(context)
        return true
    }
    fun clear(context: Context) { SettingsManager.getPreferences(context).edit().remove(KEY).commit() }
    fun get(context: Context): Profile? = runCatching {
        val j = JSONObject(SettingsManager.getPreferences(context).getString(KEY, "") ?: "")
        Profile(j.getDouble("minX").toFloat(), j.getDouble("maxX").toFloat(), j.getDouble("distance").toFloat(), j.getDouble("speed").toFloat()).takeIf {
            it.minX.isFinite() && it.maxX.isFinite() && it.minX in 0f..1f && it.maxX in 0f..1f && it.minX < it.maxX &&
                it.distance.isFinite() && it.distance >= 15f && it.speed.isFinite() && it.speed >= .05f
        }
    }.getOrNull()
}
