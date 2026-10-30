#pragma once

#include <QList>
#include <QString>

struct ColorRun
{
    int start = 0;
    int length = 0;
    // 0-15 for the ANSI palette, -1 for the default colour
    int fg = -1;
};

// Everything the engine tells its host. Every method must be implementable
// over a byte stream, so arguments are plain values only.
class IFrontend
{
public:
    virtual ~IFrontend() = default;
    virtual void line(const QString& text, const QList<ColorRun>& runs, bool isPrompt) = 0;
    virtual void notify(const QString& title, const QString& text) = 0;
    virtual void status(const QString& key, const QString& value) = 0;
};
