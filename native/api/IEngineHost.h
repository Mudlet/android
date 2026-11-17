#pragma once

#include <QString>

class IEngine;
class IFrontend;

// What an engine tells the host of its profiles as they open and close, and of itself
class IEngineHost
{
public:
    enum class ProfileState { starting, loaded, closing, restoring };

    virtual ~IEngineHost() = default;
    // A profile began to open, run by session; what it shows goes to the frontend returned, until profileClosed().
    // passwordGiven: it was opened with a password for this run
    virtual IFrontend* profileOpened(const QString& name, IEngine* session, ProfileState state, bool passwordGiven) = 0;
    virtual void profileState(const QString& name, ProfileState state) = 0;
    // The open profile was given a password for this run, or the one it had was withdrawn
    virtual void profilePasswordGiven(const QString& name, bool given) = 0;
    virtual void profileClosed(const QString& name) = 0;
    // What the engine lists of a profile changed other than by a saveProfile, as when the player took the game's
    // secure port
    virtual void profilesChanged() = 0;
    // A status of the whole engine, as its heartbeat or font, apart from any profile
    virtual void engineStatus(const QString& key, const QString& value) = 0;
    // Where what an engine shows with no profile open goes
    virtual IFrontend* controlFrontend() = 0;
};
