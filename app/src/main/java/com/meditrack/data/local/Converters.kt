package com.meditrack.data.local

import androidx.room.TypeConverter
import com.meditrack.data.local.entity.DosageForm
import com.meditrack.data.local.entity.DosageUnit
import com.meditrack.data.local.entity.DoseEventType
import com.meditrack.data.local.entity.DoseStatus
import com.meditrack.data.local.entity.FoodTiming
import com.meditrack.data.local.entity.MedicationColorTag
import com.meditrack.data.local.entity.MedicationIcon
import com.meditrack.data.local.entity.RepeatRuleType
import com.meditrack.data.local.entity.RepeatingRule

/**
 * Room type converters.
 *
 * Enums are stored by [name] rather than ordinal so that reordering a constant can never silently
 * reinterpret existing rows. Unknown names degrade to a safe default instead of crashing on an
 * older database touched by a newer build.
 */
class Converters {

    // ---------------------------------------------------------------- enums

    @TypeConverter fun iconToName(v: MedicationIcon): String = v.name
    @TypeConverter fun nameToIcon(v: String): MedicationIcon =
        runCatching { MedicationIcon.valueOf(v) }.getOrDefault(MedicationIcon.TABLET)

    @TypeConverter fun colorToName(v: MedicationColorTag): String = v.name
    @TypeConverter fun nameToColor(v: String): MedicationColorTag =
        runCatching { MedicationColorTag.valueOf(v) }.getOrDefault(MedicationColorTag.MINT)

    @TypeConverter fun formToName(v: DosageForm): String = v.name
    @TypeConverter fun nameToForm(v: String): DosageForm =
        runCatching { DosageForm.valueOf(v) }.getOrDefault(DosageForm.TABLET)

    @TypeConverter fun unitToName(v: DosageUnit): String = v.name
    @TypeConverter fun nameToUnit(v: String): DosageUnit =
        runCatching { DosageUnit.valueOf(v) }.getOrDefault(DosageUnit.TABLET)

    @TypeConverter fun timingToName(v: FoodTiming): String = v.name
    @TypeConverter fun nameToTiming(v: String): FoodTiming =
        runCatching { FoodTiming.valueOf(v) }.getOrDefault(FoodTiming.NONE)

    @TypeConverter fun statusToName(v: DoseStatus): String = v.name
    @TypeConverter fun nameToStatus(v: String): DoseStatus =
        runCatching { DoseStatus.valueOf(v) }.getOrDefault(DoseStatus.UPCOMING)

    @TypeConverter fun eventToName(v: DoseEventType): String = v.name
    @TypeConverter fun nameToEvent(v: String): DoseEventType =
        runCatching { DoseEventType.valueOf(v) }.getOrDefault(DoseEventType.EDIT)

    // ------------------------------------------------------- RepeatingRule

    /**
     * Compact, human readable encoding:
     * `TYPE|intervalDays|daysOfWeek|onDays|offDays|anchor|daysOfMonth`.
     * Example: `MONTHLY_DATES|1||1|0|19723|1,15`.
     *
     * A flat string keeps the schedules table inspectable with any SQLite browser while avoiding
     * seven extra columns that would only ever be meaningful for one rule type each.
     *
     * **Backward compatibility:** the trailing `daysOfMonth` field was added after the first
     * release. Rows written by that release have exactly six fields, and [stringToRule] treats a
     * missing seventh field as "no monthly days" rather than failing - which is why the field is
     * appended at the end instead of being inserted next to `daysOfWeek`.
     */
    @TypeConverter
    fun ruleToString(rule: RepeatingRule): String = buildString {
        append(rule.type.name); append('|')
        append(rule.intervalDays); append('|')
        append(rule.daysOfWeek.sorted().joinToString(",")); append('|')
        append(rule.cycleOnDays); append('|')
        append(rule.cycleOffDays); append('|')
        append(rule.anchorEpochDay); append('|')
        append(rule.daysOfMonth.sorted().joinToString(","))
    }

    @TypeConverter
    fun stringToRule(value: String): RepeatingRule {
        val parts = value.split('|')
        if (parts.size < 6) return RepeatingRule()
        val type = runCatching { RepeatRuleType.valueOf(parts[0]) }.getOrDefault(RepeatRuleType.DAILY)
        val weekDays = parts[2].split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..7 }
            .toSet()
        // Absent on rows written before monthly dates existed.
        val monthDays = parts.getOrNull(6)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it in 1..31 }
            ?.toSet()
            .orEmpty()
        return RepeatingRule(
            type = type,
            intervalDays = parts[1].toIntOrNull()?.coerceAtLeast(1) ?: 1,
            daysOfWeek = weekDays,
            daysOfMonth = monthDays,
            cycleOnDays = parts[3].toIntOrNull()?.coerceAtLeast(1) ?: 1,
            cycleOffDays = parts[4].toIntOrNull()?.coerceAtLeast(0) ?: 0,
            anchorEpochDay = parts[5].toLongOrNull() ?: 0L,
        )
    }

}
