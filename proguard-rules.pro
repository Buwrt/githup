# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keep class com.githubclient.app.data.remote.model.** { *; }
-keep class retrofit2.** { *; }
-dontwarn okhttp3.**
-dontwarn retrofit2.**
