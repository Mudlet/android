// Desktop driver: engine_cli <host> <port>; stdin lines are sent to the game.
#include "Engine.h"

#include <QGuiApplication>
#include <QSocketNotifier>
#include <QTextStream>
#include <QTimer>

#include <cstdio>
#include <unistd.h>

namespace {
class ConsoleFrontend : public IFrontend
{
public:
    void line(const QString& text, const QList<ColorRun>& runs, bool isPrompt) override
    {
        QTextStream out(stdout);
        for (const ColorRun& run : runs) {
            if (run.fg >= 0) {
                out << "\x1b[" << (run.fg < 8 ? 30 + run.fg : 90 + run.fg - 8) << 'm';
            }
            out << text.mid(run.start, run.length) << "\x1b[0m";
        }
        out << (isPrompt ? " [prompt]\n" : "\n");
    }
    void notify(const QString& title, const QString& text) override
    {
        QTextStream(stdout) << "[notify] " << title << ": " << text << '\n';
    }
    void status(const QString& key, const QString& value) override
    {
        QTextStream(stderr) << "[status] " << key << " = " << value << '\n';
    }
};
} // namespace

int main(int argc, char* argv[])
{
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
    }
    QGuiApplication app(argc, argv);
    if (argc < 3) {
        fprintf(stderr, "usage: %s host port\n", argv[0]);
        return 2;
    }
    ConsoleFrontend frontend;
    Engine engine(&frontend);
    engine.start();
    engine.setViewWidthPx(1080);
    engine.connectTo(QString::fromLocal8Bit(argv[1]), QString::fromLocal8Bit(argv[2]).toInt());

    QSocketNotifier stdinNotifier(STDIN_FILENO, QSocketNotifier::Read);
    QObject::connect(&stdinNotifier, &QSocketNotifier::activated, &engine, [&]() {
        char buf[4096];
        if (!fgets(buf, sizeof buf, stdin)) {
            stdinNotifier.setEnabled(false);
            QCoreApplication::quit();
            return;
        }
        QString cmd = QString::fromLocal8Bit(buf).trimmed();
        if (cmd.startsWith(QLatin1String("/lua "))) {
            engine.runLua(cmd.mid(5));
        } else {
            engine.send(cmd);
        }
    });
    return app.exec();
}
