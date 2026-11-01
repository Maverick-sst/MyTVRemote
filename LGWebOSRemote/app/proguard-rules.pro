# OkHttp ships consumer rules, but keep the optional platform backends quiet.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# The app talks to the TV with org.json, which is part of the Android platform.
-keep class org.json.** { *; }
