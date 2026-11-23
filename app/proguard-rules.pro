# The JNI entry points are looked up by name at runtime, so R8 must keep them.
-keepclasseswithmembernames,includedescriptorclasses class dev.tsdroid.core.Native {
    native <methods>;
}

# rustls-platform-verifier is called from Rust through JNI, which R8 cannot see;
# without this it looks like dead code and is stripped from release builds.
-keep, includedescriptorclasses class org.rustls.platformverifier.** { *; }
