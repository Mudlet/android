#pragma once

#include "IFrontend.h"

#include <QByteArray>
#include <QHash>
#include <QJsonArray>
#include <QJsonObject>
#include <QObject>
#include <QPointer>
#include <deque>

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
    static constexpr int protocolVersion = 1;
    static constexpr quint32 maxFrameBytes = 16 * 1024 * 1024;
    static constexpr qsizetype scrollbackLines = 2000;
    static constexpr qint64 highWaterBytes = 4 * 1024 * 1024;
    static constexpr qint64 lowWaterBytes = 256 * 1024;

    explicit SocketBridge(QObject* parent = nullptr);
    void setEngine(IEngine* engine) { mEngine = engine; }
    bool listen(const QString& path);

    void line(const QString& text, const QList<ColorRun>& runs, bool isPrompt) override;
    void tail(const QString& text, const QList<ColorRun>& runs, bool isPrompt) override;
    void cleared() override;
    void notify(const QString& title, const QString& text) override;
    void status(const QString& key, const QString& value) override;

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
    void sendAllStatus();
    void sendFrame(const QJsonObject& message);
    void dropClient(const QString& reason);
    bool clientLive() const { return mClient && mClientGreeted && !mClientLagging; }
    static QJsonObject lineMessage(const StoredLine& stored, bool replay);
    static QJsonArray runsJson(const QList<ColorRun>& runs);
    QJsonObject tailMessage() const;

    QLocalServer* mServer = nullptr;
    QPointer<QLocalSocket> mClient;
    bool mClientGreeted = false;
    bool mClientLagging = false;
    qint64 mLastSentSeq = 0;
    QByteArray mReadBuffer;
    IEngine* mEngine = nullptr;
    QHash<QString, QString> mStatus;
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
