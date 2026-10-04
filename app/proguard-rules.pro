# MultiRoute Proguard / R8 Rules

# -------------------------------------------------------------
# 1. General & Attributes preservation
# -------------------------------------------------------------
-keepattributes LineNumberTable,SourceFile,Exceptions,Signature,Deprecated,*Annotation*,InnerClasses,EnclosingMethod

# -------------------------------------------------------------
# 2. Modern LibXposed API (v102) & Module Entry Points
# -------------------------------------------------------------
-keep class io.github.libxposed.api.** { *; }
-dontwarn io.github.libxposed.api.**

# Keep Hook entry point loaded via META-INF/xposed/java_init.list
-keep class com.multiroute.hook.MultiRouteModule {
    public <init>(...);
    public void onPackageLoaded(...);
    public void onSystemServerLoaded(...);
    <methods>;
}

# -------------------------------------------------------------
# 3. ContentProvider & Data Transfer
# -------------------------------------------------------------
-keep class com.multiroute.data.RouteConfigProvider { *; }

# -------------------------------------------------------------
# 4. Data Models & Kotlinx Serialization
# -------------------------------------------------------------
-keep class com.multiroute.model.** { *; }
-keepclassmembers class com.multiroute.model.** {
    <fields>;
    <methods>;
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * {
    *** Companion;
    *** $serializer;
}

# -------------------------------------------------------------
# 5. Core Components, Utilities & ViewModel
# -------------------------------------------------------------
-keep class com.multiroute.MainActivity { *; }
-keep class com.multiroute.ui.main.MainScreenViewModel { *; }

# -------------------------------------------------------------
# 6. Jetpack Compose
# -------------------------------------------------------------
-dontwarn androidx.compose.**
