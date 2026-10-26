# kotlinx.serialization, Room, Hilt and Compose ship their own consumer R8 rules.
# Keep the settings DTOs' generated serializers explicitly as a belt-and-braces measure,
# because a stripped serializer would silently reset the user's setup to defaults.
-keep,includedescriptorclasses class com.arrivelog.data.settings.**$$serializer { *; }
-keepclassmembers class com.arrivelog.data.settings.** {
    *** Companion;
}
