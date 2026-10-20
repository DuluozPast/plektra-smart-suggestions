package rocks.pkb.plektra

import android.graphics.Color
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[33])
class ThemeTextureContrastTest {
    private val texture=KeyboardThemeTexture("texture-"+"a".repeat(64)+".png")
    @Test fun darkAndLightPhotosChooseOppositeForegroundsWithoutHalo() {
        assertEquals(ThemeTextureContrast.Choice(Color.WHITE,false),ThemeTextureContrast.choose(IntArray(100){ Color.rgb(10,15,20) }))
        assertEquals(ThemeTextureContrast.Choice(Color.BLACK,false),ThemeTextureContrast.choose(IntArray(100){ Color.rgb(240,235,220) }))
    }
    @Test fun darkJointsAreNotHiddenInAnAverageAndNeedHalo() {
        val bricks=IntArray(100){ if(it<25) Color.rgb(15,10,5) else Color.rgb(220,180,140) }
        assertEquals(ThemeTextureContrast.Choice(Color.BLACK,true),ThemeTextureContrast.choose(bricks))
    }
    @Test fun settingsRoundTripContrastAndOldThemesKeepManualColor() {
        val adjusted=texture.copy(foreground=Color.BLACK,halo=true)
        assertEquals(adjusted,ThemeTextureModel.decode(ThemeTextureModel.encode(mapOf("suggestion" to adjusted)))["suggestion"])
        assertEquals(texture,ThemeTextureModel.decode(JSONObject().put("normal_key",JSONObject().put("asset",texture.asset)))["normal_key"])
        val bad=JSONObject().put("normal_key",JSONObject().put("asset",texture.asset).put("foreground",123))
        assertNull(ThemeTextureModel.decode(bad)["normal_key"]?.foreground)
    }
    @Test fun manualSelectionClearsHaloAndRestoresColorOnTheSameView() {
        val view=TextView(RuntimeEnvironment.getApplication())
        ThemeTextureContrast.text(view,Color.MAGENTA,texture.copy(foreground=Color.BLACK,halo=true))
        assertEquals(Color.BLACK,view.currentTextColor)
        ThemeTextureContrast.text(view,Color.MAGENTA,texture)
        assertEquals(Color.MAGENTA,view.currentTextColor)
        assertEquals(0f,view.shadowRadius,0f)
    }
    @Test fun restoreManualColorsPreservesTextureAssetsAndModes() {
        val theme=SettingsManager.getKeyboardTheme(RuntimeEnvironment.getApplication(), SettingsManager.KeyboardThemeTarget.HARDWARE).toKeyboardThemePreset("test")
        val adjusted=theme.copy(textures=mapOf("normal_key" to texture.copy(mode="tile",foreground=Color.WHITE,halo=true)))
        val manual=adjusted.withoutTextureContrast()
        assertEquals(texture.copy(mode="tile"),manual.textures["normal_key"])
        assertEquals(theme.textAndIcons,manual.textAndIcons)
    }
    @Test fun statusIconAdaptsAndRestoresWithoutNestingWrappers() {
        val context=RuntimeEnvironment.getApplication()
        val host=rocks.pkb.plektra.inputmethod.statusbar.StatusBarButtonHost(context,rocks.pkb.plektra.inputmethod.statusbar.StatusBarButtonRegistry())
        val theme=rocks.pkb.plektra.inputmethod.statusbar.StatusBarButtonStyles.ThemeOverride(Color.GRAY,Color.BLUE,Color.MAGENTA,texture=texture.copy(foreground=Color.WHITE,halo=true))
        host.themeOverride=theme
        val button=host.getOrCreateButton(rocks.pkb.plektra.inputmethod.statusbar.StatusBarButtonId.Clipboard,40,rocks.pkb.plektra.inputmethod.statusbar.StatusBarCallbacks(),80,40)!!.button as android.widget.ImageView
        assertTrue(button.drawable is ThemeContrastIcon)
        host.themeOverride=theme.copy(iconColor=Color.GREEN)
        assertFalse((button.drawable as ThemeContrastIcon).source is ThemeContrastIcon)
        host.themeOverride=theme.copy(texture=null)
        assertFalse(button.drawable is ThemeContrastIcon)
    }
}
