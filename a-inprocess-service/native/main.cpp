// Entry point Qt's android plugin dlsym()s and calls on its "qtMainLoopThread" after
// QtServiceBase.onCreate() has loaded the libraries. Everything below runs on that thread.
#include "Engine.h"
#include "JniBridge.h"

#include <QGuiApplication>
#include <QScreen>

#include <android/log.h>
#include <sys/syscall.h>
#include <unistd.h>

int main(int argc, char* argv[])
{
    const qint64 mainEnteredMs = JniBridge::bootTimeMs();
    JniBridge::releaseQtServiceSetup();
    const bool ready = JniBridge::waitForServiceReady(10000);
    if (!ready) {
        __android_log_print(ANDROID_LOG_WARN, "MudletProto", "service never reported ready; starting anyway");
    }

    QGuiApplication app(argc, argv);
    const qint64 qtStartedMs = JniBridge::bootTimeMs();

    JniBridge::JniFrontend frontend;
    Engine engine(&frontend);
    frontend.status(QStringLiteral("qt.mainEnteredBootMs"), QString::number(mainEnteredMs));
    frontend.status(QStringLiteral("qt.startedBootMs"), QString::number(qtStartedMs));
    frontend.status(QStringLiteral("qt.serviceReadyWait"), ready ? QStringLiteral("ok") : QStringLiteral("timeout"));
    frontend.status(QStringLiteral("qt.pid"), QString::number(getpid()));
    frontend.status(QStringLiteral("qt.tid"), QString::number(static_cast<long>(syscall(SYS_gettid))));
    frontend.status(QStringLiteral("qt.version"), QString::fromLatin1(qVersion()));
    // Qt measures fonts in logical pixels; the view width Compose sends is physical
    if (const QScreen* screen = QGuiApplication::primaryScreen()) {
        frontend.status(QStringLiteral("qt.dpr"), QString::number(screen->devicePixelRatio()));
    }

    auto reportState = [&frontend](Qt::ApplicationState state) {
        frontend.status(QStringLiteral("qt.appState"), QString::number(static_cast<int>(state)));
    };
    QObject::connect(&app, &QGuiApplication::applicationStateChanged, &app, reportState);
    reportState(QGuiApplication::applicationState());

    engine.start();
    JniBridge::setEngine(&engine);
    const int ret = app.exec();
    JniBridge::setEngine(nullptr);
    frontend.status(QStringLiteral("qt.exec"), QStringLiteral("returned %1").arg(ret));
    return ret;
}
