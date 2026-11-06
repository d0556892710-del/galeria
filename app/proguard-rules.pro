# Keep names readable in crash reports; optimisation and shrinking stay on.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class com.galeria.app.** { *; }
-dontwarn org.slf4j.**
