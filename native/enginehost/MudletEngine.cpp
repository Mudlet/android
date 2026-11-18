#include "MudletEngine.h"

#include "THeadlessSession.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QEventLoop>
#include <QFileInfo>
#include <QFontDatabase>
#include <QFontInfo>
#include <QFontMetrics>
#include <QGuiApplication>
#include <QStandardPaths>

#include <algorithm>

namespace {

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

bool sameName(const QString& a, const QString& b)
{
    return a.compare(b, Qt::CaseInsensitive) == 0;
}

} // namespace

MudletEngine::MudletEngine(IEngineHost* host, int heartbeatMs, QObject* parent)
: QObject(parent)
, mHost(host)
, mDefaultProfile(qEnvironmentVariable("MUDLET_PROFILE", QStringLiteral("Android")))
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

    THeadlessSession::setClipboard(
            [host] {
                return host->clipboardText();
            },
            [host](const QString& text) {
                host->setClipboardText(text);
            });
    THeadlessSession::setFilePicker([host](bool folder, const QString& title, const QString& location) {
        return host->pickFile(folder, title, location);
    });
    THeadlessSession::setWebPageOpener([host](const QUrl& url) {
        return host->openWebPage(url);
    });
    reportFont();
    mHost->engineStatus(QStringLiteral("engineKind"), QStringLiteral("libmudlet"));
    mHost->engineStatus(QStringLiteral("configDir"), mConfigDir);
    mIdle = new MudletProfile(*this, mDefaultProfile);

    mLastHeartbeatMs = QDateTime::currentMSecsSinceEpoch();
    mHeartbeat.setInterval(heartbeatMs);
    connect(&mHeartbeat, &QTimer::timeout, this, &MudletEngine::onHeartbeat);
    mHeartbeat.start();

    mRestoreChain.setSingleShot(true);
    mRestoreChain.setInterval(SessionStore::staggerMs);
    connect(&mRestoreChain, &QTimer::timeout, this, &MudletEngine::restoreNext);
    takeRestore();
    mIdle->showIdle();
}

MudletEngine::~MudletEngine()
{
    // An orderly stop, as the user's: the next engine starts with no profile
    mClosing = true;
    mRestoreChain.stop();
    mStore->clear();
    // THeadlessSession refuses a shutdown while a save runs
    if (mSaver) {
        QEventLoop loop;
        QObject::connect(&mSaver->session(), &THeadlessSession::saveFinished, &loop, &QEventLoop::quit);
        QTimer::singleShot(5000, &loop, &QEventLoop::quit);
        loop.exec();
    }
    // Each with its sysExitEvent and save; the last to close takes the pool with it
    for (MudletProfile* profile : std::as_const(mOpen)) {
        profile->closeAtExit();
    }
    qDeleteAll(mOpen);
    qDeleteAll(mRestoreQueue);
    delete mIdle;
    THeadlessSession::setClipboard({}, {});
    THeadlessSession::setFilePicker({});
    THeadlessSession::setWebPageOpener({});
}

// At start-up: the profiles the engine before this one ran, if it was killed rather than stopped. The front one is
// restored first, in mIdle, then the others staggerMs apart.
void MudletEngine::takeRestore()
{
    const SessionStore::Restore restore = mStore->takeRestore(QDateTime::currentMSecsSinceEpoch());
    switch (restore.kind) {
    case SessionStore::Restore::Kind::none:
        return;
    case SessionStore::Restore::Kind::gaveUp:
        qWarning("MudletEngine: not restoring: the engine stopped unexpectedly %d times in %lld minutes", restore.attempt, SessionStore::restoreWindowMs / 60000);
        restoreStatus(QStringLiteral("gaveUp"), QStringLiteral("%1 %2").arg(restore.attempt).arg(SessionStore::restoreWindowMs / 60000));
        return;
    case SessionStore::Restore::Kind::restore:
        break;
    }
    mIdle->setRestore(restore.sessions.first());
    for (const SessionStore::Session& session : restore.sessions.sliced(1)) {
        auto* profile = new MudletProfile(*this, session.profile);
        profile->setRestore(session);
        mRestoreQueue.append(profile);
    }
    mFront = mIdle->name();
    mIdle->scheduleRestore(restore.reconnectDelayMs);
}

void MudletEngine::restoreEnded()
{
    if (!mRestoreQueue.isEmpty() && !mRestoreChain.isActive()) {
        mRestoreChain.start();
    }
}

void MudletEngine::restoreNext()
{
    if (!mRestoreQueue.isEmpty()) {
        mRestoreQueue.takeFirst()->beginRestore();
    }
}

void MudletEngine::cancelRestores()
{
    mRestoreChain.stop();
    for (MudletProfile* profile : std::as_const(mRestoreQueue)) {
        profile->deleteLater();
    }
    mRestoreQueue.clear();
    if (mIdle) {
        mIdle->dropRestore();
    }
    for (MudletProfile* profile : std::as_const(mOpen)) {
        profile->cancelRestore();
    }
}

// docs/protocol.md, "The restore status": a phase, then what it is about
void MudletEngine::restoreStatus(const QString& phase, const QString& detail)
{
    const QString value = detail.isEmpty() ? phase : QStringLiteral("%1 %2").arg(phase, detail);
    qInfo("MudletEngine: restore: %s", qPrintable(value));
    mHost->engineStatus(QStringLiteral("restore"), value);
}

void MudletEngine::persist()
{
    if (mClosing) {
        return;
    }
    QList<SessionStore::Session> sessions;
    for (const MudletProfile* profile : std::as_const(mOpen)) {
        if (const std::optional<SessionStore::Session> kept = profile->kept()) {
            sessions.append(*kept);
        }
    }
    if (mPendingOpen) {
        sessions.append(mPendingOpen->kept());
    }
    if (mIdle && mIdle->awaitingRestore()) {
        sessions.append(*mIdle->kept());
    }
    for (const MudletProfile* profile : std::as_const(mRestoreQueue)) {
        sessions.append(*profile->kept());
    }
    QString front = sessions.isEmpty() ? QString() : sessions.first().profile;
    if (std::ranges::any_of(sessions, [this](const SessionStore::Session& session) {
            return sameName(session.profile, mFront);
        })) {
        front = mFront;
    }
    if (sessions == mSavedSessions && front == mSavedFront) {
        return;
    }
    mSavedSessions = sessions;
    mSavedFront = front;
    mStore->save(sessions, front);
}

void MudletEngine::reportFont()
{
    QFont font = QFontDatabase::systemFont(QFontDatabase::FixedFont);
    font.setPixelSize(14);
    mHost->engineStatus(QStringLiteral("fontFamily"), QFontInfo(font).family());
    mHost->engineStatus(QStringLiteral("advanceM"), QString::number(QFontMetrics(font).horizontalAdvance(QLatin1Char('M'))));
    mHost->engineStatus(QStringLiteral("platform"), QGuiApplication::platformName());
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
    MudletProfile* open = openNamed(stored.name);
    const Profile before = open ? mProfiles.read(stored.name) : stored;
    if (const QString problem = mProfiles.save(stored, folder.isEmpty()); !problem.isEmpty()) {
        return QStringLiteral("could not store \"%1\": %2").arg(stored.name, problem);
    }
    // A running profile read these when it started
    if (open) {
        open->profileSaved(stored, before.host != stored.host || before.port != stored.port || before.tls != stored.tls);
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
    if (mIdle && sameName(folder, mIdle->name())) {
        mIdle->rename(mDefaultProfile);
        if (mOpen.isEmpty()) {
            mIdle->showIdle();
        }
    }
    return {};
}

// Running, about to run, closing, or waited for by a restore
bool MudletEngine::inUse(const QString& folder) const
{
    const auto same = [&folder](const MudletProfile* profile) {
        return sameName(folder, profile->name());
    };
    return std::ranges::any_of(mOpen, same) || std::ranges::any_of(mRestoreQueue, same) || (mPendingOpen && sameName(folder, mPendingOpen->profile))
           || (mIdle && mIdle->awaitingRestore() && same(mIdle));
}

MudletProfile* MudletEngine::openNamed(const QString& name) const
{
    const auto found = std::ranges::find_if(mOpen, [&name](const MudletProfile* profile) {
        return sameName(name, profile->name());
    });
    return found == mOpen.cend() ? nullptr : *found;
}

MudletProfile* MudletEngine::freeProfile(const QString& name)
{
    // The one a restore waits to bring back, which the UI opens itself instead
    for (qsizetype i = 0; i < mRestoreQueue.size(); ++i) {
        if (sameName(name, mRestoreQueue.at(i)->name())) {
            return mRestoreQueue.takeAt(i);
        }
    }
    if (mIdle && (!mIdle->awaitingRestore() || sameName(name, mIdle->name()))) {
        return mIdle;
    }
    return new MudletProfile(*this, name);
}

QString MudletEngine::openProfile(const QString& name, const QString& password, bool connect, bool alongside)
{
    const QString folder = mProfiles.find(name);
    if (folder.isEmpty()) {
        return QStringLiteral("there is no profile named \"%1\"").arg(name);
    }
    MudletProfile* open = openNamed(folder);
    if (alongside && mPendingOpen) {
        return QStringLiteral("\"%1\" is opening in place of the others: open this one once it has started").arg(mPendingOpen->profile);
    }
    if (alongside && open && open->stopping()) {
        return QStringLiteral("\"%1\" is closing: open it again once it has closed").arg(folder);
    }
    // Only a password for a profile that runs, as the UI hands one over: the order of the list and a restore under
    // way are for the player's own opening of a profile to change
    if (alongside && !connect && open && open->running()) {
        open->openAgain(password, false);
        return {};
    }
    mProfiles.markOpened(folder);
    if (!alongside) {
        cancelRestores();
    }
    if (open && open->running()) {
        open->cancelRestore();
        if (!alongside) {
            mPendingOpen.reset();
            // A copy, as a close can end at once
            const QList<MudletProfile*> others = mOpen;
            for (MudletProfile* other : others) {
                if (other != open) {
                    other->close(false);
                }
            }
        }
        open->openAgain(password, connect);
        return {};
    }
    MudletProfile::Open request;
    request.profile = folder;
    request.password = password;
    request.connect = connect;
    if (alongside) {
        MudletProfile* profile = freeProfile(folder);
        profile->cancelRestore();
        profile->open(request);
        return {};
    }
    // Lua a version 13 UI sent the profile closing for an earlier switch is for this one now
    if (mPendingOpen) {
        request.lua = mPendingOpen->lua;
    }
    mPendingOpen = request;
    // A copy, as a close can end at once, and the last to close opens the pending one
    const QList<MudletProfile*> others = mOpen;
    for (MudletProfile* other : others) {
        other->close(true);
    }
    if (mPendingOpen && mOpen.isEmpty()) {
        openPending();
    }
    persist();
    return {};
}

void MudletEngine::openPending()
{
    const MudletProfile::Open request = std::move(*mPendingOpen);
    mPendingOpen.reset();
    freeProfile(request.profile)->open(request);
}

QString MudletEngine::closeProfile(const QString& name)
{
    if (mPendingOpen && sameName(name, mPendingOpen->profile)) {
        return QStringLiteral("\"%1\" is still opening: close it once it has started").arg(mPendingOpen->profile);
    }
    MudletProfile* profile = openNamed(name);
    if (!profile) {
        return QStringLiteral("\"%1\" is not open").arg(name);
    }
    profile->cancelRestore();
    profile->close(false);
    return {};
}

IEngine* MudletEngine::defaultSession()
{
    if (!mIdle) {
        mIdle = new MudletProfile(*this, mDefaultProfile);
    }
    return mIdle;
}

bool MudletEngine::restorePending() const
{
    return (mIdle && mIdle->awaitingRestore()) || !mRestoreQueue.isEmpty();
}

void MudletEngine::setFront(const QString& name)
{
    mFront = name;
    persist();
    // From the event loop, as the host tells this from within a profile's start
    if (!std::exchange(mFrontPending, true)) {
        QTimer::singleShot(0, this, [this] {
            mFrontPending = false;
            applyFront();
        });
    }
}

void MudletEngine::applyFront()
{
    if (mFront == mAppliedFront || anyLoading()) {
        return;
    }
    MudletProfile* front = openNamed(mFront);
    if (!front || !front->started()) {
        return;
    }
    if (front->showInFront()) {
        mAppliedFront = front->name();
    } else {
        qWarning("MudletEngine: setActiveProfile(%s) refused: %s", qPrintable(front->name()), qPrintable(front->session().errorString()));
    }
}

bool MudletEngine::anyLoading() const
{
    return std::ranges::any_of(mOpen, &MudletProfile::loading);
}

bool MudletEngine::loadingOther(const MudletProfile* profile) const
{
    return std::ranges::any_of(mOpen, [profile](const MudletProfile* open) {
        return open != profile && open->loading();
    });
}

void MudletEngine::later(QObject* owner, const std::function<void()>& action)
{
    mDeferred.append({owner, action});
}

void MudletEngine::profileStarting(MudletProfile* profile)
{
    if (profile == mIdle) {
        mIdle = nullptr;
    }
    mRestoreQueue.removeOne(profile);
    if (!mOpen.contains(profile)) {
        mOpen.append(profile);
    }
    persist();
}

void MudletEngine::loadEnded(bool restoreStart)
{
    // Each may begin another load, which those after it then wait for again
    const auto deferred = std::exchange(mDeferred, {});
    for (const auto& [owner, action] : deferred) {
        if (!owner) {
            continue;
        }
        if (anyLoading()) {
            mDeferred.append({owner, action});
        } else {
            action();
        }
    }
    applyAppActive();
    applyFront();
    if (restoreStart) {
        restoreEnded();
    }
}

// A profile's close was refused, as while a script runs: a switch waiting for it is called off
void MudletEngine::closeRefused()
{
    mPendingOpen.reset();
    persist();
}

void MudletEngine::profileClosed(MudletProfile* profile)
{
    mOpen.removeOne(profile);
    if (mSaver == profile) {
        mSaver = nullptr;
    }
    const QString name = profile->name();
    // The last to close takes a version 13 UI's requests, unless the one a restore waits for does
    const bool idle = mOpen.isEmpty() && !(mIdle && mIdle->awaitingRestore());
    if (idle && mIdle != profile) {
        if (mIdle) {
            mIdle->deleteLater();
        }
        mIdle = profile;
    }
    mHost->profileClosed(name);
    if (!idle) {
        profile->deleteLater();
    }
    if (sameName(mAppliedFront, name)) {
        mAppliedFront.clear();
    }
    if (mPendingOpen && mOpen.isEmpty()) {
        openPending();
    }
    persist();
    applyAppActive();
    for (MudletProfile* open : std::as_const(mOpen)) {
        open->continueClose();
    }
}

void MudletEngine::setAppState(bool active, bool minimized)
{
    mMinimized = minimized;
    for (MudletProfile* profile : std::as_const(mOpen)) {
        profile->setWindowMinimized(minimized);
    }
    const bool leaving = mAppActive && !active;
    mAppActive = active;
    applyAppActive();
    // After sysApplicationFocusChangeEvent, so the save keeps what its handlers did
    if (leaving) {
        save();
    }
}

// Through any running profile, as the pool shares it; a profile that starts takes it from the pool, or before the first,
// from beginLoad(). THeadlessSession refuses it while a profile loads, which loadEnded() applies it after.
void MudletEngine::applyAppActive()
{
    if (anyLoading()) {
        return;
    }
    const auto running = std::ranges::find_if(mOpen, &MudletProfile::started);
    if (running == mOpen.cend() || (*running)->session().applicationActive() == mAppActive) {
        return;
    }
    if (!(*running)->session().setApplicationActive(mAppActive)) {
        qWarning("MudletEngine: setApplicationActive(%s) refused: %s", mAppActive ? "true" : "false", qPrintable((*running)->session().errorString()));
    }
}

// Every profile of the pool, as at a close but with no sysExitEvent, while they run: one save through any of them
void MudletEngine::save()
{
    if (mClosing || mSaver) {
        return;
    }
    if (anyLoading()) {
        later(this, [this] {
            save();
        });
        return;
    }
    const auto running = std::ranges::find_if(mOpen, &MudletProfile::started);
    if (running == mOpen.cend()) {
        return;
    }
    mSaver = *running;
    mSaver->session().saveAsync();
}

void MudletEngine::saveFinished(MudletProfile* profile, bool saved, const QString& error)
{
    if (profile != mSaver) {
        return;
    }
    mSaver = nullptr;
    if (saved) {
        mHost->engineStatus(QStringLiteral("save"), QStringLiteral("saved"));
    } else {
        qWarning("MudletEngine: the profiles were not saved: %s", qPrintable(error));
        mHost->engineStatus(QStringLiteral("save"), QStringLiteral("not saved: %1").arg(error));
    }
    if (mClosing) {
        return;
    }
    // Closes asked for during the save
    for (MudletProfile* open : std::as_const(mOpen)) {
        open->continueClose();
    }
}

void MudletEngine::onHeartbeat()
{
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    const qint64 gap = now - mLastHeartbeatMs;
    mLastHeartbeatMs = now;
    ++mHeartbeatTicks;
    mMaxHeartbeatGapMs = std::max(mMaxHeartbeatGapMs, gap);
    mHost->engineStatus(QStringLiteral("heartbeatTicks"), QString::number(mHeartbeatTicks));
    mHost->engineStatus(QStringLiteral("heartbeatLastGapMs"), QString::number(gap));
    mHost->engineStatus(QStringLiteral("heartbeatMaxGapMs"), QString::number(mMaxHeartbeatGapMs));
}
