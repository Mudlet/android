#pragma once

#include "AnsiParser.h"
#include "IFrontend.h"
#include "TelnetParser.h"

#include <QObject>
#include <QTimer>

class QTcpSocket;
struct lua_State;

// Stand-in for mudlet_core. Lives on Qt's thread; every public slot must be called there.
class Engine : public QObject
{
    Q_OBJECT

public:
    explicit Engine(IFrontend* frontend, QObject* parent = nullptr);
    ~Engine() override;

    // Font check and heartbeat; needs a QGuiApplication.
    void start();

    int linesReceived() const { return mLinesReceived; }
    int luaOnLineCalls() const { return mLuaOnLineCalls; }
    int notificationsPosted() const { return mNotifications; }
    int heartbeatTicks() const { return mHeartbeatTicks; }

    void setHeartbeatIntervalMs(int ms) { mHeartbeat.setInterval(ms); }

public slots:
    void connectTo(const QString& host, int port);
    void send(const QString& line);
    void disconnectFromGame();
    void runLua(const QString& code);
    void setViewWidthPx(int px);

private:
    void onReadyRead();
    void onByte(char ch);
    void flushLine(bool isPrompt);
    void callOnLine(const QString& text);
    void reportCounters();
    void onHeartbeat();
    void reportColumns();
    void initLua();
    static int luaNotify(lua_State* L);
    static int luaPrint(lua_State* L);

    IFrontend* mFrontend;
    QTcpSocket* mSocket = nullptr;
    TelnetParser mTelnet;
    AnsiParser mAnsi;
    QByteArray mLineBuffer;
    QTimer mPromptTimer;
    QTimer mHeartbeat;
    qint64 mLastHeartbeatMs = 0;
    qint64 mMaxGapMs = 0;
    int mHeartbeatTicks = 0;
    lua_State* mLua = nullptr;
    int mLinesReceived = 0;
    int mLuaOnLineCalls = 0;
    int mNotifications = 0;
    int mAdvanceM = 0;
    int mViewWidthPx = 0;
};
