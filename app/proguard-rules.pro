# pdfiumandroid and PdfBox-Android ship consumer rules for their JNI and reflection needs.
# Keep pdfium's JNI-facing classes explicitly as well: native code looks them up by name.
-keep class io.legere.pdfiumandroid.** { *; }

-dontwarn org.bouncycastle.**
-dontwarn com.gemalto.jp2.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn com.google.j2objc.annotations.**
