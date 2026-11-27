#include "ControlBridge.h"
#include "MudletEngine.h"
#include "EngineStartMark.h"
#ifdef Q_OS_ANDROID
#include "AndroidServiceSetup.h"
#endif

#include <QCommandLineParser>
#include <QDir>
#include <QFileInfo>
#include <QGuiApplication>
#include <QScopeGuard>
#include <QSocketNotifier>
#include <QSslConfiguration>
#include <QStandardPaths>

#include <csignal>
#include <ctime>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>
#ifdef Q_OS_ANDROID
#include <sys/system_properties.h>
#endif

namespace {

qint64 bootTimeMs()
{
    // CLOCK_BOOTTIME is what SystemClock.elapsedRealtime() reads on Android
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return qint64(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

#ifndef Q_OS_ANDROID
int gTermPipe[2] = {-1, -1};

// SIGTERM on the desktop ends the event loop, so the engine closes as on a quit: libmudlet's profile is saved
void quitOnTerm(QCoreApplication& app)
{
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, gTermPipe) != 0) {
        return;
    }
    auto* notifier = new QSocketNotifier(gTermPipe[1], QSocketNotifier::Read, &app);
    // Once: quit() ends every event loop, and the engine runs one of its own to save on the way out
    QObject::connect(notifier, &QSocketNotifier::activated, &app, [notifier] {
        notifier->setEnabled(false);
        QCoreApplication::quit();
    });
    std::signal(SIGTERM, [](int) {
        const char byte = 1;
        [[maybe_unused]] const auto written = ::write(gTermPipe[0], &byte, 1);
    });
}
#endif

QString envOr(const char* name, const QString& fallback)
{
    const QByteArray value = qgetenv(name);
    return value.isEmpty() ? fallback : QString::fromLocal8Bit(value);
}

} // namespace

int main(int argc, char* argv[])
{
    engineMark("host.main");
#ifdef Q_OS_ANDROID
    // Otherwise Service.onCreate() never returns and the engine ANRs at startup
    AndroidServiceSetup::releaseQtServiceSetup();
    // QtServiceBase.onCreate() sets ApplicationHidden after starting Qt. Arriving
    // once the platform integration exists, that state stops the dispatcher's
    // timers and socket notifiers; arriving before, it is only stored as pending.
    engineMark("host.serviceSetupReleased");
    const bool serviceReady = AndroidServiceSetup::waitForServiceReady(10000);
    engineMark("host.serviceReady");
    if (!serviceReady) {
        qWarning("service never reported ready; starting Qt anyway");
    }
#endif
    QGuiApplication app(argc, argv);
    const qint64 guiReadyMs = bootTimeMs();
    engineMark("host.guiApp");

    QCommandLineParser parser;
    parser.addOption({QStringLiteral("socket"), QStringLiteral("Unix socket path"), QStringLiteral("path")});
    parser.addOption({QStringLiteral("heartbeat-ms"), QStringLiteral("Heartbeat interval"), QStringLiteral("ms")});
    parser.process(app);

    QString socketPath = parser.value(QStringLiteral("socket"));
    if (socketPath.isEmpty()) {
        socketPath = envOr("MUDLET_ENGINE_SOCKET", QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/engine.sock"));
    }
    QDir().mkpath(QFileInfo(socketPath).absolutePath());
    const int heartbeatMs =
            parser.value(QStringLiteral("heartbeat-ms")).isEmpty() ? envOr("MUDLET_HEARTBEAT_MS", QStringLiteral("30000")).toInt() : parser.value(QStringLiteral("heartbeat-ms")).toInt();

    ControlBridge bridge;
    engineMark("host.bridgeMade");
    MudletEngine engine(&bridge, heartbeatMs);
    bridge.setEngineControl(&engine);
    engineMark("host.engineMade");
#ifdef Q_OS_ANDROID
    // EngineService.onTrimMemory(): short of memory, the system may kill the engine next, with no save
    AndroidServiceSetup::setSaveHandler(&engine, [&engine] {
        engine.save();
    });
    const auto noSaveHandler = qScopeGuard([] {
        AndroidServiceSetup::setSaveHandler(nullptr, {});
    });
#endif

    bridge.engineStatus(QStringLiteral("enginePid"), QString::number(QCoreApplication::applicationPid()));
    bridge.engineStatus(QStringLiteral("socketPath"), socketPath);
#ifdef Q_OS_ANDROID
    bridge.engineStatus(QStringLiteral("serviceReadyWait"), serviceReady ? QStringLiteral("ok") : QStringLiteral("timeout"));
#endif
    auto reportState = [&bridge](Qt::ApplicationState state) {
        bridge.engineStatus(QStringLiteral("appState"), QString::number(int(state)));
    };
    QObject::connect(&app, &QGuiApplication::applicationStateChanged, &app, reportState);
    reportState(QGuiApplication::applicationState());
    for (const auto& [env, key] : {std::pair{"MUDLET_APP_ONCREATE_MS", "qtStartMsAfterAppOnCreate"}, std::pair{"MUDLET_SERVICE_ONCREATE_MS", "qtStartMsAfterServiceOnCreate"}}) {
        const qint64 start = qgetenv(env).toLongLong();
        if (start > 0) {
            bridge.engineStatus(QString::fromLatin1(key), QString::number(guiReadyMs - start));
        }
    }

    if (!bridge.listen(socketPath)) {
        return 1;
    }
    qInfo("engine listening on %s", qPrintable(socketPath));
    engineMark("host.listening");
    // The first QSslSocket loads Qt's TLS backend and OpenSSL, 0.1 s and more on Android: loaded here while the player
    // picks a profile, not by that profile's connection after their tap
    std::thread tlsLoad([] {
        QSslConfiguration::defaultConfiguration();
        engineMark("host.tlsLoaded");
    });
    const auto tlsLoaded = qScopeGuard([&tlsLoad] {
        tlsLoad.join();
    });
#ifndef Q_OS_ANDROID
    quitOnTerm(app);
#endif
    return app.exec();
}
