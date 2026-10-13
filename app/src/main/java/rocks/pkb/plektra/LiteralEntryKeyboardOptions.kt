package rocks.pkb.plektra

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PlatformImeOptions

/** *Preserve intentional spelling in dictionary and replacement editors.* */
val LiteralEntryKeyboardOptions = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    platformImeOptions = PlatformImeOptions(privateImeOptions = "pastiera.literal_entry")
)
