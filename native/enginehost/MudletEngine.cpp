#include "MudletEngine.h"

#include "THeadlessSession.h"

#include <QCoreApplication>
#include <QDataStream>
#include <QDateTime>
#include <QDir>
#include <QEventLoop>
#include <QFile>
#include <QFileInfo>
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
            {TextStyle::Bold, ColorRun::bold},
            {TextStyle::Italic, ColorRun::italic},
            {TextStyle::Underline, ColorRun::underline},
            {TextStyle::StrikeOut, ColorRun::strikeOut},
            {TextStyle::Reverse, ColorRun::reverse},
            {TextStyle::Overline, ColorRun::overline},
            {TextStyle::Blink, ColorRun::blink},
            {TextStyle::FastBlink, ColorRun::fastBlink},
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
        const ColorRun converted{run.start, run.length, colourCode(run.foreground, run.defaultForeground), colourCode(run.background, run.defaultBackground), attributeBits(run.styles), run.link};
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

QString settleConfigDir()
{
    // As the GUI's, as the session reads Mudlet.ini and the keychain under them, and AppDataLocation follows them
    QCoreApplication::setOrganizationName(QStringLiteral("Mudlet"));
    QCoreApplication::setApplicationName(QStringLiteral("Mudlet"));
    // The profiles, Mudlet.ini and the passwords Mudlet does not give the KeyStore, in the app's own files
    QString dir = qEnvironmentVariable("MUDLET_CONFIG_DIR");
    if (dir.isEmpty()) {
        dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/mudlet");
    }
    return QDir(dir).absolutePath();
}

} // namespace

MudletEngine::MudletEngine(IFrontend* frontend, int heartbeatMs, QObject* parent)
: QObject(parent)
, mFrontend(frontend)
, mProfile(qEnvironmentVariable("MUDLET_PROFILE", QStringLiteral("Android")))
, mDefaultProfile(mProfile)
, mConfigDir(settleConfigDir())
, mProfiles(mConfigDir)
{
    // What a restart of the engine brings back, in the app's files beside the profiles; on Android the app names
    // both files (EngineSession.kt)
    QString statePath = qEnvironmentVariable("MUDLET_ENGINE_STATE");
    if (statePath.isEmpty()) {
        statePath = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/engine-session.json");
    }
    QString stopMarkerPath = qEnvironmentVariable("MUDLET_ENGINE_STOPPED");
    if (stopMarkerPath.isEmpty()) {
        stopMarkerPath = QFileInfo(statePath).absoluteDir().filePath(QStringLiteral("engine-stopped"));
    }
    mStore = std::make_unique<SessionStore>(statePath, stopMarkerPath);

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

    mRestoreTimer.setSingleShot(true);
    connect(&mRestoreTimer, &QTimer::timeout, this, &MudletEngine::beginRestore);
    takeRestore();
}

MudletEngine::~MudletEngine()
{
    // An orderly stop, as the user's: the next engine starts with no profile
    mClosing = true;
    mStore->clear();
    if (mState == State::started || mState == State::stopping) {
        shutdownSession(mState == State::stopping);
    }
    // Nothing the session sends as it goes may reach the members already destroyed
    QObject::disconnect(mSession.get(), nullptr, this, nullptr);
    mSession.reset();
}

// The profile gets its sysExitEvent and its save, waited for a few seconds at most, as the process is ending. One
// already closing for a switch is only waited for, as a second shutdown would be refused at once.
void MudletEngine::shutdownSession(bool alreadyClosing)
{
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
    if (!alreadyClosing) {
        mSession->shutdownAsync(true);
    }
    loop.exec();
}

// What the lines, mid-write signals among them, do here only touches the frontend: they must not call the session
void MudletEngine::connectSession()
{
    THeadlessSession* session = mSession.get();
    connect(session, &THeadlessSession::startFinished, this, [this](THeadlessSession::StartResult result, const QString& error) {
        onStartFinished(int(result), error);
    });
    connect(session, &THeadlessSession::shutdownFinished, this, [this](bool saved, const QString& error) {
        if (!mClosing && mState == State::stopping) {
            onShutdownFinished(saved, error);
        }
    });
    // Counted only: what the UI shows comes from lineShown, once the line's triggers have run
    connect(session, &THeadlessSession::outputLine, this, [this](const QString&, const QString&, bool isPrompt) {
        ++(isPrompt ? mPromptsReceived : mLinesReceived);
        markCountersDirty();
    });
    connect(session, &THeadlessSession::lineShown, this, [this](const QString&, const QString& text, bool isPrompt, const QList<TextRun>& runs, qint64, bool continuesLine) {
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
    connect(session, &THeadlessSession::announcementRequested, this, [this](const QString&, const QString& text, bool assertive) {
        mFrontend->announce(text, assertive);
    });
    connect(session, &THeadlessSession::consoleColorsChanged, this, [this](const QString&, const QColor& foreground, const QColor& background) {
        reportConsoleColors(foreground, background);
    });
    connect(session, &THeadlessSession::profileLoaded, this, [this](const QString& profile) {
        mProfiles.markOpened(profile);
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 loaded").arg(profile));
        mFrontend->status(QStringLiteral("profileLoadMs"), QString::number(mStartClock.elapsed()));
        mFrontend->history(mSession->commandHistory());
        reportConsoleColors(mSession->consoleForeground(), mSession->consoleBackground());
        // As early as the profile takes it, so a game that asks as it connects is not first told 90 by 25
        applyScreenSize();
    });
    connect(session, &THeadlessSession::hiddenInputWanted, this, [this](const QString&, bool wanted) {
        mFrontend->hiddenInput(wanted);
    });
    connect(session, &THeadlessSession::commandLineTextRequested, this, [this](const QString&, const QString& text) {
        mFrontend->commandLine(text);
    });
    connect(session, &THeadlessSession::connecting, this, [this] {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("connecting"));
    });
    connect(session, &THeadlessSession::connected, this, [this] {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("connected"));
        // mKept is already the next profile's
        if (mState == State::stopping) {
            return;
        }
        mDisconnectRequested = false;
        mKept.connect = true;
        persist();
        if (std::exchange(mRestoring, false)) {
            restoreStatus(QStringLiteral("reconnected"), restoreTarget());
        }
    });
    connect(session, &THeadlessSession::disconnected, this, [this] {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("disconnected"));
        if (mState == State::stopping) {
            return;
        }
        // Lost or refused rather than ended by the UI: wanted still only if Mudlet tries again, as the profile says
        if (!std::exchange(mDisconnectRequested, false)) {
            mKept.connect = profileReconnects();
        }
        persist();
        if (!mRestoring) {
            return;
        }
        // Mudlet tries again on its own for a profile that reconnects automatically; the engine does not add tries
        if (mKept.connect) {
            restoreStatus(QStringLiteral("retrying"), restoreTarget());
        } else {
            mRestoring = false;
            restoreStatus(QStringLiteral("notReconnected"), restoreTarget());
        }
    });
    connect(session, &THeadlessSession::quitRequested, this, [this] {
        // A switch's own close finishes it
        if (mState == State::stopping) {
            return;
        }
        mState = State::idle;
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 closed").arg(mProfile));
        mKept = {};
        persist();
        mRestoring = false;
    });
}

// At start-up: the profile the engine before this one ran, if it was killed rather than stopped
void MudletEngine::takeRestore()
{
    const SessionStore::Restore restore = mStore->takeRestore(QDateTime::currentMSecsSinceEpoch());
    switch (restore.kind) {
    case SessionStore::Restore::Kind::none:
        return;
    case SessionStore::Restore::Kind::gaveUp:
        qWarning("MudletEngine: not restoring %s: the engine stopped unexpectedly %d times in %lld minutes",
                 qPrintable(restore.session.profile),
                 restore.attempt,
                 SessionStore::restoreWindowMs / 60000);
        restoreStatus(QStringLiteral("gaveUp"), QStringLiteral("%1 %2").arg(restore.attempt).arg(SessionStore::restoreWindowMs / 60000));
        return;
    case SessionStore::Restore::Kind::restore:
        break;
    }
    mKept = restore.session;
    mSavedKept = mKept;
    mProfile = mKept.profile;
    mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 (not started)").arg(mProfile));
    if (mKept.connect && restore.reconnectDelayMs > 0) {
        restoreStatus(QStringLiteral("waiting"), QStringLiteral("%1 %2").arg(restore.reconnectDelayMs / 1000).arg(restoreTarget()));
        mRestoreTimer.start(restore.reconnectDelayMs);
        return;
    }
    // From the event loop, once the engine listens, as the load takes a while
    mRestoreTimer.start(0);
}

void MudletEngine::beginRestore()
{
    if (mState != State::idle) {
        return;
    }
    mRestoring = true;
    if (mKept.connect) {
        restoreStatus(QStringLiteral("reconnecting"), restoreTarget());
    } else {
        restoreStatus(QStringLiteral("restoring"), mProfile);
    }
    start(mKept.host, quint16(mKept.port), mKept.connect, mKept.tls);
}

// The UI took over: the restore no longer reports, nor connects later
void MudletEngine::cancelRestore()
{
    if (!mRestoreTimer.isActive() && !mRestoring) {
        return;
    }
    mRestoreTimer.stop();
    mRestoring = false;
    restoreStatus(QStringLiteral("cancelled"));
}

// docs/protocol.md, "The restore status": a phase, then what it is about
void MudletEngine::restoreStatus(const QString& phase, const QString& detail)
{
    const QString value = detail.isEmpty() ? phase : QStringLiteral("%1 %2").arg(phase, detail);
    qInfo("MudletEngine: restore: %s", qPrintable(value));
    mFrontend->status(QStringLiteral("restore"), value);
}

void MudletEngine::persist()
{
    if (mClosing || mKept == mSavedKept) {
        return;
    }
    mSavedKept = mKept;
    mStore->save(mKept);
}

QString MudletEngine::restoreTarget() const
{
    return mKept.host.isEmpty() ? mProfile : QStringLiteral("%1:%2").arg(mKept.host).arg(mKept.port);
}

// Mudlet's "Reconnect automatically" for the profile, as its connection dialog stores it
bool MudletEngine::profileReconnects() const
{
    return mProfiles.read(mProfile).autoReconnect;
}

void MudletEngine::reportFont()
{
    QFont font = QFontDatabase::systemFont(QFontDatabase::FixedFont);
    font.setPixelSize(14);
    mFrontend->status(QStringLiteral("fontFamily"), QFontInfo(font).family());
    mFrontend->status(QStringLiteral("advanceM"), QString::number(QFontMetrics(font).horizontalAdvance(QLatin1Char('M'))));
    mFrontend->status(QStringLiteral("platform"), QGuiApplication::platformName());
}

void MudletEngine::start(const QString& host, quint16 port, bool connect, std::optional<bool> tls, const QString& password)
{
    THeadlessSession::Options options;
    options.profile = mProfile;
    options.connect = connect;
    options.host = host;
    options.port = port;
    // Stored with the profile, as the connection dialog's box stores it; unset, the profile's own
    options.tls = tls;
    // For this run only: THeadlessSession stores none, and a restore has none
    options.password = password;
    mProfileTls = tls;
    if (const QString folder = mProfiles.find(mProfile); !tls.has_value() && !folder.isEmpty()) {
        mProfileTls = mProfiles.read(folder).tls;
    }
    options.configDir = mConfigDir;
    mKept.profile = mProfile;
    mKept.tls = tls;
    persist();
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
        mKept.profile.clear();
        mKept.connect = false;
        persist();
        if (std::exchange(mRestoring, false)) {
            restoreStatus(QStringLiteral("failed"), error);
        }
        if (mPendingOpen) {
            openPending();
        }
        return;
    }
    mState = State::started;
    // Again, for a setScreenSize that came between profileLoaded and now; the same size changes nothing
    applyScreenSize();
    if (mRestoring && !mKept.connect) {
        mRestoring = false;
        restoreStatus(QStringLiteral("restored"), mProfile);
    }
    for (const QString& code : pending) {
        runNow(code);
    }
    // Opened while this one started
    if (mPendingOpen) {
        beginSwitch();
    }
}

void MudletEngine::runNow(const QString& code)
{
    const bool ok = mSession->runLua(code);
    mFrontend->status(QStringLiteral("luaResult"), ok ? QStringLiteral("ran") : QStringLiteral("refused: %1").arg(mSession->errorString()));
}

void MudletEngine::connectTo(const QString& host, quint16 port, std::optional<bool> tls)
{
    if (mPendingOpen) {
        cancelRestore();
        mPendingOpen->host = host;
        mPendingOpen->port = port;
        mPendingOpen->tls = tls;
        mPendingOpen->connect = true;
        keepPending();
        return;
    }
    // A running profile connects as it was started: THeadlessSession takes the TLS choice only in start(), and
    // connectToServer() keeps the profile's. A connect refused for that changes nothing, a restore included.
    if (mState != State::idle && tls.has_value() && tls != mProfileTls) {
        QString started = QStringLiteral("with its stored choice");
        if (mProfileTls.has_value()) {
            started = *mProfileTls ? QStringLiteral("with TLS") : QStringLiteral("without TLS");
        }
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("error: TLS is chosen when the profile starts, and it started %1; stop the engine to change it").arg(started));
        return;
    }
    const QString code = QStringLiteral("connectToServer(%1, %2)").arg(luaLongString(host)).arg(port);
    cancelRestore();
    mDisconnectRequested = false;
    mKept.host = host;
    mKept.port = port;
    mKept.connect = true;
    persist();
    switch (mState) {
    case State::idle:
        start(host, port, true, tls);
        return;
    case State::starting:
        mPendingLua.append(code);
        return;
    case State::started:
        if (!mSession->runLua(code)) {
            mFrontend->status(QStringLiteral("connection"), QStringLiteral("error: %1").arg(mSession->errorString()));
        }
        return;
    case State::stopping:
        return;
    }
}

bool MudletEngine::takesInput()
{
    if (mState != State::started) {
        mFrontend->status(QStringLiteral("sendError"), QStringLiteral("no profile running"));
        return false;
    }
    return true;
}

void MudletEngine::send(const QString& line)
{
    if (takesInput() && !mSession->submit(line)) {
        mFrontend->status(QStringLiteral("sendError"), mSession->errorString());
    }
}

// Empty with no profile open
QStringList MudletEngine::history() const
{
    return mSession->commandHistory();
}

// The errors never quote the line, which THeadlessSession clears from its own copy once sent
void MudletEngine::sendHidden(const QString& line)
{
    if (takesInput() && !mSession->submitHiddenInput(line)) {
        mFrontend->status(QStringLiteral("sendError"), mSession->errorString());
    }
}

void MudletEngine::dismissHidden()
{
    if (takesInput() && !mSession->dismissHiddenInput()) {
        mFrontend->status(QStringLiteral("sendError"), mSession->errorString());
    }
}

// As Tab, or the up arrow with "Highlight history" on, on the GUI's command line holding text: calls that hand back
// the line the last one gave go on to the next word or older line
QString MudletEngine::complete(const QString& text, bool fromHistory)
{
    if (mState != State::started || !mSession->setCommandLineText(text)) {
        return {};
    }
    return (fromHistory ? mSession->completeCommandLineFromHistory() : mSession->completeCommandLine()).value_or(QString());
}

void MudletEngine::disconnectFromGame()
{
    // The user's disconnect: a restart of the engine brings the profile back, but does not connect it
    const bool waiting = mRestoreTimer.isActive();
    cancelRestore();
    if (mPendingOpen) {
        mPendingOpen->connect = false;
        keepPending();
        return;
    }
    mDisconnectRequested = true;
    mKept.connect = false;
    persist();
    if (waiting && !mPendingLua.isEmpty()) {
        // Lua held back for the restore runs all the same
        start({}, 0, false, mKept.tls);
    }
    if (mState == State::started && !mSession->runLua(QStringLiteral("disconnect()"))) {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("error: %1").arg(mSession->errorString()));
    }
}

void MudletEngine::runLua(const QString& code)
{
    if (mPendingOpen) {
        mPendingOpen->lua.append(code);
        return;
    }
    switch (mState) {
    case State::idle:
        mPendingLua.append(code);
        // A restore that waits to connect starts the profile, and runs it then
        if (!mRestoreTimer.isActive()) {
            start({}, 0, false, std::nullopt);
        }
        return;
    case State::starting:
        mPendingLua.append(code);
        return;
    case State::started:
        runNow(code);
        return;
    case State::stopping:
        // Not reached: a switch always has its open pending
        return;
    }
}

QString MudletEngine::saveProfile(const Profile& profile)
{
    Profile stored = profile;
    const QString folder = mProfiles.find(profile.name);
    if (!folder.isEmpty()) {
        stored.name = folder;
    } else if (const QString problem = Profile::newNameProblem(profile.name); !problem.isEmpty()) {
        return problem;
    }
    // A running profile read these when it started: they take effect at its next start
    if (const QString problem = mProfiles.save(stored, folder.isEmpty()); !problem.isEmpty()) {
        return QStringLiteral("could not store \"%1\": %2").arg(stored.name, problem);
    }
    return {};
}

QString MudletEngine::deleteProfile(const QString& name)
{
    const QString folder = mProfiles.find(name);
    if (folder.isEmpty()) {
        return QStringLiteral("there is no profile named \"%1\"").arg(name);
    }
    if (inUse(folder)) {
        return QStringLiteral("\"%1\" is open: open another profile before deleting it").arg(folder);
    }
    if (const QString problem = mProfiles.remove(folder); !problem.isEmpty()) {
        return problem;
    }
    // Not to be made again, empty, by the next connect
    if (folder.compare(mProfile, Qt::CaseInsensitive) == 0) {
        mProfile = mDefaultProfile;
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 (not started)").arg(mProfile));
    }
    return {};
}

// Running, about to run, closing, or waited for by a restore
bool MudletEngine::inUse(const QString& folder) const
{
    const auto same = [&folder](const QString& name) {
        return folder.compare(name, Qt::CaseInsensitive) == 0;
    };
    return (mState != State::idle && same(mProfile)) || (mPendingOpen && same(mPendingOpen->profile)) || (mRestoreTimer.isActive() && same(mKept.profile));
}

QString MudletEngine::openProfile(const QString& name, const QString& password, bool connect)
{
    const QString folder = mProfiles.find(name);
    if (folder.isEmpty()) {
        return QStringLiteral("there is no profile named \"%1\"").arg(name);
    }
    cancelRestore();
    mProfiles.markOpened(folder);
    if ((mState == State::starting || mState == State::started) && folder.compare(mProfile, Qt::CaseInsensitive) == 0) {
        // THeadlessSession takes a password only at a start, so a running profile logs in with the one it started with
        mPendingOpen.reset();
        if (connect) {
            connectProfile();
        }
        return {};
    }
    mPendingOpen.emplace();
    mPendingOpen->profile = folder;
    mPendingOpen->password = password;
    mPendingOpen->connect = connect;
    switch (mState) {
    case State::idle:
        openPending();
        break;
    case State::started:
        beginSwitch();
        break;
    case State::starting:
        // onStartFinished() switches once that start is over
    case State::stopping:
        keepPending();
        break;
    }
    return {};
}

// The running profile, started without connecting or since disconnected, connects to its own address
void MudletEngine::connectProfile()
{
    if (mKept.connect) {
        return;
    }
    const Profile profile = mProfiles.read(mProfile);
    const QString code = QStringLiteral("connectToServer(%1, %2)").arg(luaLongString(profile.host)).arg(profile.port);
    mDisconnectRequested = false;
    mKept.host.clear();
    mKept.port = 0;
    mKept.connect = true;
    persist();
    if (mState == State::starting) {
        mPendingLua.append(code);
    } else if (!mSession->runLua(code)) {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("error: %1").arg(mSession->errorString()));
    }
}

// The running profile closes and saves, as Mudlet's own close does, before the pending one starts
void MudletEngine::beginSwitch()
{
    mState = State::stopping;
    mKeptBeforeSwitch = mKept;
    keepPending();
    mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 closing").arg(mProfile));
    mSession->shutdownAsync(true);
}

void MudletEngine::onShutdownFinished(bool saved, const QString& error)
{
    // Refused, as while a script runs: the profile is still running
    if (mSession->host()) {
        mState = State::started;
        mPendingOpen.reset();
        mKept = mKeptBeforeSwitch;
        persist();
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 not closed: %2").arg(mProfile, error));
        return;
    }
    mState = State::idle;
    if (saved) {
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 closed").arg(mProfile));
    } else {
        qWarning("MudletEngine: %s closed without its save: %s", qPrintable(mProfile), qPrintable(error));
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 closed without its save: %2").arg(mProfile, error));
    }
    openPending();
}

void MudletEngine::openPending()
{
    PendingOpen open = std::move(*mPendingOpen);
    mPendingOpen.reset();
    mProfile = open.profile;
    mKept = open.kept();
    mDisconnectRequested = false;
    // After any Lua a waiting restore held back, which runs all the same
    mPendingLua.append(open.lua);
    start(open.host, open.port, open.connect, open.tls, open.password);
}

// From the switch's start, a restart of the engine brings back the profile being opened, not the one closing
void MudletEngine::keepPending()
{
    if (mState != State::stopping) {
        return;
    }
    mKept = mPendingOpen->kept();
    persist();
}

void MudletEngine::setScreenSize(int columns, int rows)
{
    mScreenSize = QSize(columns, rows);
    if (mState == State::started) {
        applyScreenSize();
    }
}

void MudletEngine::applyScreenSize()
{
    if (mScreenSize.isEmpty()) {
        return;
    }
    if (!mSession->setScreenSize(mScreenSize.width(), mScreenSize.height())) {
        qWarning("MudletEngine: setScreenSize(%d, %d) refused: %s", mScreenSize.width(), mScreenSize.height(), qPrintable(mSession->errorString()));
    }
}

void MudletEngine::reportConsoleColors(const QColor& foreground, const QColor& background)
{
    if (foreground.isValid() && background.isValid()) {
        mFrontend->status(QStringLiteral("consoleColors"), QStringLiteral("%1 %2").arg(foreground.name(), background.name()));
    }
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
    mSentTail = {};
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
    if (!mSentTail.text.isEmpty()) {
        mSentTail = {};
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
    if (mOpen.text == mSentTail.text && mOpen.runs == mSentTail.runs && mOpen.prompt == mSentTail.prompt) {
        return;
    }
    mSentTail = mOpen;
    mFrontend->tail(mSentTail.text, mSentTail.runs, mSentTail.prompt);
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
