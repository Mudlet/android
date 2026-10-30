#include "AnsiParser.h"

#include <QStringList>

void AnsiParser::reset()
{
    mFg = -1;
    mBold = false;
}

int AnsiParser::effectiveFg() const
{
    if (mBold && mFg >= 0 && mFg < 8) {
        return mFg + 8;
    }
    return mFg;
}

void AnsiParser::applySgr(const QString& params)
{
    const QStringList parts = params.split(QLatin1Char(';'));
    for (qsizetype i = 0; i < parts.size(); ++i) {
        const int code = parts.at(i).isEmpty() ? 0 : parts.at(i).toInt();
        if (code == 0) {
            reset();
        } else if (code == 1) {
            mBold = true;
        } else if (code == 22) {
            mBold = false;
        } else if (code >= 30 && code <= 37) {
            mFg = code - 30;
        } else if (code == 39) {
            mFg = -1;
        } else if (code >= 90 && code <= 97) {
            mFg = code - 90 + 8;
        } else if (code == 38 && i + 1 < parts.size()) {
            const int mode = parts.at(i + 1).toInt();
            if (mode == 5 && i + 2 < parts.size()) {
                const int idx = parts.at(i + 2).toInt();
                mFg = idx < 16 ? idx : -1;
                i += 2;
            } else if (mode == 2) {
                mFg = -1;
                i += 4;
            }
        }
    }
}

AnsiParser::Result AnsiParser::parse(const QString& raw)
{
    Result result;
    result.text.reserve(raw.size());
    int runStart = 0;
    int runFg = effectiveFg();

    auto closeRun = [&]() {
        const int len = int(result.text.size()) - runStart;
        if (len > 0) {
            if (!result.runs.isEmpty() && result.runs.last().fg == runFg) {
                result.runs.last().length += len;
            } else {
                result.runs.append({runStart, len, runFg});
            }
        }
        runStart = int(result.text.size());
        runFg = effectiveFg();
    };

    const qsizetype n = raw.size();
    for (qsizetype i = 0; i < n; ++i) {
        const QChar c = raw.at(i);
        if (c != QChar(0x1b)) {
            result.text.append(c);
            continue;
        }
        if (i + 1 < n && raw.at(i + 1) == QLatin1Char('[')) {
            qsizetype j = i + 2;
            while (j < n && !(raw.at(j).unicode() >= 0x40 && raw.at(j).unicode() <= 0x7e)) {
                ++j;
            }
            if (j >= n) {
                break; // truncated sequence: drop it
            }
            if (raw.at(j) == QLatin1Char('m')) {
                closeRun();
                applySgr(raw.mid(i + 2, j - i - 2));
                runFg = effectiveFg();
            }
            i = j;
        } else {
            ++i; // two-byte escape, e.g. ESC 7
        }
    }
    closeRun();
    return result;
}
