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

# Firestore maps model classes by reflection at runtime and reads these annotations through
# Field/Method.getAnnotation(). R8 full mode drops an annotation whenever its annotation class is
# unreferenced by code, which silently breaks @PropertyName("isActive") (every profile reads back
# active), @DocumentId (ids read back empty) and @ServerTimestamp. Keep the annotation classes and
# the runtime annotation attributes explicitly.
-keep @interface com.google.firebase.firestore.PropertyName
-keep @interface com.google.firebase.firestore.DocumentId
-keep @interface com.google.firebase.firestore.ServerTimestamp
-keep @interface com.google.firebase.firestore.IgnoreExtraProperties
-keep @interface com.google.firebase.firestore.Exclude
-keep @interface com.google.firebase.firestore.ThrowOnExtraProperties
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class com.example.data.model.** {
  @com.google.firebase.firestore.PropertyName <methods>;
  @com.google.firebase.firestore.PropertyName <fields>;
  @com.google.firebase.firestore.DocumentId <methods>;
  @com.google.firebase.firestore.DocumentId <fields>;
  @com.google.firebase.firestore.ServerTimestamp <methods>;
  @com.google.firebase.firestore.ServerTimestamp <fields>;
}
