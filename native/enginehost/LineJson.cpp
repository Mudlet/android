#include "LineJson.h"

#include "IFrontend.h"

#include <charconv>

// Each frame's fields go in the order QJsonObject keeps its keys, sorted, so the bytes are the ones it writes

namespace {

void appendNumber(QByteArray& out, qint64 n)
{
    char digits[24];
    const auto end = std::to_chars(digits, digits + sizeof digits, n).ptr;
    out.append(digits, end - digits);
}

char hexDigit(unsigned n)
{
    return "0123456789ABCDEF"[n & 0xf];
}

char namedEscape(char16_t u)
{
    switch (u) {
    case '"':
        return '"';
    case '\\':
        return '\\';
    case '\b':
        return 'b';
    case '\f':
        return 'f';
    case '\n':
        return 'n';
    case '\r':
        return 'r';
    case '\t':
        return 't';
    default:
        return 0;
    }
}

// As Qt's JSON writer escapes text: a UTF-16 unit that is no character (a lone surrogate) goes as its \u escape
void appendText(QByteArray& out, QStringView text)
{
    const qsizetype from = out.size();
    // The most a UTF-16 unit takes is a six-byte escape
    out.resize(from + 2 + 6 * text.size());
    char* to = out.data() + from;
    const auto escape = [&to](char16_t u) {
        *to++ = '\\';
        *to++ = 'u';
        *to++ = hexDigit(u >> 12);
        *to++ = hexDigit(u >> 8);
        *to++ = hexDigit(u >> 4);
        *to++ = hexDigit(u);
    };
    *to++ = '"';
    const char16_t* at = text.utf16();
    const char16_t* const end = at + text.size();
    while (at != end) {
        const char16_t u = *at++;
        if (u >= 0x20 && u < 0x80 && u != '"' && u != '\\') {
            *to++ = char(u);
        } else if (const char named = namedEscape(u)) {
            *to++ = '\\';
            *to++ = named;
        } else if (u < 0x80) {
            escape(u);
        } else if (u < 0x800) {
            *to++ = char(0xc0 | (u >> 6));
            *to++ = char(0x80 | (u & 0x3f));
        } else if (!QChar::isSurrogate(u)) {
            *to++ = char(0xe0 | (u >> 12));
            *to++ = char(0x80 | ((u >> 6) & 0x3f));
            *to++ = char(0x80 | (u & 0x3f));
        } else if (QChar::isHighSurrogate(u) && at != end && QChar::isLowSurrogate(*at)) {
            const char32_t c = QChar::surrogateToUcs4(u, *at++);
            *to++ = char(0xf0 | (c >> 18));
            *to++ = char(0x80 | ((c >> 12) & 0x3f));
            *to++ = char(0x80 | ((c >> 6) & 0x3f));
            *to++ = char(0x80 | (c & 0x3f));
        } else {
            escape(u);
        }
    }
    *to++ = '"';
    out.resize(to - out.constData());
}

// [start, length, fg], with bg and attributes, then link, only when a run has them
void appendRuns(QByteArray& out, const QList<ColorRun>& runs)
{
    out.append('[');
    for (const ColorRun& run : runs) {
        if (out.back() != '[') {
            out.append(',');
        }
        out.append('[');
        appendNumber(out, run.start);
        out.append(',');
        appendNumber(out, run.length);
        out.append(',');
        appendNumber(out, run.fg);
        if (run.link != 0 || run.bg != -1 || run.attributes != 0) {
            out.append(',');
            appendNumber(out, run.bg);
            out.append(',');
            appendNumber(out, run.attributes);
        }
        if (run.link != 0) {
            out.append(',');
            appendNumber(out, run.link);
        }
        out.append(']');
    }
    out.append(']');
}

// line, lineUpdated and tail open with prompt, their first key in sorted order
QByteArray opened(bool prompt, QStringView text, const QList<ColorRun>& runs)
{
    QByteArray out;
    out.reserve(96 + 3 * text.size() + 24 * runs.size());
    out.append(prompt ? "{\"prompt\":true" : "{\"prompt\":false");
    return out;
}

} // namespace

QByteArray lineJson(qint64 seq, QStringView text, const QList<ColorRun>& runs, bool prompt, qint64 time, bool replay)
{
    QByteArray out = opened(prompt, text, runs);
    if (replay) {
        out.append(",\"replay\":true");
    }
    out.append(",\"runs\":");
    appendRuns(out, runs);
    out.append(",\"seq\":");
    appendNumber(out, seq);
    out.append(",\"t\":\"line\",\"text\":");
    appendText(out, text);
    out.append(",\"time\":");
    appendNumber(out, time);
    out.append('}');
    return out;
}

QByteArray lineUpdatedJson(qint64 seq, QStringView text, const QList<ColorRun>& runs, bool prompt)
{
    QByteArray out = opened(prompt, text, runs);
    out.append(",\"runs\":");
    appendRuns(out, runs);
    out.append(",\"seq\":");
    appendNumber(out, seq);
    out.append(",\"t\":\"lineUpdated\",\"text\":");
    appendText(out, text);
    out.append('}');
    return out;
}

QByteArray lineDeletedJson(qint64 seq)
{
    QByteArray out("{\"deleted\":true,\"seq\":");
    appendNumber(out, seq);
    out.append(",\"t\":\"lineUpdated\"}");
    return out;
}

QByteArray tailJson(QStringView text, const QList<ColorRun>& runs, bool prompt, qint64 time)
{
    QByteArray out = opened(prompt, text, runs);
    out.append(",\"runs\":");
    appendRuns(out, runs);
    out.append(",\"t\":\"tail\",\"text\":");
    appendText(out, text);
    out.append(",\"time\":");
    appendNumber(out, time);
    out.append('}');
    return out;
}
