# Starmap ProGuard rules.
# Minification is disabled by default in app/build.gradle.kts; these rules are
# here so you can safely flip isMinifyEnabled = true later.

# Keep data-holder field names used by reflection-free JSON parsing (org.json).
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
