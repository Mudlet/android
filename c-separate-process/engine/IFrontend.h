#pragma once

#include <QList>
#include <QString>

struct ColorRun
{
    int start = 0;
    int length = 0;
    // 0-15 for the ANSI palette, rgbFlag | 0xRRGGBB for an exact colour, -1 for the default
    int fg = -1;
    int bg = -1;
    int attributes = 0;
    static constexpr int rgbFlag = 0x1000000;
    enum Attribute { bold = 1, italic = 2, underline = 4, strikeOut = 8, reverse = 16 };
    bool operator==(const ColorRun&) const = default;
};

// Everything the engine tells its host. Every method must be implementable
// over a byte stream, so arguments are plain values only.
class IFrontend
{
public:
    virtual ~IFrontend() = default;
    virtual void line(const QString& text, const QList<ColorRun>& runs, bool isPrompt) = 0;
    // The line still being written below the last one, shown until it is replaced; empty for none
    virtual void tail(const QString& text, const QList<ColorRun>& runs, bool isPrompt) = 0;
    virtual void notify(const QString& title, const QString& text) = 0;
    virtual void status(const QString& key, const QString& value) = 0;
};
