#include "AndroidBridge.h"
#include "Engine.h"

#include <QElapsedTimer>
#include <QGuiApplication>
#include <QThread>

#include <sys/types.h>
#include <unistd.h>

// Qt's loader calls this on its "QtMainThread" once a QtQuickView has loaded the libraries.
int main(int argc, char* argv[])
{
    QElapsedTimer sinceMain;
    sinceMain.start();
    QGuiApplication app(argc, argv);

    AndroidFrontend frontend;
    Engine engine(&frontend);
    AndroidBridge::attach(&engine);
    QObject::connect(&app, &QCoreApplication::aboutToQuit, &app, [&frontend]() {
        frontend.status(QStringLiteral("qt"), QStringLiteral("aboutToQuit"));
    });
    QObject::connect(&app, &QGuiApplication::applicationStateChanged, &app, [&frontend](Qt::ApplicationState state) {
        frontend.status(QStringLiteral("qt.appState"), QString::number(int(state)));
    });

    frontend.status(QStringLiteral("qt.thread"),
                    QStringLiteral("pid=%1 tid=%2 QGuiApplication after %3 ms in main()")
                            .arg(getpid())
                            .arg(gettid())
                            .arg(sinceMain.elapsed()));
    frontend.status(QStringLiteral("qt"), QStringLiteral("running"));
    engine.start();

    const int rc = app.exec();
    AndroidBridge::detach();
    frontend.status(QStringLiteral("qt"), QStringLiteral("main() returned %1").arg(rc));
    return rc;
}
