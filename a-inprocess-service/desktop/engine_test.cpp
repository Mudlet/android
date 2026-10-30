// Runs the engine against desktop/fake_server.py and checks telnet, line splitting, prompts,
// ANSI runs, Lua onLine/notify, runLua, the font check and the heartbeat.
#include "Engine.h"

#include <QGuiApplication>
#include <QMap>
#include <QProcess>
#include <QTimer>

#include <cstdio>

namespace {
struct Line
{
    QString text;
    QList<ColourRun> runs;
    bool prompt;
};

class RecordingFrontend : public IFrontend
{
public:
    void line(const QString& text, const QList<ColourRun>& runs, bool isPrompt) override
    {
        lines.append({text, runs, isPrompt});
        std::printf("line%s: %s\n", isPrompt ? "(prompt)" : "", qPrintable(text));
        if (onLineCb) {
            onLineCb(lines.last());
        }
    }
    void notify(const QString& title, const QString& text) override
    {
        notifications.append(title + QLatin1String(": ") + text);
    }
    void status(const QString& key, const QString& value) override
    {
        statuses[key] = value;
        if (onStatusCb) {
            onStatusCb(key, value);
        }
    }

    QList<Line> lines;
    QStringList notifications;
    QMap<QString, QString> statuses;
    std::function<void(const Line&)> onLineCb;
    std::function<void(const QString&, const QString&)> onStatusCb;
};

int failures = 0;

void check(bool ok, const char* what)
{
    std::printf("%s: %s\n", ok ? "PASS" : "FAIL", what);
    if (!ok) {
        ++failures;
    }
}

QString runsToString(const QList<ColourRun>& runs)
{
    QStringList out;
    for (const ColourRun& r : runs) {
        out << QStringLiteral("%1+%2:%3").arg(r.start).arg(r.length).arg(r.fg);
    }
    return out.join(QLatin1Char(' '));
}
} // namespace

int main(int argc, char* argv[])
{
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
    }
    QGuiApplication app(argc, argv);
    if (argc < 3) {
        std::fprintf(stderr, "usage: engine_test <python3> <fake_server.py>\n");
        return 2;
    }

    QProcess server;
    server.start(QString::fromLocal8Bit(argv[1]), {QString::fromLocal8Bit(argv[2])});
    if (!server.waitForReadyRead(5000)) {
        std::fprintf(stderr, "fake server did not start\n");
        return 2;
    }
    const int port = server.readLine().trimmed().toInt();

    RecordingFrontend frontend;
    Engine engine(&frontend);
    engine.setHeartbeatIntervalMs(200);
    engine.start();
    engine.setViewWidthPx(1080);

    bool sentLook = false;
    frontend.onLineCb = [&](const Line& l) {
        if (l.prompt && l.text == QLatin1String("Silent prompt> ") && !sentLook) {
            sentLook = true;
            engine.send(QStringLiteral("look"));
        }
    };
    frontend.onStatusCb = [&](const QString& key, const QString& value) {
        if (key == QLatin1String("connection") && value == QLatin1String("disconnected")) {
            QTimer::singleShot(300, &app, &QCoreApplication::quit);
        }
    };
    QTimer::singleShot(20000, &app, [&]() {
        check(false, "finished before the 20 s timeout");
        app.quit();
    });

    engine.connectTo(QStringLiteral("127.0.0.1"), port);
    app.exec();

    engine.runLua(QStringLiteral("return lineCount, 1 + 1"));
    const QString luaResult = frontend.statuses.value(QStringLiteral("lua.result"));
    engine.runLua(QStringLiteral("error('boom')"));
    const QString luaError = frontend.statuses.value(QStringLiteral("lua.error"));

    const QStringList expected = {QStringLiteral("Welcome to FakeMUD"),
                                  QStringLiteral("Bob tells you 'hello'"),
                                  QStringLiteral("green line"),
                                  QStringLiteral("still green"),
                                  QStringLiteral("HP:100> "),
                                  QStringLiteral("Silent prompt> "),
                                  QStringLiteral("NEGOTIATION OK"),
                                  QString::fromUtf8("xterm grey café"),
                                  QStringLiteral("BYE")};
    QStringList got;
    for (const Line& l : frontend.lines) {
        got << l.text;
    }
    check(got == expected, "line texts (IAC stripped, \\r dropped, split segments joined)");
    if (got != expected) {
        std::printf("  got: [%s]\n", qPrintable(got.join(QLatin1String("|"))));
    }
    if (frontend.lines.size() == expected.size()) {
        const auto& L = frontend.lines;
        check(runsToString(L[0].runs) == QLatin1String("0+11:-1 11+4:9 15+3:-1"), "bold red SGR run");
        check(runsToString(L[2].runs) == QLatin1String("0+10:2"), "green run");
        check(runsToString(L[3].runs) == QLatin1String("0+11:2"), "colour carries over to next line");
        check(runsToString(L[7].runs) == QLatin1String("0+6:-1 6+4:8 10+5:-1"), "256-colour and bright runs");
        check(!L[3].prompt && L[4].prompt, "GA marks a prompt");
        check(L[5].prompt, "500 ms of silence marks a prompt");
        check(!L[6].prompt && !L[8].prompt, "normal lines are not prompts");
    }
    check(frontend.notifications.size() == 1
              && frontend.notifications.value(0) == QLatin1String("Tell: Bob tells you 'hello'"),
          "Lua onLine called notify() for the tell");
    check(engine.luaOnLineCalls() == expected.size(), "Lua onLine called once per line");
    check(luaResult == QLatin1String("9, 2"), "runLua returns lineCount");
    check(luaError.contains(QLatin1String("boom")), "runLua reports errors");
    check(!frontend.statuses.value(QStringLiteral("font.family")).isEmpty(), "font family resolved");
    check(frontend.statuses.value(QStringLiteral("font.advanceM")).toInt() > 0, "'M' advance > 0");
    check(frontend.statuses.value(QStringLiteral("font.columns")).toInt() > 0, "columns computed");
    check(engine.heartbeatTicks() > 0, "heartbeat timer ticked");
    std::printf("font: %s, M=%s px, columns@1080=%s, heartbeat ticks=%d max gap=%s ms\n",
                qPrintable(frontend.statuses.value(QStringLiteral("font.family"))),
                qPrintable(frontend.statuses.value(QStringLiteral("font.advanceM"))),
                qPrintable(frontend.statuses.value(QStringLiteral("font.columns"))),
                engine.heartbeatTicks(),
                qPrintable(frontend.statuses.value(QStringLiteral("heartbeat.maxGapMs"))));

    server.waitForFinished(3000);
    check(server.exitCode() == 0, "fake server exited cleanly");
    std::printf("%s (%d failures)\n", failures ? "FAILED" : "ALL PASSED", failures);
    return failures ? 1 : 0;
}
