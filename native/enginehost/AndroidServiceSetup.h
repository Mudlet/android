#pragma once

#include <functional>

class QObject;

// Android only. QtServiceBase.onCreate() blocks the service's main thread in
// QtNative.waitForServiceSetup() until Qt's service-setup semaphore is
// released, which only QAndroidService does; a QGuiApplication engine must
// release it itself.
namespace AndroidServiceSetup {
void releaseQtServiceSetup();
// Waits for EngineNative.nativeServiceReady(), sent after
// QtServiceBase.onCreate() returns
bool waitForServiceReady(int timeoutMs);
// What EngineNative.nativeSave(), from EngineService.onTrimMemory(), runs on context's thread. Set the context
// back to null before it is destroyed; while it is null, nothing runs.
void setSaveHandler(QObject* context, std::function<void()> save);
} // namespace AndroidServiceSetup
