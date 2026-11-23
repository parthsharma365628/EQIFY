# EQify release rules. R8 shrinks and optimises, but does not rename,
# so Sentry stack traces stay readable without uploading mapping files.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# Gson maps JSON onto these models by field name (Retrofit responses and the
# cached headphone index). Keep their fields and constructors.
-keep class com.example.eqify.GenreRequest { *; }
-keep class com.example.eqify.GenreResponse { *; }
-keep class com.example.eqify.StaticHeadphone* { *; }
-keep class com.example.eqify.LastFm* { *; }
-keep class com.example.eqify.Itunes* { *; }

# Gson generic types.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Retrofit 2.9 with suspend functions under R8 full mode.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface com.example.eqify.*ApiService { *; }
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
