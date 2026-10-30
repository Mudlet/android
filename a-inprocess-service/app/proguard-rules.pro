# Qt's C++ looks its Java classes, methods and fields up by name through JNI
-keep class org.qtproject.qt.android.** { *; }
# Called from libmudletproto via JNI (RegisterNatives and GetStaticMethodID)
-keep class org.mudlet.proto.a.NativeBridge { *; }
