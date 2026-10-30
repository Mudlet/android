#pragma once

#include "IFrontend.h"

class Engine;

namespace JniBridge {

// Unblocks QtServiceBase.onCreate(), which waits in QtNative.waitForServiceSetup() for a
// QAndroidService to register an onBind listener; a QGuiApplication never does that itself.
void releaseQtServiceSetup();

// Blocks until Kotlin reports that QtServiceBase.onCreate() has returned, so Qt's
// setApplicationState(Hidden) lands before the platform plugin exists (see REPORT.md).
bool waitForServiceReady(int timeoutMs);

// Kotlin calls that arrived before the engine existed are replayed here; nullptr detaches.
void setEngine(Engine* engine);

qint64 bootTimeMs();

class JniFrontend : public IFrontend
{
public:
    void line(const QString& text, const QList<ColourRun>& runs, bool isPrompt) override;
    void notify(const QString& title, const QString& text) override;
    void status(const QString& key, const QString& value) override;
};

} // namespace JniBridge
