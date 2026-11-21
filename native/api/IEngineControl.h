#pragma once

#include "Profile.h"

#include <QList>
#include <QString>

class IEngine;

// What the host asks of the whole engine, apart from any one profile: its profiles, and the app's state. A profile it
// starts is told to IEngineHost, with the IEngine that runs it.
class IEngineControl
{
public:
    virtual ~IEngineControl() = default;
    // The app in front (active) or not, and its window minimized or sent to the background; leaving the front saves
    virtual void setAppState(bool active, bool minimized) = 0;
    // Writes every open profile now, as the system may stop the engine without warning
    virtual void save() = 0;

    // The last opened or created first. The others return why they did nothing, empty once done.
    virtual QList<Profile> profiles() const = 0;
    // Creates a profile, or updates the one with that name, ignoring case
    virtual QString saveProfile(const Profile& profile) = 0;
    virtual QString deleteProfile(const QString& name) = 0;
    // Starts that profile. With alongside, beside those open, leaving it as it is if already open; without, first
    // closing and saving the others. The passwords are for this run only.
    virtual QString openProfile(const QString& name, const ProfilePasswords& passwords, bool connect, bool alongside) = 0;
    // Saves and closes that open profile
    virtual QString closeProfile(const QString& name) = 0;
    // What takes a version 13 UI's profile requests while no profile is open: a connect or Lua there may open one
    virtual IEngine* defaultSession() = 0;
    // Whether a restore is yet to reopen a profile the engine before this one had open
    virtual bool restorePending() const = 0;
    // The open profile the player now looks at, as a desktop tab brought forward
    virtual void setFront(const QString& name) = 0;
    // The UI's console in characters and Mudlet's main window in pixels, as each profile opened later starts with them
    // until its own socket gives its own: kept, not given to the profiles open now
    virtual void setStartScreenSize(int columns, int rows) = 0;
    virtual void setStartMainWindowSize(int width, int height) = 0;
};
