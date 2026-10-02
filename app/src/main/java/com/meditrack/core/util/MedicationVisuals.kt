package com.meditrack.core.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Sanitizer
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Science
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Bloodtype
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material.icons.outlined.LocalDrink
import androidx.compose.material.icons.outlined.LocalPharmacy
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material.icons.outlined.Sanitizer
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Vaccines
import androidx.compose.material.icons.outlined.WaterDrop
import com.meditrack.data.local.entity.DosageForm
import com.meditrack.data.local.entity.MedicationColorTag
import com.meditrack.data.local.entity.MedicationIcon

/**
 * Maps the storage enums onto the visual vocabulary.
 *
 * Kept out of the entity classes so the data layer stays free of Compose, and out of the
 * composables so the same icon and colour are used by the today list, the medication list, the
 * history calendar and the widget preview.
 *
 * The icon set deliberately follows one rule: **outlined for "not yet taken", filled for
 * "resolved"**. A user can therefore read the state of a list from the shapes alone, which is the
 * accessible fallback for the red/green colour pair.
 */
object MedicationVisuals {

    /** Medication family to icon. Filled variant = taken/resolved. */
    fun icon(icon: MedicationIcon, filled: Boolean = true): ImageVector = when (icon) {
        MedicationIcon.TABLET -> if (filled) Icons.Filled.Medication else Icons.Outlined.Medication
        MedicationIcon.CAPSULE -> if (filled) Icons.Filled.Medication else Icons.Outlined.Medication
        MedicationIcon.BOTTLE -> if (filled) Icons.Filled.LocalPharmacy else Icons.Outlined.LocalPharmacy
        MedicationIcon.LIQUID -> if (filled) Icons.Filled.LocalDrink else Icons.Outlined.LocalDrink
        MedicationIcon.DROPPER -> if (filled) Icons.Filled.Colorize else Icons.Outlined.Colorize
        MedicationIcon.SPRAY -> if (filled) Icons.Filled.Sanitizer else Icons.Outlined.Sanitizer
        MedicationIcon.INJECTION -> if (filled) Icons.Filled.Vaccines else Icons.Outlined.Vaccines
        MedicationIcon.TUBE -> if (filled) Icons.Filled.Healing else Icons.Outlined.Healing
        MedicationIcon.SACHET -> if (filled) Icons.Filled.Grain else Icons.Outlined.Science
        MedicationIcon.HEART -> if (filled) Icons.Filled.Favorite else Icons.Outlined.Favorite
        MedicationIcon.LUNGS -> if (filled) Icons.Filled.Air else Icons.Outlined.Air
        MedicationIcon.STOMACH -> if (filled) Icons.Filled.Bloodtype else Icons.Outlined.Bloodtype
        MedicationIcon.SLEEP -> if (filled) Icons.Filled.Nightlight else Icons.Outlined.Nightlight
        MedicationIcon.VITAMIN -> if (filled) Icons.Filled.Spa else Icons.Outlined.Spa
        MedicationIcon.OTHER -> if (filled) Icons.Filled.BubbleChart else Icons.Outlined.Circle
    }

    /** Suggested icon for a dosage form; used when the user changes the form in the editor. */
    fun iconFor(form: DosageForm): MedicationIcon = when (form) {
        DosageForm.TABLET -> MedicationIcon.TABLET
        DosageForm.CAPSULE -> MedicationIcon.CAPSULE
        DosageForm.LIQUID -> MedicationIcon.LIQUID
        DosageForm.SPRAY -> MedicationIcon.SPRAY
        DosageForm.DROP -> MedicationIcon.DROPPER
        DosageForm.INJECTION -> MedicationIcon.INJECTION
        DosageForm.SACHET -> MedicationIcon.SACHET
        DosageForm.OINTMENT -> MedicationIcon.TUBE
        DosageForm.OTHER -> MedicationIcon.OTHER
    }

    /**
     * ARGB value of a colour tag.
     *
     * These are *identity* colours (which medication is which), not status colours, so they are
     * chosen to stay distinct from the semantic red/orange/green used by the status chips. A
     * medication tagged "珊瑚" must never be mistaken for a missed dose.
     */
    fun color(tag: MedicationColorTag): Color = when (tag) {
        MedicationColorTag.MINT -> Color(0xFF3DBFA0)
        MedicationColorTag.TEAL -> Color(0xFF2E9BB5)
        MedicationColorTag.LAVENDER -> Color(0xFF8E86D6)
        MedicationColorTag.BLUE -> Color(0xFF4A7FD4)
        MedicationColorTag.AMBER -> Color(0xFFD9A23C)
        MedicationColorTag.CORAL -> Color(0xFFE4796A)
        MedicationColorTag.ROSE -> Color(0xFFD4709B)
        MedicationColorTag.SLATE -> Color(0xFF7A8794)
    }

    /** A very light tint of the tag colour, used as the card's icon background. */
    fun container(tag: MedicationColorTag): Color = color(tag).copy(alpha = 0.16f)

    /**
     * Packed ARGB as an unsigned 32-bit value in a [Long].
     *
     * Compose stores colours as a 32-bit float in `Color.value`, so the ARGB integer used by the
     * database, the CSV export and the XML resources has to be reconstructed from the sRGB
     * components rather than read out of the float.
     */
    fun argb(tag: MedicationColorTag): Long {
        val c = color(tag)
        val a = (c.alpha * 255f + 0.5f).toLong()
        val r = (c.red * 255f + 0.5f).toLong()
        val g = (c.green * 255f + 0.5f).toLong()
        val b = (c.blue * 255f + 0.5f).toLong()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/** Extension so call sites read naturally: `tag.color`. */
val MedicationColorTag.color: Color get() = MedicationVisuals.color(this)

/** Extension for the icon: `medication.icon.vector(filled = true)`. */
fun MedicationIcon.vector(filled: Boolean = true): ImageVector = MedicationVisuals.icon(this, filled)
