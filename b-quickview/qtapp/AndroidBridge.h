#pragma once

#include "IFrontend.h"

class Engine;

// IFrontend over JNI: calls static methods of org.mudlet.proto.b.EngineBridge (Kotlin).
class AndroidFrontend : public IFrontend
{
public:
    void line(const QString& text, const QList<ColorRun>& runs, bool isPrompt) override;
    void notify(const QString& title, const QString& text) override;
    void status(const QString& key, const QString& value) override;
};

namespace AndroidBridge {
// Called on Qt's main thread around app.exec(); natives arriving outside that window are dropped.
void attach(Engine* engine);
void detach();
} // namespace AndroidBridge
