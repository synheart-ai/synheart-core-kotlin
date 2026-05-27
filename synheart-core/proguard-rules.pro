# Add project specific ProGuard rules here.

# Keep public API entry point
-keep class ai.synheart.core.Synheart { public *; }

# Keep data models (serialized to/from JSON)
-keep class ai.synheart.core.models.** { *; }
-keepclassmembers class ai.synheart.core.models.** {
    <fields>;
}

# Keep public config classes
-keep class ai.synheart.core.config.SynheartConfig { public *; }
-keep class ai.synheart.core.config.SynheartFeature { public *; }

# Keep consent snapshot (accessed by consumers)
-keep class ai.synheart.core.modules.interfaces.ConsentSnapshot { public *; }

# Keep JNA interface for native bridge
-keep class ai.synheart.core.modules.runtime.RuntimeNative { *; }
-keepclassmembers class ai.synheart.core.modules.runtime.RuntimeNative {
    <methods>;
}

