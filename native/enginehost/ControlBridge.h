#pragma once

#include "IEngineHost.h"
#include "SocketBridge.h"

#include <QHash>
#include <QList>
#include <QString>
#include <QStringList>
#include <QTimer>

#include <optional>
#include <utility>

class IEngineControl;

// The control socket, engine.sock: the profiles, the app's state and the engine's own statuses, and a socket beside it
// for each open profile, which a SocketBridge serves. A UI that leaves profileSockets out of its hello is served as in
// version 13: the front profile's output and requests come and go on this socket. See docs/multi-profile.md.
class ControlBridge : public SocketBridge, public IEngineHost
{
    Q_OBJECT

public:
    // The most profiles the engine is asked to keep open at once
    static constexpr int maxOpenProfiles = 4;

    explicit ControlBridge(QObject* parent = nullptr);
    void setEngineControl(IEngineControl* control);
    bool listen(const QString& path);

    IFrontend* profileOpened(const QString& name, IEngine* session, ProfileState state) override;
    void profileState(const QString& name, ProfileState state) override;
    void profileClosed(const QString& name) override;
    void engineStatus(const QString& key, const QString& value) override;
    IFrontend* controlFrontend() override { return this; }

    enum class Change { lines, connection, attention };
    // From a profile's bridge
    void profileChanged(SocketBridge* profile, Change change);

private:
    struct OpenProfile
    {
        SocketBridge* bridge = nullptr;
        ProfileState state = ProfileState::starting;
        // The last line the player has seen, as markSeen gave it
        qint64 seenSeq = 0;
        bool attention = false;
    };

    bool handleControl(const QString& type, const QJsonObject& message) override;
    void greet(const QJsonObject& hello) override;
    void sendState() override;
    void sendProfiles();
    void answerProfiles(const QString& request, const QString& problem);
    QString saveProfile(const QJsonObject& message);
    QString openProfile(const QJsonObject& message);
    void setAppState(const QJsonObject& message);
    void markSeen(const QJsonObject& message);
    qsizetype openIndex(const QString& name) const;
    // On engine-p<slot>.sock beside the control socket, once that has a place
    void listenFor(SocketBridge* bridge) const;
    void setFront(qsizetype index);
    QJsonObject openProfilesMessage() const;
    // To a UI that takes profileSockets, now, or with lines within changeDelayMs
    void sendOpenProfiles(bool now);
    // The open profiles and their connections to the engine notification
    void showProfiles();

    static constexpr int changeDelayMs = 500;

    IEngineControl* mEngineControl = nullptr;
    QString mSocketDir;
    QList<OpenProfile> mOpen;
    qsizetype mFront = -1;
    // The engine's own statuses, which a UI that takes profileSockets gets, as against mStatus's, which hold the front
    // profile's too
    QHash<QString, QString> mEngineStatus;
    QTimer mChangeTimer;
    // Runs showProfiles from the event loop, so that a switch's close and open, which come in one call, do not take the
    // engine out of the foreground between them
    QTimer mNotificationTimer;
    // What the engine notification was last given, as names and their connections
    std::optional<std::pair<QStringList, QStringList>> mShown;
};
