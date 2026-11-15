#pragma once

#include "IFrontend.h"

#include <QByteArray>
#include <QHash>
#include <QJsonArray>
#include <QJsonObject>
#include <QObject>
#include <QPointer>
#include <QStringList>
#include <deque>
#include <optional>

class IEngine;
class QLocalServer;
class QLocalSocket;

// IFrontend over a QLocalServer. Wire format: 4-byte big-endian length, then
// one UTF-8 JSON object with a "t" type field. See docs/protocol.md for the messages.
//
// The engine never waits for the UI: Android freezes a cached UI process, so a
// UI that stops reading only stops getting lines. They stay in a ring buffer and
// are sent, with a "gap" marker if the ring overflowed, once the UI drains.
class SocketBridge : public QObject, public IFrontend
{
    Q_OBJECT

public:
    static constexpr int protocolVersion = 10;
    static constexpr quint32 maxFrameBytes = 16 * 1024 * 1024;
    static constexpr qsizetype scrollbackLines = 2000;
    static constexpr qint64 highWaterBytes = 4 * 1024 * 1024;
    static constexpr qint64 lowWaterBytes = 256 * 1024;
    // The most rows one getWindowText is answered with
    static constexpr int maxWindowTextRows = 1000;

    explicit SocketBridge(QObject* parent = nullptr);
    void setEngine(IEngine* engine) { mEngine = engine; }
    bool listen(const QString& path);

    void line(const QString& text, const QList<ColorRun>& runs, bool isPrompt) override;
    void tail(const QString& text, const QList<ColorRun>& runs, bool isPrompt) override;
    void cleared() override;
    void notify(const QString& title, const QString& text) override;
    void status(const QString& key, const QString& value) override;
    void hiddenInput(bool wanted) override;
    void history(const QStringList& lines) override;
    void commandLine(const QString& text) override;
    void windows(const QList<ScriptWindowInfo>& windows, const QList<LabelInfo>& labels) override;
    void windowText(const QString& window, const WindowText& text) override;
    void mainArea(const MainArea& area) override;
    void windowCommandLine(const QString& name, const QString& text) override;
    void buttonBars(const QList<ButtonBar>& bars) override;
    void commands(const QList<AddonCommandInfo>& commands) override;
    void map(const std::optional<MapFrame>& frame) override;

private:
    struct StoredLine
    {
        qint64 seq;
        QString text;
        QList<ColorRun> runs;
        bool prompt;
    };

    void onNewConnection();
    void onReadyRead();
    void onBytesWritten();
    void handleMessage(const QJsonObject& message);
    void greet(const QJsonObject& hello);
    void sendLinesAfter(qint64 seq, bool replay);
    // Every status, then the input line's state and the script windows', as a UI attaching or catching up needs them
    void sendState();
    void sendFrame(const QJsonObject& message);
    // An answer to the request being handled: it carries that request's id as re, and is sent even to a lagging UI
    void sendReply(QJsonObject message);
    void sendError(const QString& message);
    void sendProfiles();
    void answerProfiles(const QString& request, const QString& problem);
    // An error naming the request, for a problem that is not empty
    void refuse(const QString& request, const QString& problem);
    QString saveProfile(const QJsonObject& message);
    QString openProfile(const QJsonObject& message);
    void complete(const QJsonObject& message);
    QString setMainWindowSize(const QJsonObject& message);
    void getWindowText(const QJsonObject& message);
    QString placeUserWindow(const QJsonObject& message);
    QString pointer(const QJsonObject& message);
    QString mapPointer(const QJsonObject& message);
    void getImage(const QJsonObject& message);
    // Sent whole, and only when it differs from what was kept, which a UI that attaches or drains gets again
    void sendIfChanged(QJsonObject& kept, QJsonObject message);
    void dropClient(const QString& reason);
    bool clientLive() const { return mClient && mClientGreeted && !mClientLagging; }
    bool replying() const { return !mCurrentRequestId.isUndefined() && !mCurrentRequestId.isNull(); }
    static QJsonObject lineMessage(const StoredLine& stored, bool replay);
    static QJsonArray runsJson(const QList<ColorRun>& runs);
    QJsonObject tailMessage() const;
    QJsonObject hiddenInputMessage() const;
    static QJsonObject historyMessage(const QStringList& lines);
    static QJsonObject windowJson(const ScriptWindowInfo& window);
    static QJsonObject labelMessage(const LabelInfo& label);
    static QJsonObject windowTextMessage(const QString& window, const WindowText& text);
    static QJsonObject mainAreaMessage(const MainArea& area);
    static QJsonArray buttonsJson(const QList<BarButton>& buttons);
    static QJsonObject buttonBarsMessage(const QList<ButtonBar>& bars);
    static QJsonObject commandsMessage(const QList<AddonCommandInfo>& commands);

    QLocalServer* mServer = nullptr;
    QPointer<QLocalSocket> mClient;
    bool mClientGreeted = false;
    bool mClientLagging = false;
    qint64 mLastSentSeq = 0;
    QByteArray mReadBuffer;
    IEngine* mEngine = nullptr;
    QHash<QString, QString> mStatus;
    bool mHiddenInput = false;
    // As IFrontend::windows() last gave them
    QList<ScriptWindowInfo> mWindows;
    QList<LabelInfo> mLabels;
    // None until the engine has sent one
    std::optional<MainArea> mMainArea;
    // As IFrontend::buttonBars() and commands() last gave them, as sent
    QJsonObject mButtonBars = buttonBarsMessage({});
    QJsonObject mCommands = commandsMessage({});
    // The map message last sent, empty while no mapper shows
    QJsonObject mMap;
    std::deque<StoredLine> mScrollback;
    StoredLine mTail{0, {}, {}, false};
    qint64 mNextSeq = 1;
    // The last line sent before the console was last emptied, -1 for never: a UI with no line after it is told
    qint64 mClearedAfterSeq = -1;
    // Distinguishes a restarted engine, whose sequence numbers start again
    QString mInstance;
    // Request id of the command being executed, echoed on the status frames it causes
    QJsonValue mCurrentRequestId;
};
