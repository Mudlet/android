#pragma once

#include "AnsiParser.h"
#include "IFrontend.h"
#include "LuaHost.h"
#include "TelnetDecoder.h"

#include <QAbstractSocket>
#include <QObject>
#include <QTimer>

class QTcpSocket;

// Lives on Qt's main thread. Hosts call the slots through queued invocations.
class Engine : public QObject
{
    Q_OBJECT

public:
    explicit Engine(IFrontend* frontend, QObject* parent = nullptr);
    ~Engine() override;

    void setHeartbeatIntervalMs(int ms);

public slots:
    void start();
    void connectTo(const QString& host, int port);
    void send(const QString& line);
    void disconnectFromGame();
    void runLua(const QString& code);
    void setViewWidthPx(int px);

private:
    void resetTelnet();
    void onReadyRead();
    void onSocketState(QAbstractSocket::SocketState state);
    void onBytes(const QByteArray& bytes);
    void emitLine(const QByteArray& raw, bool isPrompt);
    void flushPromptCandidate();
    void scheduleCounters();
    void reportFont();
    void heartbeat();

    IFrontend* mFrontend;
    QTcpSocket* mSocket;
    TelnetDecoder mTelnet;
    AnsiParser mAnsi;
    LuaHost mLua;
    QByteArray mPending;
    QTimer mPromptTimer;
    QTimer mHeartbeatTimer;
    QTimer mCountersTimer;
    int mAdvanceM = 0;
    int mViewWidthPx = 0;
    quint64 mLines = 0;
    quint64 mLuaCalls = 0;
    quint64 mNotifications = 0;
    quint64 mHeartbeatTicks = 0;
    qint64 mLastBeatMs = 0;
    qint64 mLastGapMs = 0;
    qint64 mMaxGapMs = 0;
};
