# Gateway models are (de)serialized by name; keep them intact.
-keep class com.banner.hintcontrol.gateway.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
# Navigation routes and stored settings use kotlinx.serialization.
-keep @kotlinx.serialization.Serializable class com.banner.hintcontrol.** { *; }
-keepclassmembers class com.banner.hintcontrol.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
