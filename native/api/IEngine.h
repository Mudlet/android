#pragma once

#include "Profile.h"

#include <QList>
#include <QString>

#include <optional>

// What the host asks of an engine: the stand-in, or libmudlet behind MudletEngine
class IEngine
{
public:
    virtual ~IEngine() = default;
    // tls: whether to encrypt the game connection; unset, as the engine would choose
    virtual void connectTo(const QString& host, quint16 port, std::optional<bool> tls) = 0;
    virtual void send(const QString& line) = 0;
    virtual void disconnectFromGame() = 0;
    virtual void runLua(const QString& code) = 0;
    virtual void setViewWidthPx(int px) = 0;

    // The last opened or created first. The others return why they did nothing, empty once done.
    virtual QList<Profile> profiles() const = 0;
    // Creates a profile, or updates the one with that name, ignoring case
    virtual QString saveProfile(const Profile& profile) = 0;
    virtual QString deleteProfile(const QString& name) = 0;
    // Starts that profile, first closing and saving another that runs. The password is for this run only.
    virtual QString openProfile(const QString& name, const QString& password, bool connect) = 0;
};
