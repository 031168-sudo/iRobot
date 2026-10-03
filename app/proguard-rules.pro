# Keep Paho classes used via reflection / callbacks.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
