#include "AndroidBridge.h"

#include "Engine.h"

#include <QCoreApplication>
#include <QMetaObject>

#include <jni.h>
#include <mutex>

namespace {
constexpr const char* kBridgeClass = "org/mudlet/proto/b/EngineBridge";

JavaVM* g_vm = nullptr;
jclass g_bridgeClass = nullptr;
jmethodID g_onLine = nullptr;
jmethodID g_onNotify = nullptr;
jmethodID g_onStatus = nullptr;

std::mutex g_engineMutex;
Engine* g_engine = nullptr;

JNIEnv* currentEnv()
{
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        // Qt's main thread is attached by Qt; this covers any other thread that reports.
        g_vm->AttachCurrentThread(&env, nullptr);
    }
    return env;
}

jstring toJString(JNIEnv* env, const QString& s)
{
    return env->NewString(reinterpret_cast<const jchar*>(s.utf16()), jsize(s.size()));
}

QString fromJString(JNIEnv* env, jstring s)
{
    if (!s) {
        return {};
    }
    const jsize len = env->GetStringLength(s);
    const jchar* chars = env->GetStringChars(s, nullptr);
    QString out(reinterpret_cast<const QChar*>(chars), len);
    env->ReleaseStringChars(s, chars);
    return out;
}

void clearException(JNIEnv* env)
{
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

// Runs fn on Qt's main thread against the engine; false when no engine is alive.
template<typename Fn>
bool postToEngine(Fn&& fn)
{
    std::lock_guard lock(g_engineMutex);
    if (!g_engine) {
        return false;
    }
    Engine* engine = g_engine;
    return QMetaObject::invokeMethod(engine, [engine, fn = std::forward<Fn>(fn)]() { fn(engine); }, Qt::QueuedConnection);
}

jboolean nativeConnect(JNIEnv* env, jclass, jstring host, jint port)
{
    const QString h = fromJString(env, host);
    return postToEngine([h, port](Engine* e) { e->connectTo(h, port); });
}

jboolean nativeSend(JNIEnv* env, jclass, jstring line)
{
    const QString l = fromJString(env, line);
    return postToEngine([l](Engine* e) { e->send(l); });
}

jboolean nativeDisconnect(JNIEnv*, jclass)
{
    return postToEngine([](Engine* e) { e->disconnectFromGame(); });
}

jboolean nativeRunLua(JNIEnv* env, jclass, jstring code)
{
    const QString c = fromJString(env, code);
    return postToEngine([c](Engine* e) { e->runLua(c); });
}

jboolean nativeSetViewWidthPx(JNIEnv*, jclass, jint px)
{
    return postToEngine([px](Engine* e) { e->setViewWidthPx(px); });
}

jboolean nativeQuit(JNIEnv*, jclass)
{
    return postToEngine([](Engine*) { QCoreApplication::quit(); });
}

jboolean nativeEngineAlive(JNIEnv*, jclass)
{
    std::lock_guard lock(g_engineMutex);
    return g_engine != nullptr;
}
} // namespace

void AndroidFrontend::line(const QString& text, const QList<ColorRun>& runs, bool isPrompt)
{
    JNIEnv* env = currentEnv();
    jstring jtext = toJString(env, text);
    jintArray jruns = env->NewIntArray(jsize(runs.size() * 3));
    QVarLengthArray<jint, 48> flat;
    for (const ColorRun& run : runs) {
        flat.append(run.start);
        flat.append(run.length);
        flat.append(run.fg);
    }
    env->SetIntArrayRegion(jruns, 0, jsize(flat.size()), flat.constData());
    env->CallStaticVoidMethod(g_bridgeClass, g_onLine, jtext, jruns, jboolean(isPrompt));
    clearException(env);
    env->DeleteLocalRef(jruns);
    env->DeleteLocalRef(jtext);
}

void AndroidFrontend::notify(const QString& title, const QString& text)
{
    JNIEnv* env = currentEnv();
    jstring jt = toJString(env, title);
    jstring jx = toJString(env, text);
    env->CallStaticVoidMethod(g_bridgeClass, g_onNotify, jt, jx);
    clearException(env);
    env->DeleteLocalRef(jx);
    env->DeleteLocalRef(jt);
}

void AndroidFrontend::status(const QString& key, const QString& value)
{
    JNIEnv* env = currentEnv();
    jstring jk = toJString(env, key);
    jstring jv = toJString(env, value);
    env->CallStaticVoidMethod(g_bridgeClass, g_onStatus, jk, jv);
    clearException(env);
    env->DeleteLocalRef(jv);
    env->DeleteLocalRef(jk);
}

void AndroidBridge::attach(Engine* engine)
{
    std::lock_guard lock(g_engineMutex);
    g_engine = engine;
}

void AndroidBridge::detach()
{
    std::lock_guard lock(g_engineMutex);
    g_engine = nullptr;
}

// QtLoader System.load()s this library from the Android UI thread, so FindClass sees the app's
// class loader here; it would not from Qt's thread.
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*)
{
    g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass local = env->FindClass(kBridgeClass);
    if (!local) {
        clearException(env);
        return JNI_ERR;
    }
    g_bridgeClass = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);

    g_onLine = env->GetStaticMethodID(g_bridgeClass, "onLine", "(Ljava/lang/String;[IZ)V");
    g_onNotify = env->GetStaticMethodID(g_bridgeClass, "onNotify", "(Ljava/lang/String;Ljava/lang/String;)V");
    g_onStatus = env->GetStaticMethodID(g_bridgeClass, "onStatus", "(Ljava/lang/String;Ljava/lang/String;)V");
    if (!g_onLine || !g_onNotify || !g_onStatus) {
        clearException(env);
        return JNI_ERR;
    }

    static const JNINativeMethod methods[] = {
            {"nativeConnect", "(Ljava/lang/String;I)Z", reinterpret_cast<void*>(nativeConnect)},
            {"nativeSend", "(Ljava/lang/String;)Z", reinterpret_cast<void*>(nativeSend)},
            {"nativeDisconnect", "()Z", reinterpret_cast<void*>(nativeDisconnect)},
            {"nativeRunLua", "(Ljava/lang/String;)Z", reinterpret_cast<void*>(nativeRunLua)},
            {"nativeSetViewWidthPx", "(I)Z", reinterpret_cast<void*>(nativeSetViewWidthPx)},
            {"nativeQuit", "()Z", reinterpret_cast<void*>(nativeQuit)},
            {"nativeEngineAlive", "()Z", reinterpret_cast<void*>(nativeEngineAlive)},
    };
    if (env->RegisterNatives(g_bridgeClass, methods, jint(std::size(methods))) != JNI_OK) {
        clearException(env);
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
