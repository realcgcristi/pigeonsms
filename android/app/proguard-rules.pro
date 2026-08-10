# lazysodium loads its native lib via JNA reflection; R8 can't see that usage and
# will strip or rename classes it thinks are unused, breaking the E2EE crypto path
# only in minified release builds (debug builds are unaffected either way).
-keep class com.goterl.lazysodium.** { *; }
-keepclassmembers class com.goterl.lazysodium.** { *; }
-dontwarn com.goterl.lazysodium.**

-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { public *; }
-dontwarn com.sun.jna.**
