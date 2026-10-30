#pragma once

#include <QList>
#include <QString>

struct ColourRun
{
    int start = 0;
    int length = 0;
    // 0-15 for the ANSI palette, -1 for the default colour
    int fg = -1;

    bool operator==(const ColourRun&) const = default;
};

// Everything the engine tells its host. Called on the engine's (Qt's) thread.
class IFrontend
{
public:
    virtual ~IFrontend() = default;
    virtual void line(const QString& text, const QList<ColourRun>& runs, bool isPrompt) = 0;
    virtual void notify(const QString& title, const QString& text) = 0;
    virtual void status(const QString& key, const QString& value) = 0;
};
