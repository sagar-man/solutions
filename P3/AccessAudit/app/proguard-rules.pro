# Room generates implementations reflectively at build time; nothing to keep.
# Shizuku's binder stubs and the AIDL-generated provider must survive shrinking.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# The appops collector resolves @SystemApi members by name. Shrinking cannot
# rename platform classes, but it can strip our reflective helpers' signatures.
-keepclassmembers class dev.accessaudit.app.collect.** { *; }
