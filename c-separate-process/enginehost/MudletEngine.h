#pragma once

#include "IEngine.h"
#include "IFrontend.h"

#include <QElapsedTimer>
#include <QList>
#include <QObject>
#include <QStringList>
#include <QTimer>

#include <memory>

class THeadlessSession;

// libmudlet behind the same host protocol as the stand-in: one profile in a THeadlessSession, started on the
// first connect or Lua call. Only THeadlessSession's public API is used.
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

    OpenLine mOpen;
    // localText's next piece starts with the line feed that ends the line it left open, which lineShown has
    // since ended by sending a line below it
    bool mDropLineFeed = false;
    bool mTailSyncPending = false;
    QString mSentTailText;
    QList<ColorRun> mSentTailRuns;
    bool mSentTailPrompt = false;

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
