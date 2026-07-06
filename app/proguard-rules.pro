# NanoHTTPD
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.smsapi.app.SmsRequest { *; }
-keep class com.smsapi.app.BulkSmsRequest { *; }
-keep class com.smsapi.app.SmsResponse { *; }
-keep class com.smsapi.app.BulkSmsResponse { *; }
-keep class com.smsapi.app.StatusResponse { *; }
