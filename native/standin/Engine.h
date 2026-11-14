#pragma once

#include "IEngine.h"
#include "IFrontend.h"

#include <QByteArray>
#include <QObject>
#include <QTcpSocket>
#include <QTimer>

struct lua_State;

class Engine : public QObject, public IEngine
{
    Q_OBJECT

public:
    // heartbeatMs is 30000 in the app; tests shorten it
    explicit Engine(IFrontend* frontend, int heartbeatMs = 30000, QObject* parent = nullptr);
    ~Engine() override;

    void connectTo(const QString& host, quint16 port) override;
    void send(const QString& line) override;
    void disconnectFromGame() override;
    void runLua(const QString& code) override;
    void setViewWidthPx(int px) override;

    // Called from Lua's notify()
    void luaNotify(const QString& title, const QString& text);

private:
    enum class TelnetState { Data, Iac, Option, Sub, SubIac };

    void onReadyRead();
    void processByte(unsigned char byte);
    void emitLine(bool isPrompt);
    void callOnLine(const QString& text);
    void reportFont();
    void onHeartbeat();
    void flushCounters();
    void markCountersDirty();
    void applySgr(const QByteArray& params);
    int effectiveFg() const;

    IFrontend* mFrontend;
    QTcpSocket mSocket;
    QTimer mPromptTimer;
    QTimer mHeartbeat;
    QTimer mCounterTimer;
    lua_State* mLua = nullptr;

    TelnetState mTelnetState = TelnetState::Data;
    unsigned char mTelnetCommand = 0;
    QByteArray mLineBuffer;

    int mFgBase = -1;
    bool mBold = false;

    int mAdvancePx = 0;
    int mViewWidthPx = 0;

    qint64 mLinesReceived = 0;
    qint64 mPromptsReceived = 0;
    qint64 mLuaCalls = 0;
    qint64 mNotifications = 0;
    bool mCountersDirty = false;

    qint64 mLastHeartbeatMs = 0;
    qint64 mHeartbeatTicks = 0;
    qint64 mMaxHeartbeatGapMs = 0;
};
