#pragma once

#include "IEngine.h"
#include "IFrontend.h"

#include <QByteArray>
#include <QObject>
#include <QTcpSocket>
#include <QTimer>

struct lua_State;

// One profile's session, or the session a version 13 UI talks to before it opens one
class Engine : public QObject, public IEngine
{
    Q_OBJECT

public:
    explicit Engine(QObject* parent = nullptr);
    ~Engine() override;
    // What it shows goes to frontend from now on
    void start(IFrontend* frontend);
    // Drops the game's connection, as the session is about to go
    void close();
    bool unconnected() const { return mSocket.state() == QAbstractSocket::UnconnectedState; }

    void connectTo(const QString& host, quint16 port, std::optional<bool> tls) override;
    void send(const QString& line) override;
    void sendHidden(const QString& line) override;
    void dismissHidden() override;
    QString complete(const QString& text, bool fromHistory) override;
    QStringList history() const override { return mHistory; }
    void disconnectFromGame() override;
    void runLua(const QString& code) override;
    void setScreenSize(int columns, int rows) override;
    // The stand-in's runs carry no links
    QString link(const QString&, int, Link&) const override { return QStringLiteral("not supported"); }
    QString followLink(const QString&, int, std::optional<int>) override { return QStringLiteral("not supported"); }
    // No scripts make windows here
    QString setMainWindowSize(int, int) override { return noWindows(); }
    std::optional<WindowText> windowText(const QString&, int, int) const override { return std::nullopt; }
    QString placeUserWindow(const QString&, const QRect&, bool) override { return noWindows(); }
    QString closeUserWindow(const QString&) override { return noWindows(); }
    QString sendWindowCommandLine(const QString&, const QString&) override { return noWindows(); }
    QString setTextBoxText(const QString&, QString&) override { return noWindows(); }
    QString selectFrameTab(const QString&) override { return noWindows(); }
    QString pointer(const QString&, const PointerEvent&) override { return noWindows(); }
    std::variant<PngImage, QString> image(const QString&, const QSize&, std::optional<int>) const override { return noWindows(); }
    // Nor buttons or commands
    QString pressButton(int) override { return noButtons(); }
    QString activateCommand(int) override { return noButtons(); }
    // Nor a map
    QString mapPointer(const PointerEvent&) override { return noMap(); }
    QString setMapViewOnly(bool) override { return noMap(); }
    // Taken, as there is no map to draw at it
    QString setMapScale(double) override { return {}; }
    // The app's map is taken, and is always nothing to draw
    QString setAppMapSize(const QSize&, double) override
    {
        mFrontend->appMap(std::nullopt);
        return {};
    }
    QString appMapPointer(const PointerEvent&) override { return noMap(); }
    std::variant<RoomList, QString> findRooms(const QString&) const override { return noMap(); }
    QString showRoom(int) override { return noMap(); }
    QString downloadMap() override { return noMap(); }
    QString cancelMapDownload() override { return noMap(); }
    // Kept as given, with no meaning here: what a UI sets it reads back, and a key never set is left out
    std::variant<QVariantMap, QString> settings(const QStringList& keys) override;
    QString setSettings(const QVariantMap& values, QMap<QString, QString>&) override;

    // Called from Lua's notify() and announce()
    void luaNotify(const QString& title, const QString& text);
    void luaAnnounce(const QString& text, bool assertive);

private:
    static QString noWindows() { return QStringLiteral("the stand-in engine has no script windows"); }
    static QString noButtons() { return QStringLiteral("the stand-in engine has no buttons or commands"); }
    static QString noMap() { return QStringLiteral("the stand-in engine has no map"); }

    enum class TelnetState { Data, Iac, Option, Sub, SubIac };

    void onReadyRead();
    void processByte(unsigned char byte);
    void emitLine(bool isPrompt);
    void callOnLine(const QString& text);
    void flushCounters();
    void markCountersDirty();
    void setHiddenInput(bool wanted);
    void sendNaws();
    void writeLine(const QString& line);
    void applySgr(const QByteArray& params);
    int effectiveFg() const;

    IFrontend* mFrontend = nullptr;
    QTcpSocket mSocket;
    QTimer mPromptTimer;
    QTimer mCounterTimer;
    lua_State* mLua = nullptr;
    QVariantMap mSettings;

    // The game echoes for the player (telnet WILL ECHO), as it does to hide a password
    bool mHiddenInput = false;
    // Every line send() sent, newest first, each once
    QStringList mHistory;

    TelnetState mTelnetState = TelnetState::Data;
    unsigned char mTelnetCommand = 0;
    QByteArray mLineBuffer;

    int mFgBase = -1;
    bool mBold = false;

    // What a game that asks is told (NAWS): Mudlet's 90 by 25 until the UI gives its size
    int mColumns = 90;
    int mRows = 25;
    // The game asked for the size (telnet DO NAWS), so each change is sent
    bool mNaws = false;

    qint64 mLinesReceived = 0;
    qint64 mPromptsReceived = 0;
    qint64 mLuaCalls = 0;
    qint64 mNotifications = 0;
    bool mCountersDirty = false;
};
