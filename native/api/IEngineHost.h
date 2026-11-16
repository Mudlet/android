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
    // A profile began to open, run by session; what it shows goes to the frontend returned, until profileClosed()
    virtual IFrontend* profileOpened(const QString& name, IEngine* session, ProfileState state) = 0;
    virtual void profileState(const QString& name, ProfileState state) = 0;
    virtual void profileClosed(const QString& name) = 0;
    // A status of the whole engine, as its heartbeat or font, apart from any profile
    virtual void engineStatus(const QString& key, const QString& value) = 0;
    // Where what an engine shows with no profile open goes
    virtual IFrontend* controlFrontend() = 0;
};
