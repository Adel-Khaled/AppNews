# Minification is disabled by default. If you enable it, keep serialization DTOs:
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.smartnews.data.remote.**$$serializer { *; }
-keepclassmembers class com.smartnews.data.remote.** { *** Companion; }
