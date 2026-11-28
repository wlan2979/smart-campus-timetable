# 桃课 (Taoke · Smart Campus Timetable) ProGuard / R8 Rules
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations

# Kotlin Serialization 模型与 DTO（领域模型、同步接口、节假日、学校目录、浏览器帧提取）
-keep,includedescriptorclasses class com.campus.timetable.domain.model.** { *; }
-keep,includedescriptorclasses class com.campus.timetable.data.remote.** { *; }
-keep,includedescriptorclasses class com.campus.timetable.data.repository.**$*Dto* { *; }
-keep,includedescriptorclasses class com.campus.timetable.feature.importing.** { *; }
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class *$$serializer {
    static *** INSTANCE;
}

# Room 实体与数据库
-keep class com.campus.timetable.data.local.** { *; }

# Jetpack Glance 桌面小组件与系统广播接收器
-keep class com.campus.timetable.feature.widgets.** { *; }
-keep class com.campus.timetable.feature.alarm.** { *; }

# Apache POI / PDFBox / OkHttp / ML Kit 兼容忽略项
-dontwarn org.apache.poi.**
-dontwarn javax.xml.**
-dontwarn java.awt.**
-dontwarn org.bouncycastle.**
-dontwarn okhttp3.**
-dontwarn okio.**
