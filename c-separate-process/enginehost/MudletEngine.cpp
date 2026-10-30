#include "MudletEngine.h"

#include "Host.h"
#include "TBuffer.h"
#include "TConsoleModel.h"
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
        // Counted only: what the UI shows comes from the console model once triggers have had their go
        QObject::connect(mSession, &THeadlessSession::outputLine, this, [this](const QString&, const QString&, bool isPrompt) {
            ++(isPrompt ? mPromptsReceived : mLinesReceived);
            markCountersDirty();
        });
        QObject::connect(mSession, &THeadlessSession::localText, this, [this](const QString&, const QString& text) {
            mLocalLines += text.count(QLatin1Char('\n'));
            markCountersDirty();
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
    watchConsole();
    return true;
}

void MudletEngine::watchConsole()
{
    Host* host = mSession->host();
    if (!host) {
        return;
    }
    mSentLines = 0;
    TConsoleModelNotifier& notifier = host->mainConsoleModel().mNotifier;
    // Most of these fire mid-write or mid-trigger-pass; the sync waits for the event loop, when the line's
    // triggers have run
    for (auto signal : {&TConsoleModelNotifier::lineCommitted, &TConsoleModelNotifier::linesAppended}) {
        QObject::connect(&notifier, signal, this, &MudletEngine::scheduleSync);
    }
    for (auto signal : {&TConsoleModelNotifier::newLinesWritten, &TConsoleModelNotifier::serverWrapLineHeld}) {
        QObject::connect(&notifier, signal, this, &MudletEngine::scheduleSync);
    }
    QObject::connect(&notifier, &TConsoleModelNotifier::linesChanged, this, &MudletEngine::scheduleSync);
    QObject::connect(&notifier, &TConsoleModelNotifier::textPrinted, this, &MudletEngine::scheduleSync);
    QObject::connect(&notifier, &TConsoleModelNotifier::bufferCleared, this, [this] {
        mSentLines = 0;
        scheduleSync();
    });
    QObject::connect(&notifier, &TConsoleModelNotifier::linesRemoved, this, [this](int first, int last) {
        if (last < mSentLines) {
            mSentLines -= last - first + 1;
        } else if (first < mSentLines) {
            mSentLines = first;
        }
        scheduleSync();
    });
    scheduleSync();
}

void MudletEngine::scheduleSync()
{
    if (mSyncPending) {
        return;
    }
    mSyncPending = true;
    QTimer::singleShot(0, this, &MudletEngine::syncConsole);
}

namespace {

int colourCode(QRgb colour, QRgb defaultColour)
{
    return (colour & RGB_MASK) == (defaultColour & RGB_MASK) ? -1 : int(ColorRun::rgbFlag | (colour & RGB_MASK));
}

int attributeBits(const TChar& c)
{
    return (c.isBold() ? ColorRun::bold : 0) | (c.isItalic() ? ColorRun::italic : 0) | (c.isUnderlined() ? ColorRun::underline : 0)
         | (c.isStruckOut() ? ColorRun::strikeOut : 0) | (c.isReversed() ? ColorRun::reverse : 0);
}

// One logical line: buffer lines first..last, the ones after the first split off it by word wrapping
void appendLogicalLine(const TBuffer& buffer, int first, int last, QRgb fg, QRgb bg, QString& text, QList<ColorRun>& runs)
{
    auto addChar = [&](QChar ch, int cFg, int cBg, int cAttributes) {
        const int at = int(text.size());
        text.append(ch);
        if (!runs.isEmpty()) {
            ColorRun& run = runs.last();
            if (run.fg == cFg && run.bg == cBg && run.attributes == cAttributes && run.start + run.length == at) {
                ++run.length;
                return;
            }
        }
        if (cFg != -1 || cBg != -1 || cAttributes != 0) {
            runs.append(ColorRun{at, 1, cFg, cBg, cAttributes});
        }
    };
    for (int y = first; y <= last; ++y) {
        const QString& lineText = buffer.lineBuffer.at(y);
        const auto& chars = buffer.buffer.at(y);
        int from = 0;
        if (y > first) {
            for (int gap = buffer.wrapGapBefore(y); gap > 0; --gap) {
                addChar(QLatin1Char(' '), -1, -1, 0);
            }
            from = std::min(int(lineText.size()), buffer.wrapIndentWidth(y));
        }
        for (int x = from; x < lineText.size(); ++x) {
            if (x < int(chars.size())) {
                const TChar& c = chars.at(x);
                addChar(lineText.at(x), colourCode(c.foregroundRgba(), fg), colourCode(c.backgroundRgba(), bg), attributeBits(c));
            } else {
                addChar(lineText.at(x), -1, -1, 0);
            }
        }
    }
}

} // namespace

// Sends the main console's finished lines as they stand after their triggers ran, gagged ones gone, and the
// open line at the bottom as the tail
void MudletEngine::syncConsole()
{
    mSyncPending = false;
    Host* host = mSession ? mSession->host() : nullptr;
    if (!host) {
        return;
    }
    const TBuffer& buffer = host->mainConsoleModel().buffer;
    const QRgb fg = host->mFgColor.rgba();
    const QRgb bg = host->mBgColor.rgba();
    const int size = int(buffer.lineBuffer.size());
    mSentLines = std::clamp(mSentLines, 0, size);
    int y = mSentLines;
    while (y < size) {
        int last = y;
        while (last + 1 < size && buffer.wrapsFromPreviousLine(last + 1)) {
            ++last;
        }
        QString text;
        QList<ColorRun> runs;
        appendLogicalLine(buffer, y, last, fg, bg, text, runs);
        const bool prompt = buffer.promptBuffer.at(last);
        if (last == size - 1) {
            if (text != mTailText || runs != mTailRuns || prompt != mTailPrompt) {
                mTailText = text;
                mTailRuns = runs;
                mTailPrompt = prompt;
                mFrontend->tail(text, runs, prompt);
            }
            break;
        }
        mFrontend->line(text, runs, prompt);
        y = last + 1;
    }
    mSentLines = y;
    if (y >= size && !mTailText.isEmpty()) {
        mTailText.clear();
        mTailRuns.clear();
        mTailPrompt = false;
        mFrontend->tail({}, {}, false);
    }
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
