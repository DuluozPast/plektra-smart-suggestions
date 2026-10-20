package rocks.pkb.plektra

import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThemeTextureModelTest {
    private val asset = "texture-" + "a".repeat(64) + ".png"
    @Test fun intensitySurvivesThemeJsonAndRendererConversionsAndDefaultsForOldThemes() {
        val texture=KeyboardThemeTexture(asset,intensity=.35f,continuous=true,wholeKeyboard=true)
        val theme=SettingsManager.defaultKeyboardTheme().copy(textures=mapOf("background" to texture))
        val parsed=SettingsManager.keyboardThemeFromJsonString(SettingsManager.keyboardThemeToJsonString(theme))!!
        assertEquals(texture,parsed.toKeyboardThemePreset("Photo").toKeyboardThemeColors().textures["background"])
        assertEquals(89,ThemeTextureModel.renderAlpha(texture))
        assertEquals(35,ThemeTextureModel.renderAlpha(texture,100))
        val old=JSONObject().put("background",JSONObject().put("asset",asset))
        assertEquals(1f,ThemeTextureModel.decode(old)["background"]!!.intensity,0f)
        for((value,expected) in listOf(-2.0 to 0f,2.0 to 1f)) {
            val encoded=JSONObject().put("background",JSONObject().put("asset",asset).put("intensity",value))
            assertEquals(expected,ThemeTextureModel.decode(encoded)["background"]!!.intensity,0f)
        }
        assertEquals(255,ThemeTextureModel.renderAlpha(texture.copy(intensity=Float.NaN)))
        assertEquals(0,ThemeTextureModel.renderAlpha(texture.copy(intensity=0f)))
    }
    @Test fun textureReferencesSurviveSettingsAndAllRendererConversions() {
        val context = RuntimeEnvironment.getApplication()
        val target = SettingsManager.KeyboardThemeTarget.HARDWARE
        val textures = mapOf("background" to KeyboardThemeTexture(asset,"tile"),"suggestion" to KeyboardThemeTexture(asset,"fit"))
        val theme = SettingsManager.getKeyboardTheme(context,target).copy(textures=textures)
        SettingsManager.setKeyboardTheme(context,target,theme)
        val restored = SettingsManager.getKeyboardTheme(context,target)
        assertEquals(textures,restored.textures)
        val parsed = SettingsManager.keyboardThemeFromJsonString(SettingsManager.keyboardThemeToJsonString(restored))!!
        val preset = parsed.toKeyboardThemePreset("Matière")
        assertEquals(textures,preset.toSettingsTheme().textures)
        assertEquals(textures,preset.toKeyboardThemeColors().textures)
        assertEquals(textures,preset.toAospThemeOverride().textures)
    }
    @Test fun oldThemesStayPlainAndUnsafeReferencesCannotReachAFile() {
        val json=JSONObject().put("background",JSONObject().put("asset","../secret.png"))
            .put("suggestion",JSONObject().put("asset",asset).put("mode","invalid"))
            .put("text_icons",JSONObject().put("asset",asset))
        assertTrue(ThemeTextureModel.decode(json).isEmpty())
        assertTrue(ThemeTextureModel.decode(null).isEmpty())
    }
    @Test fun referencedAssetsAreFoundInsideSavedThemesAndDraftsWithoutIncludingOtherStrings() {
        val nested=JSONObject().put("themes",org.json.JSONArray().put(JSONObject().put("theme",
            JSONObject().put("textures",ThemeTextureModel.encode(mapOf("normal_key" to KeyboardThemeTexture(asset)))))))
        assertEquals(setOf(asset),ThemeTextureModel.referencedAssets(listOf(nested.toString(),"ordinary text",false,123)))
    }
    @Test fun cropBoundsPreserveTheChosenRegionAndBoundDecoderMemory() {
        val plan=ThemeTextureModel.cropPlan(8000,6000,RectF(.2f,.3f,.6f,.8f))
        assertTrue(plan.fullWidth*plan.fullHeight<=4_000_000)
        assertTrue(plan.crop.width() in 1..512 && plan.crop.height() in 1..512)
        assertEquals(.2f,plan.crop.left.toFloat()/plan.fullWidth,.003f)
        assertEquals(.8f,plan.crop.bottom.toFloat()/plan.fullHeight,.003f)
        val tiny=ThemeTextureModel.cropPlan(10,8,RectF(0f,0f,1f,1f))
        assertEquals(10,tiny.crop.width());assertEquals(8,tiny.crop.height())
    }
    @Test fun malformedCropRegionsAreRejected() {
        for(region in listOf(RectF(-1f,0f,1f,1f),RectF(0f,0f,Float.NaN,1f),RectF(0f,0f,0f,1f))) {
            try { ThemeTextureModel.cropPlan(100,100,region);fail("Invalid crop accepted") }
            catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun frameStaysInsideThePhotoEvenAtItsCorners() {
        val image=IntSize(1000,500);val viewport=IntSize(400,250);val frame=IntSize(300,200)
        for(pan in listOf(Offset.Zero,Offset(9999f,9999f),Offset(-9999f,-9999f))) {
            val geometry=textureViewport(image,viewport,1f,pan,frame)
            val region=geometry.region(image,frame)
            assertTrue(region.left>=0 && region.top>=0 && region.right<=1 && region.bottom<=1)
            assertFalse(region.isEmpty)
            assertEquals(frame.width.toFloat(),region.width()*image.width*geometry.scale,.001f)
            assertEquals(frame.height.toFloat(),region.height()*image.height*geometry.scale,.001f)
        }
    }
    @Test fun fitCoverAndRepeatUseDifferentScalesWithoutDistortingTheSample() {
        assertEquals(4f,ThemeTextureModel.renderScale(100,50,400f,100f,"cover"),0f)
        assertEquals(2f,ThemeTextureModel.renderScale(100,50,400f,100f,"fit"),0f)
        assertEquals(1f,ThemeTextureModel.renderScale(100,50,400f,100f,"tile"),0f)
    }
    @Test fun solidColourRemovesOnlyTheRelevantTextureAndKeepsExistingColourCoupling() {
        val context=RuntimeEnvironment.getApplication()
        val original=SettingsManager.getKeyboardTheme(context,SettingsManager.KeyboardThemeTarget.HARDWARE).toKeyboardThemePreset("test")
        val next=original.withSurfaceTexture("normal_key",KeyboardThemeTexture(asset),true)
        assertEquals(next.textures["normal_key"],next.textures["suggestion"])
        assertTrue(next.withSurfaceTexture("normal_key",null,true).textures.isEmpty())
        assertEquals(original,original.withSurfaceTexture("text_icons",KeyboardThemeTexture(asset)))
    }
    @Test fun oneGlobalMaterialPreservesTextDividersAndOnlyUsesOneAsset() {
        val theme=SettingsManager.getKeyboardTheme(RuntimeEnvironment.getApplication(),SettingsManager.KeyboardThemeTarget.HARDWARE).toKeyboardThemePreset("Global")
        val updated=theme.withSurfaceTexture(ThemeTextureModel.ALL_SURFACES,KeyboardThemeTexture(asset))
        assertEquals(ThemeTextureModel.roles,updated.textures.keys)
        assertTrue(updated.textures.values.all{it.asset==asset && it.continuous && it.wholeKeyboard})
        assertEquals(theme.textAndIcons,updated.textAndIcons);assertEquals(theme.divider,updated.divider)
        assertEquals(updated.textures,ThemeTextureModel.decode(ThemeTextureModel.encode(updated.textures)))
        assertTrue(updated.withSurfaceTexture(ThemeTextureModel.ALL_SURFACES,null).textures.isEmpty())
        assertEquals(theme.normalKey,updated.normalKey)
    }

}
