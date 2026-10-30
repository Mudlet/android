// Runs the engine against tests/fake_telnet_server.py: engine_selftest <port>
#include "Engine.h"

#include <QGuiApplication>
#include <QTextStream>
#include <QTimer>

namespace {
struct Line
{
    QString text;
    QList<ColorRun> runs;
    bool prompt;
};

class RecordingFrontend : public IFrontend
{
public:
    QList<Line> lines;
    QList<QPair<QString, QString>> notifications;
    QHash<QString, QString> statuses;
    std::function<void(const QString&, const QString&)> onStatus;
    std::function<void(const Line&)> onLine;

    void line(const QString& text, const QList<ColorRun>& runs, bool isPrompt) override
    {
        lines.append({text, runs, isPrompt});
        if (onLine) {
            onLine(lines.last());
        }
    }
    void notify(const QString& title, const QString& text) override { notifications.append({title, text}); }
    void status(const QString& key, const QString& value) override
    {
        statuses.insert(key, value);
        QTextStream(stderr) << "[status] " << key << " = " << value << '\n';
        if (onStatus) {
            onStatus(key, value);
        }
    }
};

QString runsToString(const QList<ColorRun>& runs)
{
    QStringList parts;
    for (const ColorRun& r : runs) {
        parts << QStringLiteral("%1+%2:%3").arg(r.start).arg(r.length).arg(r.fg);
    }
    return parts.join(QLatin1Char(' '));
}
} // namespace

int main(int argc, char* argv[])
{
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
    }
    QGuiApplication app(argc, argv);
    if (argc < 2) {
        return 2;
    }
    RecordingFrontend fe;
    Engine engine(&fe);
    engine.setHeartbeatIntervalMs(400);
    engine.start();
    engine.setViewWidthPx(1080);

    fe.onLine = [&](const Line& l) {
        if (l.prompt && l.text == QLatin1String("HP:100> ")) {
            engine.send(QStringLiteral("look"));
        }
    };
    bool done = false;
    fe.onStatus = [&](const QString& key, const QString& value) {
        if (key == QLatin1String("conn") && value == QLatin1String("disconnected") && !done) {
            done = true;
            engine.runLua(QStringLiteral("return lineCount"));
            // Let the coalesced counters status fire before checking.
            QTimer::singleShot(400, &app, &QCoreApplication::quit);
        }
    };
    QTimer::singleShot(15000, &app, [&]() {
        QTextStream(stderr) << "timeout\n";
        QCoreApplication::exit(3);
    });
    engine.connectTo(QStringLiteral("127.0.0.1"), QString::fromLocal8Bit(argv[1]).toInt());
    if (app.exec() == 3) {
        return 3;
    }

    struct Expect
    {
        QString text;
        bool prompt;
        QString runs;
    };
    const QList<Expect> expected = {
            {QStringLiteral("Welcome to Fake MUD"), false, QStringLiteral("0+11:-1 11+4:9 15+4:-1")},
            {QStringLiteral("green line"), false, QStringLiteral("0+10:2")},
            {QStringLiteral("still green"), false, QStringLiteral("0+11:2")},
            {QStringLiteral("Bob tells you 'hello'"), false, QStringLiteral("0+21:-1")},
            {QStringLiteral("beforeafter"), false, QStringLiteral("0+11:-1")},
            {QStringLiteral("HP:100> "), true, QStringLiteral("0+8:-1")},
            {QStringLiteral("You look around."), false, QStringLiteral("0+16:-1")},
            {QStringLiteral("Silent> "), true, QStringLiteral("0+8:-1")},
            {QStringLiteral("bright yellow default"), false, QStringLiteral("0+13:11 13+8:-1")},
            {QStringLiteral("256: idx9 then truecolor"), false, QStringLiteral("0+5:-1 5+4:9 9+15:-1")},
    };

    int failures = 0;
    auto check = [&](bool ok, const QString& what) {
        QTextStream(stdout) << (ok ? "PASS " : "FAIL ") << what << '\n';
        if (!ok) {
            ++failures;
        }
    };

    check(fe.lines.size() == expected.size(), QStringLiteral("line count %1 == %2").arg(fe.lines.size()).arg(expected.size()));
    for (qsizetype i = 0; i < std::min(fe.lines.size(), expected.size()); ++i) {
        const Line& got = fe.lines.at(i);
        const Expect& want = expected.at(i);
        const QString gotRuns = runsToString(got.runs);
        check(got.text == want.text && got.prompt == want.prompt && gotRuns == want.runs,
              QStringLiteral("line %1: '%2' prompt=%3 runs=[%4]").arg(i).arg(got.text).arg(got.prompt).arg(gotRuns));
    }
    check(fe.notifications.size() == 1 && fe.notifications.value(0).first == QLatin1String("Tell")
                  && fe.notifications.value(0).second == QLatin1String("Bob tells you 'hello'"),
          QStringLiteral("one Tell notification from Lua (got %1)").arg(fe.notifications.size()));
    check(fe.statuses.value(QStringLiteral("lua.result")) == QString::number(expected.size()),
          QStringLiteral("Lua lineCount == %1 (got %2)").arg(expected.size()).arg(fe.statuses.value(QStringLiteral("lua.result"))));
    check(fe.statuses.value(QStringLiteral("counters")) == QStringLiteral("lines=%1 lua=%1 notify=1").arg(expected.size()),
          QStringLiteral("counters: %1").arg(fe.statuses.value(QStringLiteral("counters"))));
    check(fe.statuses.value(QStringLiteral("font.advanceM")).toInt() > 0,
          QStringLiteral("font: %1, M advance %2, columns %3")
                  .arg(fe.statuses.value(QStringLiteral("font.family")), fe.statuses.value(QStringLiteral("font.advanceM")),
                       fe.statuses.value(QStringLiteral("columns"))));
    check(fe.statuses.value(QStringLiteral("heartbeat")).startsWith(QLatin1String("ticks=")),
          QStringLiteral("heartbeat: %1").arg(fe.statuses.value(QStringLiteral("heartbeat"))));
    check(!fe.statuses.contains(QStringLiteral("lua.error")), QStringLiteral("no Lua errors"));
    QTextStream(stdout) << (failures ? "ENGINE SELFTEST FAILED\n" : "ENGINE SELFTEST OK\n");
    return failures ? 1 : 0;
}
