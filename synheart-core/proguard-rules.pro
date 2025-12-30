# Add project specific ProGuard rules here.
# Keep data classes
-keep class com.synheart.core.models.** { *; }
-keepclassmembers class com.synheart.core.models.** {
    <fields>;
}

# Keep public SDK classes
-keep class com.synheart.core.** { *; }

