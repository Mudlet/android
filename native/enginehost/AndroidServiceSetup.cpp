#include "AndroidServiceSetup.h"

#include <QObject>
#include <QtCore/private/qjnihelpers_p.h>

#include <jni.h>

#include <chrono>
#include <condition_variable>
#include <mutex>

namespace {

std::mutex gReadyMutex;
std::condition_variable gReadyCv;
bool gServiceReady = false;

class NullBindListener : public QtAndroidPrivate::OnBindListener
{
public:
    jobject onBind(jobject) override { return nullptr; }
};
NullBindListener gBindListener;

std::mutex gSaveMutex;
QObject* gSaveContext = nullptr;
std::function<void()> gSave;

} // namespace

// Bound by name: QtLoader loads this library through the app's class loader
extern "C" JNIEXPORT void JNICALL Java_org_mudlet_android_engine_EngineNative_nativeServiceReady(JNIEnv*, jclass)
{
    {
        std::lock_guard lock(gReadyMutex);
        gServiceReady = true;
    }
    gReadyCv.notify_all();
}

// From the service's main thread, not Qt's: queued, so the save runs on the engine's thread
extern "C" JNIEXPORT void JNICALL Java_org_mudlet_android_engine_EngineNative_nativeSave(JNIEnv*, jclass)
{
    std::lock_guard lock(gSaveMutex);
    if (gSaveContext) {
        QMetaObject::invokeMethod(gSaveContext, gSave, Qt::QueuedConnection);
    }
}

namespace AndroidServiceSetup {

void releaseQtServiceSetup()
{
    // setOnBindListener() releases the semaphore only once the locker count is
    // zero; QAndroidService takes a lock in its constructor, and nothing here
    // does (sic: acuqire)
    QtAndroidPrivate::acuqireServiceSetup(0);
    QtAndroidPrivate::setOnBindListener(&gBindListener);
}

bool waitForServiceReady(int timeoutMs)
{
    std::unique_lock lock(gReadyMutex);
    return gReadyCv.wait_for(lock, std::chrono::milliseconds(timeoutMs), [] {
        return gServiceReady;
    });
}

void setSaveHandler(QObject* context, std::function<void()> save)
{
    std::lock_guard lock(gSaveMutex);
    gSaveContext = context;
    gSave = std::move(save);
}

} // namespace AndroidServiceSetup
