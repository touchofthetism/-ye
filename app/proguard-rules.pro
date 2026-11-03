# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Firestore @IgnoreExtraProperties and @DocumentId data models for reflection serialization
-keep class com.example.data.model.** { *; }
-keepnames class com.example.data.model.** { *; }

# Keep Google ML Kit Face Detection & Vision internal JNI/reflection classes
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Keep Jetpack Credential Manager & Google Identity classes
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
