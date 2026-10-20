package rocks.pkb.plektra

import android.view.ViewGroup
import android.graphics.drawable.ColorDrawable
import rocks.pkb.plektra.inputmethod.ui.VariationBarView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class ThemeTextureSurfaceTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val asset="texture-${"a".repeat(64)}.png"
    @Before fun reset() { SettingsManager.getPreferences(context).edit().clear().commit() }
    @Test fun manualTitleColorOnPhotoIgnoresPlainBackingWhileAutomaticContrastStillWinsWhenChosen() {
        val bar=VariationBarView(context)
        val theme=SettingsManager.defaultKeyboardTheme().copy(background=android.graphics.Color.WHITE,
            cursorSwipe=android.graphics.Color.WHITE,textures=mapOf("background" to KeyboardThemeTexture(asset)))
        bar.themeOverride=theme.toKeyboardThemeColors();bar.themeName="Photo";bar.ensureView()
        val field=VariationBarView::class.java.getDeclaredField("emptyHintView").apply { isAccessible=true }
        val hint=field.get(bar) as android.widget.TextView
        assertEquals(android.graphics.Color.WHITE,hint.currentTextColor)
        bar.themeOverride=theme.copy(textures=mapOf("background" to KeyboardThemeTexture(asset,
            foreground=android.graphics.Color.BLACK))).toKeyboardThemeColors()
        assertEquals(android.graphics.Color.BLACK,hint.currentTextColor)
    }
    @Test fun textureIsPresentOnTheFirstCreatedPhysicalBarWithoutAToggle() {
        val colors=SettingsManager.getKeyboardTheme(context,SettingsManager.KeyboardThemeTarget.HARDWARE)
            .copy(textures=mapOf("background" to KeyboardThemeTexture(asset))).toKeyboardThemeColors()
        val bar=VariationBarView(context)
        bar.themeOverride=colors
        val root=bar.ensureView() as ViewGroup
        assertTrue(root.background is ThemeTextureDrawable)
        assertTrue(root.getChildAt(0).background is ThemeTextureDrawable)
    }
    @Test fun revertingToASolidColourReplacesTheExistingTextureBackground() {
        val plain=SettingsManager.getKeyboardTheme(context,SettingsManager.KeyboardThemeTarget.HARDWARE).toKeyboardThemeColors()
        val bar=VariationBarView(context)
        bar.themeOverride=plain.copy(textures=mapOf("background" to KeyboardThemeTexture(asset)))
        val root=bar.ensureView()
        bar.themeOverride=plain
        assertTrue(root.background is ColorDrawable)
        assertEquals(plain.background,(root.background as ColorDrawable).color)
    }
}
