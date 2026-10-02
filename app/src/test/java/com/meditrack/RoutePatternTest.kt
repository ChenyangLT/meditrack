package com.meditrack

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the navigation route contract.
 *
 * This is the unit-testable half of the crash that shipped: the destination pattern and the
 * concrete navigation target must agree on the query-parameter name. If they drift apart again,
 * Navigation throws `IllegalArgumentException: Navigation destination ... cannot be found` at
 * runtime - here it fails at build time instead.
 */
class RoutePatternTest {

    @Test
    fun `editor route declares the argument as a placeholder`() {
        assertThat(Routes.MEDICATION_EDITOR_ROUTE)
            .isEqualTo("medication_editor?arg={arg}")
        // The placeholder syntax is what makes the route a *pattern* rather than a literal.
        assertThat(Routes.MEDICATION_EDITOR_ROUTE).contains("{${Routes.ARG_MEDICATION_ID}}")
    }

    @Test
    fun `concrete editor route fills the same parameter name the pattern declares`() {
        val concrete = Routes.medicationEditor()

        assertThat(concrete).isEqualTo("medication_editor?arg=0")
        // Derive the parameter name from the declared pattern and require the concrete route to
        // use it. This is the assertion that would have caught the original bug.
        val declaredName = Routes.MEDICATION_EDITOR_ROUTE
            .substringAfter('?')
            .substringBefore('=')
        val concreteName = concrete.substringAfter('?').substringBefore('=')
        assertThat(concreteName).isEqualTo(declaredName)
    }

    @Test
    fun `concrete editor route carries the requested id`() {
        assertThat(Routes.medicationEditor(42L)).isEqualTo("medication_editor?arg=42")
    }

    @Test
    fun `a negative id is clamped so it can never produce a malformed route`() {
        assertThat(Routes.medicationEditor(-5L)).isEqualTo("medication_editor?arg=0")
    }

    @Test
    fun `the view model reads the argument under the declared name`() {
        // The SavedStateHandle key must equal the query parameter name, otherwise the id silently
        // never arrives and the editor would treat every open as "create new".
        assertThat(com.meditrack.ui.medications.MedicationEditorViewModel.ARG_MEDICATION_ID)
            .isEqualTo(Routes.ARG_MEDICATION_ID)
    }

    @Test
    fun `top level routes are distinct`() {
        val routes = listOf(Routes.TODAY, Routes.MEDICATIONS, Routes.HISTORY, Routes.SETTINGS)
        assertThat(routes).containsNoDuplicates()
    }
}
