package rocks.pkb.plektra

import android.content.res.Configuration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class MenuLocalizationRegressionTest {
    private fun context(language: String) = RuntimeEnvironment.getApplication().let { app ->
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) })
    }
    @Test fun menuTitlesAreTranslatedInAllSupportedLanguages() {
        val titles=mapOf("fr" to "Thème du clavier", "de" to "Tastaturdesign", "es" to "Tema del teclado", "it" to "Tema della tastiera",
            "el" to "Θέμα πληκτρολογίου", "hy" to "Ստեղնաշարի թեմա", "pl" to "Motyw klawiatury", "ru" to "Тема клавиатуры",
            "uk" to "Тема клавіатури", "vi" to "Giao diện bàn phím")
        titles.forEach { (lang,title) ->
            assertEquals(title,context(lang).getString(R.string.keyboard_theme_title))
            assertNotEquals("Dictionaries",context(lang).getString(R.string.dictionaries_title))
        }
    }
    @Test fun correctionDialogAndWordCountUseFrenchResources() {
        assertEquals("10 mots",context("fr").getString(R.string.installed_dictionaries_word_count,10))
        assertEquals("Ajouter le remplacement au dictionnaire personnel",context("fr").getString(R.string.auto_correct_add_replacement_to_dictionary))
    }
    @Test fun rotationSaveAndAltEnterUseFrenchResources() {
        assertEquals("Enregistrer",context("fr").getString(R.string.keyboard_theme_rotation_save))
        assertEquals("Alt + Entrée",context("fr").getString(R.string.app_enter_behaviour_additional_send_shortcut_alt_enter))
    }
    @Test fun translatedSearchMessagesPreserveTheirFormattingArgument() {
        listOf("en","fr","de","es","it","el","hy","pl","ru","uk","vi").forEach { lang ->
            val text=context(lang).getString(R.string.menu_literal_94982e0edb,"abcXYZ123")
            assertTrue(text.contains("abcXYZ123"))
            assertFalse(text.contains("__PH_"))
            assertFalse(text.contains("ZXQ"))
        }
    }
}
