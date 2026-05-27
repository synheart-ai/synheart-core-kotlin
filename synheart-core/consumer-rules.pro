# Public facade
-keep class ai.synheart.core.Synheart { *; }
-keep class ai.synheart.core.SynheartLogger { *; }
-keep class ai.synheart.core.SynheartDefaults { *; }

# Public config
-keep class ai.synheart.core.config.** { *; }

# Public models, artifacts, errors
-keep class ai.synheart.core.models.** { *; }
-keep class ai.synheart.core.artifacts.** { *; }
-keep class ai.synheart.core.storage.** { *; }

# Public module interfaces + consent types
-keep class ai.synheart.core.modules.interfaces.** { *; }
-keep class ai.synheart.core.modules.consent.ConsentProfile { *; }
-keep class ai.synheart.core.modules.consent.ConsentTier { *; }
-keep class ai.synheart.core.modules.consent.ConsentToken { *; }

# JNA — reflection-heavy
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.* { *; }
-keepclassmembers class * implements com.sun.jna.Callback {
    *;
}

# Serialization + enums in public models
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class ai.synheart.core.models.** {
    public static *** Companion;
    *** serializer(...);
}
-keepclassmembers enum ai.synheart.core.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
