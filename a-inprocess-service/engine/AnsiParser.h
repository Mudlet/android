#pragma once

#include "IFrontend.h"

// Strips ANSI escape sequences from a decoded line and turns SGR foreground colours into runs.
// The colour state carries over from one line to the next, as it does on a real MUD.
class AnsiParser
{
public:
    struct Result
    {
        QString text;
        QList<ColourRun> runs;
    };

    Result parse(const QString& raw);
    void reset();

private:
    void applySgr(const QString& params);

    int mFg = -1;
    bool mBold = false;
};
