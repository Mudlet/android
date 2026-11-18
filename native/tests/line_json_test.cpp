// The line frames' writer (enginehost/LineJson) against QJsonDocument: the same bytes for every line, its text
// holding every kind of UTF-16 unit, quotes, control characters and lone surrogates among them, and its runs every
// shape the protocol gives one.
#include "IFrontend.h"
#include "LineJson.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>

#include <cstdio>
#include <limits>
#include <random>

namespace {

int failures = 0;

QJsonArray qtRuns(const QList<ColorRun>& runs)
{
    QJsonArray json;
    for (const ColorRun& run : runs) {
        if (run.link != 0) {
            json.append(QJsonArray{run.start, run.length, run.fg, run.bg, run.attributes, run.link});
        } else if (run.bg != -1 || run.attributes != 0) {
            json.append(QJsonArray{run.start, run.length, run.fg, run.bg, run.attributes});
        } else {
            json.append(QJsonArray{run.start, run.length, run.fg});
        }
    }
    return json;
}

QByteArray qtJson(const QJsonObject& message)
{
    return QJsonDocument(message).toJson(QJsonDocument::Compact);
}

void check(const QByteArray& ours, const QByteArray& qts, const char* frame, int which)
{
    if (ours != qts) {
        ++failures;
        std::fprintf(stderr, "FAIL %s %d:\n  ours %s\n  Qt's %s\n", frame, which, ours.toHex().constData(), qts.toHex().constData());
    }
}

void checkLine(const QString& text, const QList<ColorRun>& runs, bool prompt, qint64 seq, qint64 time, int which)
{
    const QJsonArray json = qtRuns(runs);
    for (const bool replay : {false, true}) {
        QJsonObject line{{QStringLiteral("t"), QStringLiteral("line")},
                         {QStringLiteral("seq"), seq},
                         {QStringLiteral("text"), text},
                         {QStringLiteral("runs"), json},
                         {QStringLiteral("prompt"), prompt},
                         {QStringLiteral("time"), time}};
        if (replay) {
            line.insert(QStringLiteral("replay"), true);
        }
        check(lineJson(seq, text, runs, prompt, time, replay), qtJson(line), "line", which);
    }
    check(lineUpdatedJson(seq, text, runs, prompt),
          qtJson({{QStringLiteral("t"), QStringLiteral("lineUpdated")},
                  {QStringLiteral("seq"), seq},
                  {QStringLiteral("text"), text},
                  {QStringLiteral("runs"), json},
                  {QStringLiteral("prompt"), prompt}}),
          "lineUpdated",
          which);
    check(lineDeletedJson(seq), qtJson({{QStringLiteral("t"), QStringLiteral("lineUpdated")}, {QStringLiteral("seq"), seq}, {QStringLiteral("deleted"), true}}), "deleted lineUpdated", which);
    check(tailJson(text, runs, prompt, time),
          qtJson({{QStringLiteral("t"), QStringLiteral("tail")}, {QStringLiteral("text"), text}, {QStringLiteral("runs"), json}, {QStringLiteral("prompt"), prompt}, {QStringLiteral("time"), time}}),
          "tail",
          which);
}

QString units(std::initializer_list<char16_t> list)
{
    QString text;
    for (const char16_t u : list) {
        text.append(QChar(u));
    }
    return text;
}

} // namespace

int main()
{
    const QList<ColorRun> shapes{
            {0, 3, 7}, {3, 2, 1, -1, ColorRun::bold}, {5, 4, ColorRun::rgbFlag | 0xffaa00, 4}, {9, 1, -1, -1, 0, 17}, {10, 0, 15, ColorRun::rgbFlag, ColorRun::concealed | ColorRun::reverse, 1}};
    const QList<QString> texts{
            QString(),
            QStringLiteral("You slash a goblin with your longsword."),
            QStringLiteral("He says, \"a\\b\" / and leaves."),
            units({0x00, 0x01, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x1b, 0x1f, 0x20, 0x7e, 0x7f}),
            QStringLiteral("café Straße ¡olé! £5 ×2"),
            QStringLiteral("北の門 — ¿qué? ‘quoted’ …"),
            units({0xd83d, 0xde00, 0x20, 0xd83c, 0xdff0}),
            units({0xd800}),
            units({0x41, 0xdc00, 0x42}),
            units({0xd800, 0xd800, 0xdc00}),
            units({0xdbff, 0xdfff, 0xdbff}),
            units({0x2028, 0x2029, 0xfeff, 0xfffd, 0xfffe, 0xffff, 0x80, 0x7ff, 0x800}),
    };
    int which = 0;
    for (const QString& text : texts) {
        checkLine(text, {}, false, 1, 0, which);
        checkLine(text, shapes, true, std::numeric_limits<qint64>::max(), 1'731'000'000'000, which);
        checkLine(text, shapes.mid(which % shapes.size()), which % 2, -1, std::numeric_limits<qint64>::min(), which);
        ++which;
    }

    // Text and runs at random, every UTF-16 unit as likely as an ASCII one
    std::mt19937 random(9011);
    for (int line = 0; line < 20000; ++line, ++which) {
        QString text;
        const int length = int(random() % 40);
        for (int i = 0; i < length; ++i) {
            text.append(QChar(char16_t(random() % 2 ? random() % 0x80 : random() % 0x10000)));
        }
        QList<ColorRun> runs;
        for (int run = int(random() % 5); run > 0; --run) {
            runs.append({int(random() % 100),
                         int(random() % 100),
                         int(random() % 17) - 1,
                         random() % 3 ? -1 : int(random() % 16),
                         random() % 3 ? 0 : int(random() % 512),
                         random() % 4 ? 0 : int(random() % 1000)});
        }
        checkLine(text, runs, random() % 2, qint64(random()), qint64(random()) << 12, which);
    }

    if (failures) {
        std::fprintf(stderr, "%d checks failed\n", failures);
        return 1;
    }
    std::printf("the line frames' writer: all %d lines as Qt writes them\n", which);
    return 0;
}
