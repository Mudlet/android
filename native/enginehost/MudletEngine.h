#pragma once

#include "IEngine.h"
#include "IFrontend.h"
#include "SessionStore.h"

#include <QElapsedTimer>
#include <QList>
#include <QObject>
#include <QStringList>
#include <QTimer>

#include <memory>

class THeadlessSession;

// libmudlet behind the same host protocol as the stand-in: one profile in a THeadlessSession, started on the
// first connect or Lua call, or at start-up to restore the one the engine before it ran when that engine was killed.
// Only THeadlessSession's public API is used.
class MudletEngine : public QObject, public IEngine
{
    Q_OBJECT

public:
    explicit MudletEngine(IFrontend* frontend, int heartbeatMs = 30000, QObject* parent = nullptr);
    ~MudletEngine() override;

    void connectTo(const QString& host, quint16 port) override;
    void send(const QString& line) override;
    void disconnectFromGame() override;
    void runLua(const QString& code) override;
    void setViewWidthPx(int px) override;

private:
    enum class State { idle, starting, started };
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
    void shutdownSession();
    void takeRestore();
    void beginRestore();
    void cancelRestore();
    void restoreStatus(const QString& phase, const QString& detail = {});
    void persist();
    QString restoreTarget() const;
    bool profileReconnects() const;
    void start(const QString& host, quint16 port, bool connect);
    void onStartFinished(int result, const QString& error);
    void runNow(const QString& code);
    void reportFont();
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
    QString mProfile;
    QString mConfigDir;
    State mState = State::idle;
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

    QTimer mHeartbeat;
    QTimer mCounterTimer;
    int mAdvancePx = 0;
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
