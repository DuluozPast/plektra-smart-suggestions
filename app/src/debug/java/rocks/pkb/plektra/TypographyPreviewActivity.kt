package rocks.pkb.plektra
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.content.res.Configuration
import java.util.Locale

/** Shell-only synthetic comparison using exactly the settings preview widget. No preferences are saved. */
class TypographyPreviewActivity:Activity() {
 private val handler=Handler(Looper.getMainLooper())
 override fun onCreate(state:Bundle?) {
  super.onCreate(state)
  val config=Configuration(resources.configuration).apply {setLocale(Locale.ENGLISH)}
  val demo=createConfigurationContext(config)
  val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(24,32,24,24);setBackgroundColor(Color.WHITE)}
  root.addView(TextView(demo).apply {text="Keyboard text size â€” real renderer";textSize=20f;setTextColor(Color.BLACK)})
  val preview=KeyboardTextScalePreview(demo);preview.setLabelColor(Color.BLACK);root.addView(preview)
  root.addView(TextView(demo).apply {text="The reference stays at 100%. The selected size cycles through 85%, 100%, 125%. Long suggestions still shrink to fit.";textSize=16f;setTextColor(Color.BLACK)})
  root.setOnApplyWindowInsetsListener { view, insets ->
   val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
   view.setPadding(24 + bars.left, 24 + bars.top, 24 + bars.right, 24 + bars.bottom)
   insets
  }
  setContentView(root)
  root.requestApplyInsets()
  val sizes=floatArrayOf(.85f,1f,1.25f);var i=0
  val tick=object:Runnable {override fun run(){preview.setScale(sizes[i++%sizes.size]);handler.postDelayed(this,2500)}}
  handler.post(tick)
 }
 override fun onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy()}
}
