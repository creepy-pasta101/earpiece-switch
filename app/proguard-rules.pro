# R8 / ProGuard rules for the release build.
#
# This app has no reflective access anywhere: no Class.forName, no serialization
# framework, no dependency injection, no XML-declared View subclasses, no JNI.
# Every class R8 can reach is reachable statically, so no -keep rules are needed
# for the app's own model/state/UI classes, and no -dontwarn suppressions are
# required either. Anything else would only bloat the APK or hide real warnings.

# Keep source file names and line numbers in obfuscated stack traces, but strip the
# original file name so build paths do not leak into crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
