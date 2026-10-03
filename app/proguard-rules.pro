# Room / Hilt / Compose ship their own consumer rules; the entries below cover
# the reflective bits Glance and our JSON exporter rely on.

# Glance receivers are referenced from the manifest only.
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }

# ---------------------------------------------------------------------------- Gson (reflection)
#
# Every JSON DTO in this app is mapped by Gson through reflection, which means R8 can break it silently:
# it strips @SerializedName (nothing references an annotation at runtime) and renames fields, after
# which fromJson() maps nothing, returns an object full of nulls, and the caller sees a *parse* failure
# with no error anywhere. That is exactly what shipped in 1.8.0: the update check reported
# "检查失败：请确认网络可用" on release builds only, because the release dex had no "tag_name" string at
# all, while the same code worked in debug.
#
# Keep the annotations AND both DTO packages.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature

# Backup DTOs: kept outright so exported JSON keys stay stable and readable.
-keep class com.meditrack.data.backup.** { *; }

# Update DTOs (GitHubRelease / GitHubAsset / VersionManifest). Field names differ from JSON keys here
# ("tagName" vs "tag_name"), so the annotations are load-bearing.
-keep class com.meditrack.data.update.** { *; }

# Keep enum valueOf used by the JSON importer.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
