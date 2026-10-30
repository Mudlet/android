#include "AnsiParser.h"

#include <QStringList>

void AnsiParser::reset()
{
    mFg = -1;
    mBold = false;
}

AnsiParser::Result AnsiParser::parse(const QString& raw)
{
    Result result;
    result.text.reserve(raw.size());

    auto effectiveFg = [this]() {
        // Bold brightens the eight basic colours, as most MUD clients render it
        if (mBold && mFg >= 0 && mFg < 8) {
            return mFg + 8;
        }
        return mFg;
    };
    auto append = [&result](QChar c, int fg) {
        if (!result.runs.isEmpty() && result.runs.last().fg == fg
            && result.runs.last().start + result.runs.last().length == result.text.size()) {
            result.runs.last().length++;
        } else {
            result.runs.append({static_cast<int>(result.text.size()), 1, fg});
        }
        result.text.append(c);
    };

    const qsizetype n = raw.size();
    for (qsizetype i = 0; i < n; ++i) {
        const QChar c = raw.at(i);
        if (c != QChar(0x1b)) {
            append(c, effectiveFg());
            continue;
        }
        if (i + 1 < n && raw.at(i + 1) == QLatin1Char('[')) {
            // CSI: parameters and intermediates, then a final byte in 0x40-0x7e
            qsizetype j = i + 2;
            while (j < n && (raw.at(j).unicode() < 0x40 || raw.at(j).unicode() > 0x7e)) {
                ++j;
            }
            if (j < n && raw.at(j) == QLatin1Char('m')) {
                applySgr(raw.mid(i + 2, j - i - 2));
            }
            i = j;
        } else {
            // Two-character escape (ESC x): drop both
            ++i;
        }
    }
    return result;
}

void AnsiParser::applySgr(const QString& params)
{
    const QStringList parts = params.split(QLatin1Char(';'));
    for (qsizetype k = 0; k < parts.size(); ++k) {
        const int code = parts.at(k).isEmpty() ? 0 : parts.at(k).toInt();
        if (code == 0) {
            mFg = -1;
            mBold = false;
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
        } else if (code == 38 || code == 48) {
            const bool foreground = code == 38;
            if (k + 2 < parts.size() && parts.at(k + 1) == QLatin1String("5")) {
                const int idx = parts.at(k + 2).toInt();
                if (foreground) {
                    mFg = idx < 16 ? idx : -1;
                }
                k += 2;
            } else if (k + 4 < parts.size() && parts.at(k + 1) == QLatin1String("2")) {
                if (foreground) {
                    mFg = -1;
                }
                k += 4;
            }
        }
    }
}
