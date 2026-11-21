# The JNI entry points are looked up by name at runtime, so R8 must keep them.
-keepclasseswithmembernames,includedescriptorclasses class dev.tsdroid.core.Native {
    native <methods>;
}
