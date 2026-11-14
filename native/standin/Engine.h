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
    // heartbeatMs is 30000 in the app; tests shorten it. The profiles are kept in a JSON file at profilesPath.
    Engine(IFrontend* frontend, int heartbeatMs, const QString& profilesPath, QObject* parent = nullptr);
    ~Engine() override;

    void connectTo(const QString& host, quint16 port, std::optional<bool> tls) override;
    void send(const QString& line) override;
    void sendHidden(const QString& line) override;
    void dismissHidden() override;
    QString complete(const QString& text, bool fromHistory) override;
    QStringList history() const override { return mHistory; }
    void disconnectFromGame() override;
    void runLua(const QString& code) override;
    void setViewWidthPx(int px) override;
    QList<Profile> profiles() const override { return mProfiles; }
    QString saveProfile(const Profile& profile) override;
    QString deleteProfile(const QString& name) override;
    QString openProfile(const QString& name, const QString& password, bool connect) override;
    // No scripts make windows here
    QString setMainWindowSize(int, int) override { return noWindows(); }
    std::optional<WindowText> windowText(const QString&, int, int) const override { return std::nullopt; }
    QString placeUserWindow(const QString&, const QRect&, bool) override { return noWindows(); }
    QString closeUserWindow(const QString&) override { return noWindows(); }
    QString sendWindowCommandLine(const QString&, const QString&) override { return noWindows(); }

    // Called from Lua's notify()
    void luaNotify(const QString& title, const QString& text);

private:
    static QString noWindows() { return QStringLiteral("the stand-in engine has no script windows"); }

    enum class TelnetState { Data, Iac, Option, Sub, SubIac };

    void onReadyRead();
    void processByte(unsigned char byte);
    void emitLine(bool isPrompt);
    void callOnLine(const QString& text);
    void reportFont();
    void onHeartbeat();
    void flushCounters();
    void markCountersDirty();
    void setHiddenInput(bool wanted);
    void writeLine(const QString& line);
    void applySgr(const QByteArray& params);
    int effectiveFg() const;
    void loadProfiles();
    QString storeProfiles(const QList<Profile>& profiles);
    qsizetype profileIndex(const QString& name) const;

    IFrontend* mFrontend;
    QTcpSocket mSocket;
    QTimer mPromptTimer;
    QTimer mHeartbeat;
    QTimer mCounterTimer;
    lua_State* mLua = nullptr;

    QString mProfilesPath;
    // The last opened first; a new one goes first too
    QList<Profile> mProfiles;
    // Empty until a profile is opened; connect alone opens none
    QString mOpenProfile;

    // The game echoes for the player (telnet WILL ECHO), as it does to hide a password
    bool mHiddenInput = false;
    // Every line send() sent, newest first, each once
    QStringList mHistory;

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
