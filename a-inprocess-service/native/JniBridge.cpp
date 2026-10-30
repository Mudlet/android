#include "JniBridge.h"

#include "Engine.h"

#include <QtCore/private/qjnihelpers_p.h>

#include <QMetaObject>

#include <android/log.h>
#include <jni.h>

#include <chrono>
#include <condition_variable>
#include <ctime>
#include <functional>
#include <mutex>
#include <vector>

namespace {

constexpr const char* cBridgeClass = "org/mudlet/proto/a/NativeBridge";

JavaVM* gVm = nullptr;
jclass gBridgeClass = nullptr;
jmethodID gOnLine = nullptr;
jmethodID gOnNotify = nullptr;
jmethodID gOnStatus = nullptr;

std::mutex gEngineMutex;
Engine* gEngine = nullptr;
std::vector<std::function<void(Engine*)>> gPending;

std::mutex gReadyMutex;
std::condition_variable gReadyCv;
bool gServiceReady = false;

class NullBindListener : public QtAndroidPrivate::OnBindListener
{
public:
    jobject onBind(jobject) override { return nullptr; }
};
NullBindListener gBindListener;

JNIEnv* env()
{
    JNIEnv* e = nullptr;
    if (gVm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_6) != JNI_OK) {
        // Only threads Qt or Java created reach the frontend, but attach defensively
        gVm->AttachCurrentThread(&e, nullptr);
    }
    return e;
}

jstring toJ(JNIEnv* e, const QString& s)
{
    return e->NewString(reinterpret_cast<const jchar*>(s.utf16()), static_cast<jsize>(s.size()));
}

QString fromJ(JNIEnv* e, jstring s)
{
    if (!s) {
        return {};
    }
    const jsize len = e->GetStringLength(s);
    const jchar* chars = e->GetStringChars(s, nullptr);
    QString out(reinterpret_cast<const QChar*>(chars), len);
    e->ReleaseStringChars(s, chars);
    return out;
}

void checkException(JNIEnv* e, const char* where)
{
    if (e->ExceptionCheck()) {
        __android_log_print(ANDROID_LOG_ERROR, "MudletProto", "Java exception in %s", where);
        e->ExceptionDescribe();
        e->ExceptionClear();
    }
}

// Kotlin -> C++: every call is queued onto Qt's thread; none touches the engine directly.
void post(std::function<void(Engine*)> fn)
{
    std::lock_guard lock(gEngineMutex);
    if (!gEngine) {
        gPending.push_back(std::move(fn));
        return;
    }
    Engine* engine = gEngine;
    QMetaObject::invokeMethod(engine, [engine, fn = std::move(fn)]() { fn(engine); }, Qt::QueuedConnection);
}

void jServiceReady(JNIEnv*, jclass)
{
    {
        std::lock_guard lock(gReadyMutex);
        gServiceReady = true;
    }
    gReadyCv.notify_all();
}

jboolean jEngineRunning(JNIEnv*, jclass)
{
    std::lock_guard lock(gEngineMutex);
    return gEngine ? JNI_TRUE : JNI_FALSE;
}

void jConnect(JNIEnv* e, jclass, jstring host, jint port)
{
    post([h = fromJ(e, host), port](Engine* engine) { engine->connectTo(h, port); });
}

void jSend(JNIEnv* e, jclass, jstring line)
{
    post([l = fromJ(e, line)](Engine* engine) { engine->send(l); });
}

void jDisconnect(JNIEnv*, jclass)
{
    post([](Engine* engine) { engine->disconnectFromGame(); });
}

void jRunLua(JNIEnv* e, jclass, jstring code)
{
    post([c = fromJ(e, code)](Engine* engine) { engine->runLua(c); });
}

void jSetViewWidthPx(JNIEnv*, jclass, jint px)
{
    post([px](Engine* engine) { engine->setViewWidthPx(px); });
}

const JNINativeMethod cMethods[] = {
    {"nativeServiceReady", "()V", reinterpret_cast<void*>(jServiceReady)},
    {"nativeEngineRunning", "()Z", reinterpret_cast<void*>(jEngineRunning)},
    {"nativeConnect", "(Ljava/lang/String;I)V", reinterpret_cast<void*>(jConnect)},
    {"nativeSend", "(Ljava/lang/String;)V", reinterpret_cast<void*>(jSend)},
    {"nativeDisconnect", "()V", reinterpret_cast<void*>(jDisconnect)},
    {"nativeRunLua", "(Ljava/lang/String;)V", reinterpret_cast<void*>(jRunLua)},
    {"nativeSetViewWidthPx", "(I)V", reinterpret_cast<void*>(jSetViewWidthPx)},
};

} // namespace

// Runs when QtLoader System.load()s this library from QtServiceBase.onCreate(). Qt's own
// JNI_OnLoad lives in libQt6Core and the platform plugin and is invoked for those libraries
// separately, so there is nothing to chain to. FindClass works here because the calling
// class (QtLoader) was loaded by the app's class loader.
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*)
{
    gVm = vm;
    JNIEnv* e = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass local = e->FindClass(cBridgeClass);
    if (!local) {
        checkException(e, "FindClass NativeBridge");
        return JNI_ERR;
    }
    gBridgeClass = static_cast<jclass>(e->NewGlobalRef(local));
    e->DeleteLocalRef(local);
    if (e->RegisterNatives(gBridgeClass, cMethods, sizeof(cMethods) / sizeof(cMethods[0])) != JNI_OK) {
        checkException(e, "RegisterNatives");
        return JNI_ERR;
    }
    gOnLine = e->GetStaticMethodID(gBridgeClass, "onLine", "(Ljava/lang/String;[IZ)V");
    gOnNotify = e->GetStaticMethodID(gBridgeClass, "onNotify", "(Ljava/lang/String;Ljava/lang/String;)V");
    gOnStatus = e->GetStaticMethodID(gBridgeClass, "onStatus", "(Ljava/lang/String;Ljava/lang/String;)V");
    if (!gOnLine || !gOnNotify || !gOnStatus) {
        checkException(e, "GetStaticMethodID");
        return JNI_ERR;
    }
    __android_log_print(ANDROID_LOG_INFO, "MudletProto", "JNI_OnLoad: natives registered");
    return JNI_VERSION_1_6;
}

namespace JniBridge {

void releaseQtServiceSetup()
{
    // waitForServiceSetup() acquires a semaphore that setOnBindListener() releases only when
    // the locker count drops to zero; QAndroidService takes the lock in its constructor.
    QtAndroidPrivate::acuqireServiceSetup(0);
    QtAndroidPrivate::setOnBindListener(&gBindListener);
}

bool waitForServiceReady(int timeoutMs)
{
    std::unique_lock lock(gReadyMutex);
    return gReadyCv.wait_for(lock, std::chrono::milliseconds(timeoutMs), [] { return gServiceReady; });
}

void setEngine(Engine* engine)
{
    std::lock_guard lock(gEngineMutex);
    gEngine = engine;
    if (!engine) {
        return;
    }
    for (auto& fn : gPending) {
        QMetaObject::invokeMethod(engine, [engine, fn = std::move(fn)]() { fn(engine); }, Qt::QueuedConnection);
    }
    gPending.clear();
}

qint64 bootTimeMs()
{
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return static_cast<qint64>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

void JniFrontend::line(const QString& text, const QList<ColourRun>& runs, bool isPrompt)
{
    JNIEnv* e = env();
    jstring jText = toJ(e, text);
    const auto n = static_cast<jsize>(runs.size() * 3);
    jintArray jRuns = e->NewIntArray(n);
    std::vector<jint> flat;
    flat.reserve(static_cast<size_t>(n));
    for (const ColourRun& r : runs) {
        flat.push_back(r.start);
        flat.push_back(r.length);
        flat.push_back(r.fg);
    }
    e->SetIntArrayRegion(jRuns, 0, n, flat.data());
    e->CallStaticVoidMethod(gBridgeClass, gOnLine, jText, jRuns, isPrompt ? JNI_TRUE : JNI_FALSE);
    checkException(e, "onLine");
    e->DeleteLocalRef(jRuns);
    e->DeleteLocalRef(jText);
}

void JniFrontend::notify(const QString& title, const QString& text)
{
    JNIEnv* e = env();
    jstring a = toJ(e, title);
    jstring b = toJ(e, text);
    e->CallStaticVoidMethod(gBridgeClass, gOnNotify, a, b);
    checkException(e, "onNotify");
    e->DeleteLocalRef(a);
    e->DeleteLocalRef(b);
}

void JniFrontend::status(const QString& key, const QString& value)
{
    JNIEnv* e = env();
    jstring a = toJ(e, key);
    jstring b = toJ(e, value);
    e->CallStaticVoidMethod(gBridgeClass, gOnStatus, a, b);
    checkException(e, "onStatus");
    e->DeleteLocalRef(a);
    e->DeleteLocalRef(b);
}

} // namespace JniBridge
