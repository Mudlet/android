#include "MudletEngine.h"

#include "THeadlessSession.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QEventLoop>
#include <QFontDatabase>
#include <QFontInfo>
#include <QFontMetrics>
#include <QGuiApplication>
#include <QStandardPaths>

namespace {

using TextRun = THeadlessSession::TextRun;
using TextStyle = THeadlessSession::TextStyle;

QString luaLongString(const QString& text)
{
    return QStringLiteral("[==[%1]==]").arg(text);
}

int colourCode(const QColor& colour, bool profileDefault)
{
    return profileDefault || !colour.isValid() ? -1 : int(ColorRun::rgbFlag | (colour.rgb() & 0xFFFFFF));
}

int attributeBits(THeadlessSession::TextStyles styles)
{
    static constexpr std::pair<TextStyle, int> bits[] = {
            {TextStyle::Bold, ColorRun::bold},           {TextStyle::Italic, ColorRun::italic},
            {TextStyle::Underline, ColorRun::underline}, {TextStyle::StrikeOut, ColorRun::strikeOut},
            {TextStyle::Reverse, ColorRun::reverse},     {TextStyle::Overline, ColorRun::overline},
            {TextStyle::Blink, ColorRun::blink},         {TextStyle::FastBlink, ColorRun::fastBlink},
            {TextStyle::Concealed, ColorRun::concealed},
    };
    int attributes = 0;
    for (const auto& [style, bit] : bits) {
        if (styles.testFlag(style)) {
            attributes |= bit;
        }
    }
    return attributes;
}

// Runs that are the profile's own colours with no style or link are left out, as the stand-in leaves them
QList<ColorRun> colorRuns(const QList<TextRun>& runs)
{
    QList<ColorRun> out;
    for (const TextRun& run : runs) {
        const ColorRun converted{run.start, run.length, colourCode(run.foreground, run.defaultForeground),
                                 colourCode(run.background, run.defaultBackground), attributeBits(run.styles), run.link};
        if (converted.length > 0 && (converted.fg != -1 || converted.bg != -1 || converted.attributes != 0 || converted.link != 0)) {
            out.append(converted);
        }
    }
    return out;
}

// The runs over [from, to) of a text, counted from from
QList<ColorRun> sliceRuns(const QList<ColorRun>& runs, int from, int to)
{
    QList<ColorRun> out;
    for (ColorRun run : runs) {
        const int start = std::max(run.start, from);
        const int end = std::min(run.start + run.length, to);
        if (start < end) {
            run.start = start - from;
            run.length = end - start;
            out.append(run);
        }
    }
    return out;
}

} // namespace

MudletEngine::MudletEngine(IFrontend* frontend, int heartbeatMs, QObject* parent)
: QObject(parent)
, mFrontend(frontend)
, mProfile(qEnvironmentVariable("MUDLET_PROFILE", QStringLiteral("Android")))
{
    // As the GUI's, as the session reads Mudlet.ini and the keychain under them
    QCoreApplication::setOrganizationName(QStringLiteral("Mudlet"));
    QCoreApplication::setApplicationName(QStringLiteral("Mudlet"));
    // The profiles, Mudlet.ini and the passwords Mudlet does not give the KeyStore, in the app's own files
    mConfigDir = qEnvironmentVariable("MUDLET_CONFIG_DIR");
    if (mConfigDir.isEmpty()) {
        mConfigDir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/mudlet");
    }
    mConfigDir = QDir(mConfigDir).absolutePath();

    mSession = std::make_unique<THeadlessSession>();
    connectSession();

    mCounterTimer.setSingleShot(true);
    mCounterTimer.setInterval(100);
    connect(&mCounterTimer, &QTimer::timeout, this, &MudletEngine::flushCounters);

    reportFont();
    mFrontend->status(QStringLiteral("engineKind"), QStringLiteral("libmudlet"));
    mFrontend->status(QStringLiteral("configDir"), mConfigDir);
    mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 (not started)").arg(mProfile));
    mFrontend->status(QStringLiteral("connection"), QStringLiteral("disconnected"));

    mLastHeartbeatMs = QDateTime::currentMSecsSinceEpoch();
    mHeartbeat.setInterval(heartbeatMs);
    connect(&mHeartbeat, &QTimer::timeout, this, &MudletEngine::onHeartbeat);
    mHeartbeat.start();
}

// The profile gets its sysExitEvent and its save, waited for a few seconds at most, as the process is ending
MudletEngine::~MudletEngine()
{
    if (mState != State::started) {
        return;
    }
    QEventLoop loop;
    QObject::connect(mSession.get(), &THeadlessSession::shutdownFinished, &loop, [&loop](bool succeeded, const QString& error) {
        if (!succeeded) {
            qWarning("MudletEngine: the profile was not saved at exit: %s", qPrintable(error));
        }
        loop.quit();
    });
    QTimer::singleShot(5000, &loop, [&loop] {
        qWarning("MudletEngine: the profile's save at exit did not finish in 5 s");
        loop.quit();
    });
    mSession->shutdownAsync(true);
    loop.exec();
}

// What the lines, mid-write signals among them, do here only touches the frontend: they must not call the session
void MudletEngine::connectSession()
{
    THeadlessSession* session = mSession.get();
    connect(session, &THeadlessSession::startFinished, this, [this](THeadlessSession::StartResult result, const QString& error) {
        onStartFinished(int(result), error);
    });
    // Counted only: what the UI shows comes from lineShown, once the line's triggers have run
    connect(session, &THeadlessSession::outputLine, this, [this](const QString&, const QString&, bool isPrompt) {
        ++(isPrompt ? mPromptsReceived : mLinesReceived);
        markCountersDirty();
    });
    connect(session, &THeadlessSession::lineShown, this,
            [this](const QString&, const QString& text, bool isPrompt, const QList<TextRun>& runs, qint64, bool continuesLine) {
                onLineShown(text, isPrompt, colorRuns(runs), continuesLine);
            });
    connect(session, &THeadlessSession::localTextStyled, this, [this](const QString&, const QString& text, const QList<TextRun>& runs, bool continuesLine) {
        mLocalLines += text.count(QLatin1Char('\n'));
        markCountersDirty();
        onLocalText(text, colorRuns(runs), continuesLine);
    });
    connect(session, &THeadlessSession::mainConsoleCleared, this, &MudletEngine::onMainConsoleCleared);
    connect(session, &THeadlessSession::errorConsoleText, this, [this](const QString&, const QString& text) {
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
    connect(session, &THeadlessSession::notificationRequested, this, [this](const QString&, const QString& title, const QString& text) {
        mFrontend->notify(title, text);
    });
    connect(session, &THeadlessSession::profileLoaded, this, [this](const QString& profile) {
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 loaded").arg(profile));
        mFrontend->status(QStringLiteral("profileLoadMs"), QString::number(mStartClock.elapsed()));
    });
    connect(session, &THeadlessSession::connecting, this, [this] {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("connecting"));
    });
    connect(session, &THeadlessSession::connected, this, [this] {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("connected"));
    });
    connect(session, &THeadlessSession::disconnected, this, [this] {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("disconnected"));
    });
    connect(session, &THeadlessSession::quitRequested, this, [this] {
        mState = State::idle;
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 closed").arg(mProfile));
    });
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

void MudletEngine::start(const QString& host, quint16 port, bool connect)
{
    THeadlessSession::Options options;
    options.profile = mProfile;
    options.connect = connect;
    options.host = host;
    options.port = port;
    options.configDir = mConfigDir;
    mState = State::starting;
    mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 starting").arg(mProfile));
    mStartClock.start();
    mSession->startAsync(options);
}

void MudletEngine::onStartFinished(int result, const QString& error)
{
    mFrontend->status(QStringLiteral("startMs"), QString::number(mStartClock.elapsed()));
    const QStringList pending = std::exchange(mPendingLua, {});
    if (THeadlessSession::StartResult(result) != THeadlessSession::StartResult::started) {
        mState = State::idle;
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("start failed (%1): %2").arg(result).arg(error));
        if (!pending.isEmpty()) {
            mFrontend->status(QStringLiteral("luaResult"), QStringLiteral("no profile"));
        }
        return;
    }
    mState = State::started;
    for (const QString& code : pending) {
        runNow(code);
    }
}

void MudletEngine::runNow(const QString& code)
{
    const bool ok = mSession->runLua(code);
    mFrontend->status(QStringLiteral("luaResult"), ok ? QStringLiteral("ran") : QStringLiteral("refused: %1").arg(mSession->errorString()));
}

void MudletEngine::connectTo(const QString& host, quint16 port)
{
    const QString code = QStringLiteral("connectToServer(%1, %2)").arg(luaLongString(host)).arg(port);
    switch (mState) {
    case State::idle:
        start(host, port, true);
        return;
    case State::starting:
        mPendingLua.append(code);
        return;
    case State::started:
        if (!mSession->runLua(code)) {
            mFrontend->status(QStringLiteral("connection"), QStringLiteral("error: %1").arg(mSession->errorString()));
        }
        return;
    }
}

void MudletEngine::send(const QString& line)
{
    if (mState != State::started) {
        mFrontend->status(QStringLiteral("sendError"), QStringLiteral("no profile running"));
        return;
    }
    if (!mSession->submit(line)) {
        mFrontend->status(QStringLiteral("sendError"), mSession->errorString());
    }
}

void MudletEngine::disconnectFromGame()
{
    if (mState == State::started) {
        mSession->runLua(QStringLiteral("disconnect()"));
    }
}

void MudletEngine::runLua(const QString& code)
{
    switch (mState) {
    case State::idle:
        mPendingLua.append(code);
        start({}, 0, false);
        return;
    case State::starting:
        mPendingLua.append(code);
        return;
    case State::started:
        runNow(code);
        return;
    }
}

void MudletEngine::setViewWidthPx(int px)
{
    const int columns = mAdvancePx > 0 ? px / mAdvancePx : 0;
    mFrontend->status(QStringLiteral("viewWidthPx"), QString::number(px));
    mFrontend->status(QStringLiteral("columns"), QString::number(columns));
}

// A line as the main console shows it once its triggers ran. It stays open, as the tail, until the next line or
// piece of text that does not continue it, so that one that does can still be put on its end.
void MudletEngine::onLineShown(const QString& text, bool isPrompt, const QList<ColorRun>& runs, bool continuesLine)
{
    if (continuesLine && mOpen.kind != OpenLine::Kind::none) {
        appendToOpen(text, runs);
        mOpen.kind = OpenLine::Kind::shown;
        mOpen.prompt = mOpen.prompt || isPrompt;
    } else {
        if (mOpen.kind == OpenLine::Kind::local) {
            mDropLineFeed = true;
        }
        commitOpen();
        mOpen = {OpenLine::Kind::shown, text, runs, isPrompt};
    }
    commitCompleteLines(OpenLine::Kind::shown);
    scheduleTailSync();
}

// Text written to the main console that the game did not send, in pieces that need not end a line
void MudletEngine::onLocalText(const QString& text, const QList<ColorRun>& runs, bool continuesLine)
{
    QString piece = text;
    QList<ColorRun> pieceRuns = runs;
    if (std::exchange(mDropLineFeed, false) && piece.startsWith(QLatin1Char('\n'))) {
        piece.remove(0, 1);
        pieceRuns = sliceRuns(pieceRuns, 1, int(text.size()));
    }
    if (piece.isEmpty()) {
        return;
    }
    if (mOpen.kind == OpenLine::Kind::local || (continuesLine && mOpen.kind != OpenLine::Kind::none)) {
        appendToOpen(piece, pieceRuns);
    } else {
        commitOpen();
        mOpen = {OpenLine::Kind::local, piece, pieceRuns, false};
    }
    commitCompleteLines(OpenLine::Kind::local);
    scheduleTailSync();
}

void MudletEngine::onMainConsoleCleared()
{
    mOpen = {};
    mDropLineFeed = false;
    mSentTailText.clear();
    mSentTailRuns.clear();
    mSentTailPrompt = false;
    mFrontend->cleared();
}

void MudletEngine::appendToOpen(const QString& text, const QList<ColorRun>& runs)
{
    const int offset = int(mOpen.text.size());
    mOpen.text.append(text);
    for (ColorRun run : runs) {
        run.start += offset;
        mOpen.runs.append(run);
    }
}

// Sends each line the open text's line feeds end. What follows the last one stays open: the rest of a line lineShown
// sent whole, which may be empty, or the start of a line localText has yet to end.
void MudletEngine::commitCompleteLines(OpenLine::Kind remainder)
{
    qsizetype end;
    while ((end = mOpen.text.indexOf(QLatin1Char('\n'))) >= 0) {
        const int size = int(mOpen.text.size());
        mFrontend->line(mOpen.text.left(end), sliceRuns(mOpen.runs, 0, int(end)), mOpen.prompt && remainder == OpenLine::Kind::local);
        mOpen.runs = sliceRuns(mOpen.runs, int(end) + 1, size);
        mOpen.text.remove(0, end + 1);
        if (remainder == OpenLine::Kind::local) {
            mOpen.kind = OpenLine::Kind::local;
            mOpen.prompt = false;
        }
    }
    if (remainder == OpenLine::Kind::local && mOpen.text.isEmpty()) {
        mOpen = {};
    }
}

void MudletEngine::commitOpen()
{
    if (mOpen.kind == OpenLine::Kind::none) {
        return;
    }
    mFrontend->line(mOpen.text, mOpen.runs, mOpen.prompt);
    mOpen = {};
    // The UI would otherwise show the line twice until the tail is next synced
    if (!mSentTailText.isEmpty()) {
        mSentTailText.clear();
        mSentTailRuns.clear();
        mSentTailPrompt = false;
        mFrontend->tail({}, {}, false);
    }
}

// Once a turn, so that a flood of lines sends only those lines and one tail
void MudletEngine::scheduleTailSync()
{
    if (mTailSyncPending) {
        return;
    }
    mTailSyncPending = true;
    QTimer::singleShot(0, this, &MudletEngine::syncTail);
}

void MudletEngine::syncTail()
{
    mTailSyncPending = false;
    if (mOpen.text == mSentTailText && mOpen.runs == mSentTailRuns && mOpen.prompt == mSentTailPrompt) {
        return;
    }
    mSentTailText = mOpen.text;
    mSentTailRuns = mOpen.runs;
    mSentTailPrompt = mOpen.prompt;
    mFrontend->tail(mSentTailText, mSentTailRuns, mSentTailPrompt);
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
