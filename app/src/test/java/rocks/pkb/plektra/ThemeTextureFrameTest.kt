package rocks.pkb.plektra

import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class ThemeTextureFrameTest {
    @Test fun adjacentKeysRevealDifferentPartsOfOneSharedImage() {
        val activity=org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val context=activity
        val root=FrameLayout(context).apply {setTag(R.id.theme_texture_frame,true)}
        activity.setContentView(root)
        val left=View(context);val right=View(context);root.addView(left);root.addView(right)
        root.layout(0,0,300,100);left.layout(0,0,100,100);right.layout(100,0,200,100)
        val first=ThemeTextureFrame.bounds(left,RectF(0f,0f,100f,100f))
        val second=ThemeTextureFrame.bounds(right,RectF(0f,0f,100f,100f))
        assertEquals(300f,first.width(),0f);assertEquals(first.width(),second.width(),0f)
        assertEquals(first.left-100f,second.left,0f)
        // Both shaders use the same scale. Their image origin differs by precisely the key offset.
        assertEquals(ThemeTextureModel.renderScale(300,100,first.width(),first.height(),"cover"),ThemeTextureModel.renderScale(300,100,second.width(),second.height(),"cover"),0f)
    }
    @Test fun separateRowsShareTheVerticalFrameAndMissingFrameKeepsLocalBounds() {
        val activity=org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val context=activity
        val root=FrameLayout(context).apply {setTag(R.id.theme_texture_frame,true)}
        activity.setContentView(root)
        val top=View(context);val bottom=View(context);root.addView(top);root.addView(bottom)
        root.layout(0,0,300,100);top.layout(0,0,100,50);bottom.layout(0,50,100,100)
        assertEquals(-50f,ThemeTextureFrame.bounds(bottom,RectF()).top,0f)
        assertEquals(0f,ThemeTextureFrame.bounds(top,RectF()).top,0f)
        assertEquals(RectF(1f,2f,3f,4f),ThemeTextureFrame.bounds(View(context),RectF(1f,2f,3f,4f)))
    }
    @Test fun continuousSettingSurvivesJsonAndLegacyThemesKeepTheirOldRendering() {
        val asset="texture-"+"a".repeat(64)+".png"
        val texture=KeyboardThemeTexture(asset,foreground=android.graphics.Color.BLACK,halo=true,continuous=true)
        assertEquals(texture,ThemeTextureModel.decode(ThemeTextureModel.encode(mapOf("normal_key" to texture)))["normal_key"])
        assertFalse(ThemeTextureModel.decode(JSONObject().put("normal_key",JSONObject().put("asset",asset)))["normal_key"]!!.continuous)
    }
    @Test fun globalMaterialUsesTheSameFrameForKeyboardAndSurroundingChrome() {
        val activity=org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val root=FrameLayout(activity).apply {setTag(R.id.theme_texture_frame,true)}
        val keyboard=AospKeyboardView(activity)
        val key=View(activity);val suggestions=View(activity)
        root.addView(suggestions);root.addView(keyboard);keyboard.addView(key)
        activity.setContentView(root)
        root.layout(0,0,300,120);suggestions.layout(0,0,300,30)
        keyboard.layout(0,30,300,120);key.layout(0,0,100,90)
        val local=ThemeTextureFrame.bounds(key,RectF(),false)
        val global=ThemeTextureFrame.bounds(key,RectF(),true)
        val chrome=ThemeTextureFrame.bounds(suggestions,RectF(),true)
        assertEquals(90f,local.height(),0f);assertEquals(120f,global.height(),0f)
        assertEquals(chrome.height(),global.height(),0f);assertEquals(-30f,global.top,0f)
        activity.finish()
    }
    private class AospKeyboardView(context:android.content.Context):FrameLayout(context)

}
