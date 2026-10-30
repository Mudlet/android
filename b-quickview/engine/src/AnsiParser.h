#pragma once

#include "IFrontend.h"

#include <QList>
#include <QString>

// SGR state carries across lines, as MUDs often colour a block and reset only at its end.
class AnsiParser
{
public:
    struct Result
    {
        QString text;
        QList<ColorRun> runs;
    };

    Result parse(const QString& raw);
    void reset();

private:
    void applySgr(const QString& params);
    int effectiveFg() const;

    int mFg = -1;
    bool mBold = false;
};
