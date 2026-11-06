# Keep names readable in crash reports; optimisation and shrinking stay on.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class com.galeria.app.** { *; }
-dontwarn org.slf4j.**

# DataStore Preferences stores data with protobuf-lite, which reads fields by reflection.
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite { <fields>; }
-keep class androidx.datastore.preferences.** { *; }
