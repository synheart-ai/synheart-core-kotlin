# Add project specific ProGuard rules here.
# Keep data classes
-keep class com.synheart.hsi.models.** { *; }
-keepclassmembers class com.synheart.hsi.models.** {
    <fields>;
}

# Keep HSI classes
-keep class com.synheart.hsi.** { *; }

