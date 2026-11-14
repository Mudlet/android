#include "AndroidServiceSetup.h"

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

} // namespace AndroidServiceSetup
