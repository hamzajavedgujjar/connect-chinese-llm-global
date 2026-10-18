# Release shrinking is deliberately off until a device-tested release exists.
# Keep JWT reflection metadata if R8 is enabled in a future release.
-keepattributes Signature,*Annotation*
-keep class com.nimbusds.** { *; }
