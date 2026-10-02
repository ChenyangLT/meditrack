package com.meditrack.data.local.entity

/**
 * Purely descriptive enums. They are persisted by name (see TypeConverters) so that renaming a
 * constant would be a schema change - always append, never rename.
 */

/** Icon family shown for a medication. Maps to a Material Symbol in the UI layer. */
enum class MedicationIcon(val label: String) {
    TABLET("片剂"),
    CAPSULE("胶囊"),
    BOTTLE("药瓶"),
    LIQUID("液体"),
    DROPPER("滴剂"),
    SPRAY("喷雾"),
    INJECTION("注射"),
    TUBE("软膏"),
    SACHET("冲剂"),
    HEART("心脏"),
    LUNGS("呼吸"),
    STOMACH("肠胃"),
    SLEEP("助眠"),
    VITAMIN("维生素"),
    OTHER("其他"),
}

/**
 * Colour tag used to identify a medication at a glance in the today list and the widget.
 * The ARGB value lives in [MedicationColors] so this enum stays free of Android dependencies.
 */
enum class MedicationColorTag(val label: String) {
    MINT("薄荷绿"),
    TEAL("青蓝"),
    LAVENDER("淡紫"),
    BLUE("天蓝"),
    AMBER("琥珀"),
    CORAL("珊瑚"),
    ROSE("玫瑰"),
    SLATE("石板"),
}

/** Physical form of the medication. Drives the unit picker and the default quantity step. */
enum class DosageForm(val label: String, val defaultUnit: DosageUnit) {
    TABLET("片剂", DosageUnit.TABLET),
    CAPSULE("胶囊", DosageUnit.CAPSULE),
    LIQUID("液体", DosageUnit.MILLILITER),
    SPRAY("喷雾", DosageUnit.SPRAY),
    DROP("滴剂", DosageUnit.DROP),
    INJECTION("注射", DosageUnit.UNIT),
    SACHET("冲剂", DosageUnit.SACHET),
    OINTMENT("外用", DosageUnit.UNIT),
    OTHER("其他", DosageUnit.UNIT),
}

/** Counting unit for a dose. [allowsFraction] enables the +/- 0.5 stepper for liquids. */
enum class DosageUnit(val label: String, val allowsFraction: Boolean) {
    TABLET("片", false),
    CAPSULE("粒", false),
    MILLILITER("ml", true),
    SPRAY("喷", false),
    DROP("滴", false),
    SACHET("袋", false),
    UNIT("单位", false),
}

/** When the medication should be taken relative to food / sleep. */
enum class FoodTiming(val label: String, val shortLabel: String) {
    NONE("不限", ""),
    BEFORE_MEAL("饭前", "饭前"),
    AFTER_MEAL("饭后", "饭后"),
    WITH_MEAL("随餐", "随餐"),
    BEDTIME("睡前", "睡前"),
    MORNING_EMPTY("晨起空腹", "空腹"),
}

/** How a schedule repeats. */
enum class RepeatRuleType(val label: String) {
    /** Every single day. */
    DAILY("每天"),

    /** Every other day - shorthand for [EVERY_N_DAYS] with interval 2. */
    EVERY_OTHER_DAY("隔天"),

    /** Specific days of the week, e.g. Mon/Wed/Fri. */
    WEEKLY("每周指定"),

    /** Every N days counted from [RepeatRule.anchorEpochDay]. */
    EVERY_N_DAYS("每 N 天"),

    /** Take for X days, then pause for Y days, repeating. */
    CYCLE("吃 X 天停 Y 天"),

    /**
     * Specific days of the month, e.g. "每月 1 号和 15 号".
     *
     * A day that does not exist in a short month (31 in February) fires on that month's **last**
     * day instead, so a monthly prescription is never silently skipped - see
     * [RepeatingRule.matches].
     */
    MONTHLY_DATES("每月几号"),
}

/**
 * Status of a single planned dose. The lifecycle is:
 *
 *   UPCOMING --(time passes)--> DUE --(amount starts)--> PARTIAL --(amount reached)--> TAKEN
 *                                 |                        |
 *                                 +--> SKIPPED             +--> (amount back to 0) --> DUE
 *
 * MISSED and TAKEN are only ever written once a dose actually becomes actionable, so a dose that
 * is merely in the past but has not been swept yet can still be edited.
 */
enum class DoseStatus(val label: String) {
    UPCOMING("未到时间"),
    DUE("待服用"),
    PARTIAL("部分服用"),
    TAKEN("已服用"),
    SKIPPED("已跳过"),

    /**
     * The scheduled time passed and nothing was recorded.
     *
     * Labelled 未服药 rather than 漏服 because that is what the user sees and understands: the
     * slot was not taken. Statistics still count it as a miss.
     */
    MISSED("未服药");

    val isResolved: Boolean get() = this == TAKEN || this == SKIPPED
    val isActionable: Boolean get() = this == DUE || this == PARTIAL
}
