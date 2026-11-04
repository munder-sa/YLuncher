# Preserve the existing JSON contract, including Slot serial names and enum names.
# This also retains generated $$serializer classes, Companion objects and members.
-keep class com.gt8d.launcher.config.** { *; }
-keep interface com.gt8d.launcher.config.** { *; }

# Metadata used by serialization and its serializer/Companion lookup paths.
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault
