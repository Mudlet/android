#pragma once

// Android only. QtServiceBase.onCreate() blocks the service's main thread in
// QtNative.waitForServiceSetup() until Qt's service-setup semaphore is
// released, which only QAndroidService does; a QGuiApplication engine must
// release it itself.
namespace AndroidServiceSetup {
void releaseQtServiceSetup();
// Waits for EngineNative.nativeServiceReady(), sent after
// QtServiceBase.onCreate() returns
bool waitForServiceReady(int timeoutMs);
} // namespace AndroidServiceSetup
