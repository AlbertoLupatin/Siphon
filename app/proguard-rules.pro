# youtubedl-android: Jackson maps yt-dlp / GitHub JSON onto these classes reflectively.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn com.fasterxml.jackson.**
-dontwarn java.beans.**
-dontwarn org.w3c.dom.bootstrap.**
# commons-compress (used to unpack bundled python/ffmpeg) instantiates zip extra-field classes reflectively.
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
