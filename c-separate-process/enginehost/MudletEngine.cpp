#include "MudletEngine.h"

#include "THeadlessSession.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFontDatabase>
#include <QFontInfo>
#include <QFontMetrics>
#include <QGuiApplication>
#include <QStandardPaths>

// A Qt resource collection in a static library reaches the binary only if something names it
extern void qInitResources_mudlet();
extern void qInitResources_qm();

namespace {

// ANSI palette index the UI draws text Mudlet shows only locally in (echo, print, commands)
constexpr int localTextFg = 6;

QString luaLongString(const QString& text)
{
    return QStringLiteral("[==[%1]==]").arg(text);
}

} // namespace

MudletEngine::MudletEngine(IFrontend* frontend, int heartbeatMs, QObject* parent)
: QObject(parent)
, mFrontend(frontend)
, mProfile(qEnvironmentVariable("MUDLET_PROFILE", QStringLiteral("Android")))
{
    // As the GUI's, so profiles and settings land where Mudlet looks for them
    QCoreApplication::setOrganizationName(QStringLiteral("Mudlet"));
    QCoreApplication::setApplicationName(QStringLiteral("Mudlet"));
    qInitResources_mudlet();
    qInitResources_qm();

    mCounterTimer.setSingleShot(true);
    mCounterTimer.setInterval(100);
    connect(&mCounterTimer, &QTimer::timeout, this, &MudletEngine::flushCounters);

    installLuaFiles();
    reportFont();
    mFrontend->status(QStringLiteral("engineKind"), QStringLiteral("libmudlet"));
    mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 (not started)").arg(mProfile));
    mFrontend->status(QStringLiteral("connection"), QStringLiteral("disconnected"));

    mLastHeartbeatMs = QDateTime::currentMSecsSinceEpoch();
    mHeartbeat.setInterval(heartbeatMs);
    connect(&mHeartbeat, &QTimer::timeout, this, &MudletEngine::onHeartbeat);
    mHeartbeat.start();
}

MudletEngine::~MudletEngine()
{
    if (mSession) {
        mSession->shutdown(true);
        delete mSession;
    }
}

// Mudlet reads its Lua from a directory; on Android the files ship as assets, which Qt lists under assets:/
void MudletEngine::installLuaFiles()
{
#ifdef Q_OS_ANDROID
    QElapsedTimer clock;
    clock.start();
    const QString target = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/mudlet-lua");
    QFile stampAsset(QStringLiteral("assets:/mudlet-lua/stamp"));
    QFile stampCopy(target + QStringLiteral("/stamp"));
    const QByteArray wanted = stampAsset.open(QIODevice::ReadOnly) ? stampAsset.readAll() : QByteArray();
    const QByteArray have = stampCopy.open(QIODevice::ReadOnly) ? stampCopy.readAll() : QByteArray();
    stampCopy.close();
    if (wanted.isEmpty()) {
        mFrontend->status(QStringLiteral("luaFiles"), QStringLiteral("no assets:/mudlet-lua/stamp"));
        return;
    }
    if (wanted == have) {
        mFrontend->status(QStringLiteral("luaFiles"), QStringLiteral("up to date"));
        return;
    }
    QDir(target).removeRecursively();
    int copied = 0;
    QDirIterator it(QStringLiteral("assets:/mudlet-lua"), QDir::Files, QDirIterator::Subdirectories);
    while (it.hasNext()) {
        const QString source = it.next();
        const QString destination = target + source.mid(int(qstrlen("assets:/mudlet-lua")));
        QDir().mkpath(QFileInfo(destination).absolutePath());
        // The stamp last, so an interrupted copy is redone
        if (!source.endsWith(QStringLiteral("/stamp")) && QFile::copy(source, destination)) {
            QFile::setPermissions(destination, QFile::ReadOwner | QFile::WriteOwner);
            ++copied;
        }
    }
    QFile stampOut(target + QStringLiteral("/stamp"));
    if (stampOut.open(QIODevice::WriteOnly)) {
        stampOut.write(wanted);
    }
    mFrontend->status(QStringLiteral("luaFiles"), QStringLiteral("copied %1 in %2 ms").arg(copied).arg(clock.elapsed()));
#endif
}

void MudletEngine::reportFont()
{
    QFont font = QFontDatabase::systemFont(QFontDatabase::FixedFont);
    font.setPixelSize(14);
    mAdvancePx = QFontMetrics(font).horizontalAdvance(QLatin1Char('M'));
    mFrontend->status(QStringLiteral("fontFamily"), QFontInfo(font).family());
    mFrontend->status(QStringLiteral("advanceM"), QString::number(mAdvancePx));
    mFrontend->status(QStringLiteral("platform"), QGuiApplication::platformName());
}

bool MudletEngine::ensureStarted(const QString& host, quint16 port, bool connect)
{
    if (mStarted) {
        return true;
    }
    if (!mSession) {
        mSession = new THeadlessSession(this);
        QObject::connect(mSession, &THeadlessSession::outputLine, this, [this](const QString&, const QString& text, bool isPrompt) {
            ++(isPrompt ? mPromptsReceived : mLinesReceived);
            markCountersDirty();
            mFrontend->line(text, {}, isPrompt);
        });
        QObject::connect(mSession, &THeadlessSession::localText, this, [this](const QString&, const QString& text) {
            takeLocalText(text);
        });
        QObject::connect(mSession, &THeadlessSession::errorConsoleText, this, [this](const QString&, const QString& text) {
            mErrorPending += text;
            qsizetype end;
            while ((end = mErrorPending.indexOf(QLatin1Char('\n'))) >= 0) {
                const QString line = mErrorPending.left(end);
                mErrorPending.remove(0, end + 1);
                if (!line.trimmed().isEmpty()) {
                    ++mErrorLines;
                    markCountersDirty();
                    qWarning("mudlet error console: %s", qPrintable(line));
                    mFrontend->status(QStringLiteral("luaError"), line);
                }
            }
        });
        QObject::connect(mSession, &THeadlessSession::profileLoaded, this, [this](const QString& profile) {
            mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 loaded").arg(profile));
            mFrontend->status(QStringLiteral("profileLoadMs"), QString::number(mStartClock.elapsed()));
        });
        QObject::connect(mSession, &THeadlessSession::connected, this, [this] {
            mFrontend->status(QStringLiteral("connection"), QStringLiteral("connected"));
        });
        QObject::connect(mSession, &THeadlessSession::disconnected, this, [this] {
            mFrontend->status(QStringLiteral("connection"), QStringLiteral("disconnected"));
        });
        QObject::connect(mSession, &THeadlessSession::quitRequested, this, [this] {
            mStarted = false;
            mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 closed").arg(mProfile));
        });
    }
    THeadlessSession::Options options;
    options.profile = mProfile;
    options.connect = connect;
    options.host = host;
    options.port = port;
    mStartClock.start();
    const auto result = mSession->start(options);
    mFrontend->status(QStringLiteral("startMs"), QString::number(mStartClock.elapsed()));
    if (result != THeadlessSession::StartResult::started) {
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("start failed (%1): %2").arg(int(result)).arg(mSession->errorString()));
        return false;
    }
    mStarted = true;
    return true;
}

void MudletEngine::connectTo(const QString& host, quint16 port)
{
    mFrontend->status(QStringLiteral("connection"), QStringLiteral("connecting %1:%2").arg(host).arg(port));
    if (!mStarted) {
        ensureStarted(host, port, true);
        return;
    }
    if (!mSession->runLua(QStringLiteral("connectToServer(%1, %2)").arg(luaLongString(host)).arg(port))) {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("error: %1").arg(mSession->errorString()));
    }
}

void MudletEngine::send(const QString& line)
{
    if (!mStarted) {
        mFrontend->status(QStringLiteral("sendError"), QStringLiteral("no profile running"));
        return;
    }
    if (!mSession->submit(line)) {
        mFrontend->status(QStringLiteral("sendError"), mSession->errorString());
    }
}

void MudletEngine::disconnectFromGame()
{
    if (mStarted) {
        mSession->runLua(QStringLiteral("disconnect()"));
    }
}

void MudletEngine::runLua(const QString& code)
{
    if (!ensureStarted({}, 0, false)) {
        mFrontend->status(QStringLiteral("luaResult"), QStringLiteral("no profile"));
        return;
    }
    const bool ok = mSession->runLua(code);
    mFrontend->status(QStringLiteral("luaResult"), ok ? QStringLiteral("ran") : QStringLiteral("refused: %1").arg(mSession->errorString()));
}

void MudletEngine::setViewWidthPx(int px)
{
    const int columns = mAdvancePx > 0 ? px / mAdvancePx : 0;
    mFrontend->status(QStringLiteral("viewWidthPx"), QString::number(px));
    mFrontend->status(QStringLiteral("columns"), QString::number(columns));
}

// Pieces that need not end a line; shown as whole lines in the local colour
void MudletEngine::takeLocalText(const QString& text)
{
    mLocalPending += text;
    qsizetype end;
    while ((end = mLocalPending.indexOf(QLatin1Char('\n'))) >= 0) {
        const QString line = mLocalPending.left(end);
        mLocalPending.remove(0, end + 1);
        ++mLocalLines;
        markCountersDirty();
        mFrontend->line(line, {ColorRun{0, int(line.size()), localTextFg}}, false);
    }
}

void MudletEngine::markCountersDirty()
{
    mCountersDirty = true;
    if (!mCounterTimer.isActive()) {
        mCounterTimer.start();
    }
}

void MudletEngine::flushCounters()
{
    if (!mCountersDirty) {
        return;
    }
    mCountersDirty = false;
    mFrontend->status(QStringLiteral("linesReceived"), QString::number(mLinesReceived));
    mFrontend->status(QStringLiteral("promptsReceived"), QString::number(mPromptsReceived));
    mFrontend->status(QStringLiteral("localLines"), QString::number(mLocalLines));
    mFrontend->status(QStringLiteral("errorConsoleLines"), QString::number(mErrorLines));
}

void MudletEngine::onHeartbeat()
{
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    const qint64 gap = now - mLastHeartbeatMs;
    mLastHeartbeatMs = now;
    ++mHeartbeatTicks;
    mMaxHeartbeatGapMs = std::max(mMaxHeartbeatGapMs, gap);
    mFrontend->status(QStringLiteral("heartbeatTicks"), QString::number(mHeartbeatTicks));
    mFrontend->status(QStringLiteral("heartbeatLastGapMs"), QString::number(gap));
    mFrontend->status(QStringLiteral("heartbeatMaxGapMs"), QString::number(mMaxHeartbeatGapMs));
}
