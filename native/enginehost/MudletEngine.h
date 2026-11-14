#pragma once

#include "IEngine.h"
#include "IFrontend.h"
#include "ProfileStore.h"
#include "SessionStore.h"

#include <QElapsedTimer>
#include <QList>
#include <QObject>
#include <QSize>
#include <QStringList>
#include <QTimer>

#include <memory>
#include <optional>

class QColor;
class THeadlessSession;

// libmudlet behind the same host protocol as the stand-in: one profile at a time in a THeadlessSession, started by
// openProfile, on the first connect or Lua call, or at start-up to restore the one the engine before it ran when that
// engine was killed. Only THeadlessSession's public API is used.
class MudletEngine : public QObject, public IEngine
{
    Q_OBJECT

public:
    explicit MudletEngine(IFrontend* frontend, int heartbeatMs = 30000, QObject* parent = nullptr);
    ~MudletEngine() override;

    void connectTo(const QString& host, quint16 port, std::optional<bool> tls) override;
    void send(const QString& line) override;
    void sendHidden(const QString& line) override;
    void dismissHidden() override;
    QString complete(const QString& text, bool fromHistory) override;
    QStringList history() const override;
    void disconnectFromGame() override;
    void runLua(const QString& code) override;
    void setScreenSize(int columns, int rows) override;
    QString link(int id, Link& link) const override;
    QString followLink(int id, std::optional<int> item) override;
    void setAppState(bool active, bool minimized) override;
    void save() override;
    QList<Profile> profiles() const override { return mProfiles.profiles(); }
    QString saveProfile(const Profile& profile) override;
    QString deleteProfile(const QString& name) override;
    QString openProfile(const QString& name, const QString& password, bool connect) override;

private:
    // stopping: the profile closes, for the one in mPendingOpen to start
    enum class State { idle, starting, started, stopping };
    // A profile to start once the one running has closed. While there is one, connect, disconnect and Lua are for it.
    struct PendingOpen
    {
        QString profile;
        QString password;
        bool connect = true;
        // As SessionStore::Session's: empty, 0 and unset for the profile's own
        QString host;
        quint16 port = 0;
        std::optional<bool> tls;
        QStringList lua;

        SessionStore::Session kept() const { return {profile, host, port, connect, tls}; }
    };
    // The bottom line of the main console, which may still grow: the last line lineShown sent, which an echo() after
    // deleteLine() or a command typed at a prompt continues, or what localText has written since its last line feed
    struct OpenLine
    {
        enum class Kind { none, shown, local };
        Kind kind = Kind::none;
        QString text;
        QList<ColorRun> runs;
        bool prompt = false;
    };

    void connectSession();
    // False, with status sendError, while no profile is running to take what the player types
    bool takesInput();
    void shutdownSession(bool alreadyClosing);
    void beginSwitch();
    void onShutdownFinished(bool saved, const QString& error);
    void openPending();
    void keepPending();
    void connectProfile();
    bool inUse(const QString& folder) const;
    void takeRestore();
    void beginRestore();
    void cancelRestore();
    void restoreStatus(const QString& phase, const QString& detail = {});
    void persist();
    QString restoreTarget() const;
    bool profileReconnects() const;
    void start(const QString& host, quint16 port, bool connect, std::optional<bool> tls, const QString& password = {});
    void onStartFinished(int result, const QString& error);
    void runNow(const QString& code);
    void reportFont();
    void applyScreenSize();
    void applyAppActive();
    void onSaveFinished(bool saved, const QString& error);
    void reportConsoleColors(const QColor& foreground, const QColor& background);
    void onHeartbeat();
    void flushCounters();
    void markCountersDirty();

    void onLineShown(const QString& text, bool isPrompt, const QList<ColorRun>& runs, bool continuesLine);
    void onLocalText(const QString& text, const QList<ColorRun>& runs, bool continuesLine);
    void onMainConsoleCleared();
    void appendToOpen(const QString& text, const QList<ColorRun>& runs);
    void commitCompleteLines(OpenLine::Kind remainder);
    void commitOpen();
    void scheduleTailSync();
    void syncTail();

    IFrontend* mFrontend;
    std::unique_ptr<THeadlessSession> mSession;
    // The profile running, or the one the next start starts
    QString mProfile;
    // MUDLET_PROFILE, else Android: what connect and Lua start when nothing has named another
    QString mDefaultProfile;
    QString mConfigDir;
    ProfileStore mProfiles;
    std::optional<PendingOpen> mPendingOpen;
    // What mKept was before a switch began, for a close that is refused
    SessionStore::Session mKeptBeforeSwitch;
    State mState = State::idle;
    // The TLS choice the profile was started with, unset when that start made none
    std::optional<bool> mProfileTls;
    // Lua (connects included) asked for while the profile starts, run once it has
    QStringList mPendingLua;
    QString mErrorPending;

    std::unique_ptr<SessionStore> mStore;
    // What a restart of the engine would bring back, and what was last written of it
    SessionStore::Session mKept;
    SessionStore::Session mSavedKept;
    // The UI asked for a disconnect, so the disconnected that follows does not leave the connection wanted
    bool mDisconnectRequested = false;
    // From the restore's start until it has connected, or found it will not
    bool mRestoring = false;
    // Holds back a restore that is to wait before it connects
    QTimer mRestoreTimer;
    // The engine is stopping in order: nothing more is written for a restore
    bool mClosing = false;

    OpenLine mOpen;
    // localText's next piece starts with the line feed that ends the line it left open, which lineShown has
    // since ended by sending a line below it
    bool mDropLineFeed = false;
    bool mTailSyncPending = false;
    // What the frontend was last sent as the tail; its kind is not used
    OpenLine mSentTail;

    // The highest link id sent before this profile started, added to its ids, which count from 1 again, so that an id
    // on an earlier profile's line, which the UI keeps, names none of this profile's links
    int mLinkBase = 0;
    int mLinkTop = 0;

    // The UI's last setScreenSize, given to each profile as it loads; empty until then
    QSize mScreenSize;
    // The UI's last setAppState active, as THeadlessSession's starts
    bool mAppActive = true;
    // A saveAsync() has not answered yet, which a shutdown would be refused for
    bool mSaving = false;

    QTimer mHeartbeat;
    QTimer mCounterTimer;
    qint64 mLinesReceived = 0;
    qint64 mPromptsReceived = 0;
    qint64 mLocalLines = 0;
    qint64 mErrorLines = 0;
    bool mCountersDirty = false;
    qint64 mLastHeartbeatMs = 0;
    qint64 mHeartbeatTicks = 0;
    qint64 mMaxHeartbeatGapMs = 0;
    QElapsedTimer mStartClock;
};
