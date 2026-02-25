# Add project specific ProGuard rules here.

# Keep public API entry point
-keep class com.synheart.core.Synheart { public *; }

# Keep data models (serialized to/from JSON)
-keep class com.synheart.core.models.** { *; }
-keepclassmembers class com.synheart.core.models.** {
    <fields>;
}

# Keep public config classes
-keep class com.synheart.core.config.SynheartConfig { public *; }
-keep class com.synheart.core.config.SynheartFeature { public *; }

# Keep consent snapshot (accessed by consumers)
-keep class com.synheart.core.modules.interfaces.ConsentSnapshot { public *; }

# Keep JNA interface for native bridge
-keep class com.synheart.core.modules.runtime.RuntimeNative { *; }
-keepclassmembers class com.synheart.core.modules.runtime.RuntimeNative {
    <methods>;
}

