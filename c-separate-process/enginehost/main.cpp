#ifdef ENGINE_LIBMUDLET
#include "MudletEngine.h"
#else
#include "Engine.h"
#endif
#include "SocketBridge.h"
#ifdef Q_OS_ANDROID
#include "AndroidServiceSetup.h"
#endif

#include <QCommandLineParser>
#include <QDir>
#include <QFileInfo>
#include <QGuiApplication>
#include <QStandardPaths>

#include <ctime>

namespace {

qint64 bootTimeMs()
{
    // CLOCK_BOOTTIME is what SystemClock.elapsedRealtime() reads on Android
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return qint64(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

QString envOr(const char* name, const QString& fallback)
{
    const QByteArray value = qgetenv(name);
    return value.isEmpty() ? fallback : QString::fromLocal8Bit(value);
}

} // namespace

int main(int argc, char* argv[])
{
#ifdef Q_OS_ANDROID
    // Otherwise Service.onCreate() never returns and the engine ANRs at startup
    AndroidServiceSetup::releaseQtServiceSetup();
    // QtServiceBase.onCreate() sets ApplicationHidden after starting Qt. Arriving
    // once the platform integration exists, that state stops the dispatcher's
    // timers and socket notifiers; arriving before, it is only stored as pending.
    const bool serviceReady = AndroidServiceSetup::waitForServiceReady(10000);
    if (!serviceReady) {
        qWarning("service never reported ready; starting Qt anyway");
    }
#endif
    QGuiApplication app(argc, argv);
    const qint64 guiReadyMs = bootTimeMs();

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

    SocketBridge bridge;
#ifdef ENGINE_LIBMUDLET
    MudletEngine engine(&bridge, heartbeatMs);
#else
    Engine engine(&bridge, heartbeatMs);
#endif
    bridge.setEngine(&engine);

    bridge.status(QStringLiteral("enginePid"), QString::number(QCoreApplication::applicationPid()));
    bridge.status(QStringLiteral("socketPath"), socketPath);
#ifdef Q_OS_ANDROID
    bridge.status(QStringLiteral("serviceReadyWait"), serviceReady ? QStringLiteral("ok") : QStringLiteral("timeout"));
#endif
    auto reportState = [&bridge](Qt::ApplicationState state) {
        bridge.status(QStringLiteral("appState"), QString::number(int(state)));
    };
    QObject::connect(&app, &QGuiApplication::applicationStateChanged, &app, reportState);
    reportState(QGuiApplication::applicationState());
    for (const auto& [env, key] : {std::pair{"MUDLET_APP_ONCREATE_MS", "qtStartMsAfterAppOnCreate"}, std::pair{"MUDLET_SERVICE_ONCREATE_MS", "qtStartMsAfterServiceOnCreate"}}) {
        const qint64 start = qgetenv(env).toLongLong();
        if (start > 0) {
            bridge.status(QString::fromLatin1(key), QString::number(guiReadyMs - start));
        }
    }

    if (!bridge.listen(socketPath)) {
        return 1;
    }
    qInfo("engine listening on %s", qPrintable(socketPath));
    return app.exec();
}
