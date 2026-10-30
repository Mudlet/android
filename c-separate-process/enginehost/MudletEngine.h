#pragma once

#include "IEngine.h"
#include "IFrontend.h"

#include <QElapsedTimer>
#include <QList>
#include <QObject>
#include <QTimer>

class THeadlessSession;

// libmudlet behind the same host protocol as the stand-in: one profile in a THeadlessSession, started on the
// first connect or Lua call
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
    bool ensureStarted(const QString& host, quint16 port, bool connect);
    void installLuaFiles();
    void reportFont();
    void onHeartbeat();
    void flushCounters();
    void markCountersDirty();
    void watchConsole();
    void scheduleSync();
    void syncConsole();

    IFrontend* mFrontend;
    THeadlessSession* mSession = nullptr;
    QString mProfile;
    bool mStarted = false;
    QString mErrorPending;
    // Main console lines above this one went to the frontend; the rest are still open to triggers
    int mSentLines = 0;
    bool mSyncPending = false;
    QString mTailText;
    QList<ColorRun> mTailRuns;
    bool mTailPrompt = false;

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
