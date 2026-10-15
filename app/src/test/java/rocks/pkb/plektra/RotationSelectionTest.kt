package rocks.pkb.plektra

import org.junit.Assert.*
import org.junit.Test

class RotationSelectionTest {
    @Test fun renamedMissingEntriesDoNotOccupyTheFirstSixRanks() {
        val names = listOf("Imperial", "Prato Marble", "Porphyry", "Pompeian Style", "Forest", "Plum", "Panthéon")
        assertEquals(listOf("Panthéon"), DailyThemeRotation.availableNames(names,
            listOf("Impérial", "Marbre de Prato", "Porphyre", "Style pompéien", "Forêt", "Prune", "Panthéon")))
    }
    @Test fun preserveOrderAndCanonicalizeCaseWithoutDuplicateSlots() {
        assertEquals(listOf("B", "A"), DailyThemeRotation.availableNames(listOf("b", "deleted", "A", "B"), listOf("A", "B")))
    }
    @Test fun emptyOrDeletedSelectionCanBeRebuiltFromFirstRank() {
        assertEquals(emptyList<String>(), DailyThemeRotation.availableNames(listOf("deleted"), listOf("A")))
        assertEquals(emptyList<String>(), DailyThemeRotation.availableNames(emptyList(), listOf("A")))
        assertEquals(listOf("A"), DailyThemeRotation.availableNames(listOf("A"), listOf("A")))
    }
}
