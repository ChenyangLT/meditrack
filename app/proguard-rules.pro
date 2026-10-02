# Room / Hilt / Compose ship their own consumer rules; the entries below cover
# the reflective bits Glance and our JSON exporter rely on.

# Glance receivers are referenced from the manifest only.
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }

# kotlinx.serialization-free exporter uses hand written JSON; keep model names
# for readable backups even after obfuscation.
-keepnames class com.meditrack.data.backup.** { *; }

# Keep enum valueOf used by the JSON importer.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
