# R8 rules for the app's release build (consumerProguardFiles in build.gradle.kts): what Qt and the engine reach by
# name, which R8 cannot see. CI's emulator smoke test runs the minified build to check them (docs/ptb.md).

# Qt's Java side (Qt6Android*.jar, staged by scripts/build-apk.sh). Qt's C++ finds these classes, their methods and
# fields by name through JNI and registers its native methods on them, and Qt ships no R8 rules of its own; so all
# of it is kept as it is, which costs a few hundred KB of dex.
-keep class org.qtproject.qt.** { *; }
-keep interface org.qtproject.qt.** { *; }

# Implemented in libengine (native/enginehost/AndroidServiceSetup.cpp), bound by its JNI name
-keep class org.mudlet.android.engine.EngineNative {
    native <methods>;
}

# Called from C++ (native/enginehost/AndroidNotifier.cpp) with QJniObject::callStaticMethod, by class, name and
# signature
-keep class org.mudlet.android.engine.EngineNotifications {
    public static void postTell(android.content.Context, java.lang.String, java.lang.String);
}
