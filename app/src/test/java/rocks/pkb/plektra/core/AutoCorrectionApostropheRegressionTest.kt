package rocks.pkb.plektra.core

import android.content.Context
import rocks.pkb.plektra.SettingsManager
import rocks.pkb.plektra.inputmethod.AutoCorrector
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutoCorrectionApostropheRegressionTest {
    @Test fun defaultFrenchEtatRuleWorksWithoutAPersonalReplacementDespiteExtendedSpelling() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.saveCustomAutoCorrections(context, "fr", emptyMap())
        SettingsManager.setAutoCorrectEnabledLanguages(context, setOf("fr"))
        AutoCorrector.loadCorrections(context.assets, context)
        AutoCorrector.clearRejectedWords()
        for ((input, expected) in listOf("etat" to "état", "Etat" to "État",
            "l'etat" to "l'état", "L'etat" to "L'état", "l’etat" to "l’état")) {
            assertEquals(input, input to expected,
                AutoCorrector.processText("je parle de $input ", "fr", context, isKnownWord = { true }))
        }
        assertNull(AutoCorrector.processText("monpetitloup ", "fr", context, isKnownWord = { true }))
    }

    @Test fun personalAccentRulesPreserveCaseAndRecognizeBothApostrophesDespiteKnownWords() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.saveCustomAutoCorrections(context, "fr", mapOf("c'etait" to "c'était", "l'etat" to "l'état"))
        SettingsManager.setAutoCorrectEnabledLanguages(context, setOf("fr"))
        AutoCorrector.loadCorrections(context.assets, context)
        AutoCorrector.clearRejectedWords()
        for ((input, expected) in listOf("c'etait" to "c'était", "C'etait" to "C'était",
            "l'etat" to "l'état", "L'etat" to "L'état", "c’etait" to "c’était", "C’etait" to "C’était",
            "l’etat" to "l’état", "L’etat" to "L’état")) {
            for (prefix in listOf("", "je parle de ")) {
                val result = AutoCorrector.processText(prefix + input, "fr", context, isKnownWord = { true })
                assertEquals("$prefix$input", input to expected, result)
            }
        }
    }

    @Test fun correctionOnTheSameSpaceDelimiterKeepsSurroundingSentence() {
        val context = RuntimeEnvironment.getApplication()
        SettingsManager.saveCustomAutoCorrections(context, "fr", mapOf("c'etait" to "c'était", "l'etat" to "l'état"))
        SettingsManager.setAutoCorrectEnabledLanguages(context, setOf("fr"))
        AutoCorrector.loadCorrections(context.assets, context)
        AutoCorrector.clearRejectedWords()
        for (input in listOf("c'etait", "C'etait", "l'etat", "L'etat", "c’etait", "C’etait", "l’etat", "L’etat")) {
            val connection = ApostropheInputConnection(context, "je dis $input")
            val handled = AutoCorrectionManager(context).handleBoundaryKey(android.view.KeyEvent.KEYCODE_SPACE,
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_SPACE),
                connection, true, true, {}, isKnownWord = { true })
            assertTrue(input, handled)
            assertEquals("je dis ${input.replace("etait", "était").replace("etat", "état")} ", connection.editable.toString())
        }
    }
    private class ApostropheInputConnection(context: Context, initial: String) : android.view.inputmethod.BaseInputConnection(android.view.View(context), true) {
        init { val text = requireNotNull(editable); text.append(initial); android.text.Selection.setSelection(text, text.length) }
    }
}
