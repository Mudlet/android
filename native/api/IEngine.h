#pragma once

#include "Link.h"
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
    // What the player typed while the game hides input: never echoed, logged or kept in the history
    virtual void sendHidden(const QString& line) = 0;
    virtual void dismissHidden() = 0;
    // The input line completed, as Tab completes its last word or, fromHistory, from the history; empty for none
    virtual QString complete(const QString& text, bool fromHistory) = 0;
    // The input line's history, newest first
    virtual QStringList history() const = 0;
    virtual void disconnectFromGame() = 0;
    virtual void runLua(const QString& code) = 0;
    // The UI's console in characters, which a game that asks is told (NAWS)
    virtual void setScreenSize(int columns, int rows) = 0;
    // The main console's link with that id, as runs name it; else why not, as "unknown"
    virtual QString link(int id, Link& link) const = 0;
    // Follows it as a tap does, or picks item from its menu; else why nothing ran, as "disabled"
    virtual QString followLink(int id, std::optional<int> item) = 0;
    // The app in front (active) or not, and its window minimized or sent to the background; leaving the front saves
    virtual void setAppState(bool active, bool minimized) = 0;
    // Writes the profile now, as the system may stop the engine without warning
    virtual void save() = 0;

    // The last opened or created first. The others return why they did nothing, empty once done.
    virtual QList<Profile> profiles() const = 0;
    // Creates a profile, or updates the one with that name, ignoring case
    virtual QString saveProfile(const Profile& profile) = 0;
    virtual QString deleteProfile(const QString& name) = 0;
    // Starts that profile, first closing and saving another that runs. The password is for this run only.
    virtual QString openProfile(const QString& name, const QString& password, bool connect) = 0;
};
