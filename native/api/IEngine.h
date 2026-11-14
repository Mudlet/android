#pragma once

#include "IFrontend.h"
#include "Profile.h"

#include <QList>
#include <QRect>
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
    virtual void setViewWidthPx(int px) = 0;

    // The last opened or created first. The others return why they did nothing, empty once done.
    virtual QList<Profile> profiles() const = 0;
    // Creates a profile, or updates the one with that name, ignoring case
    virtual QString saveProfile(const Profile& profile) = 0;
    virtual QString deleteProfile(const QString& name) = 0;
    // Starts that profile, first closing and saving another that runs. The password is for this run only.
    virtual QString openProfile(const QString& name, const QString& password, bool connect) = 0;

    // The script windows'. The main window's size in pixels, kept for the next profile too.
    virtual QString setMainWindowSize(int width, int height) = 0;
    // Up to count rows of a console from row first, which counts back from the end below 0; none for a name that is
    // not a console's, or with no profile open
    virtual std::optional<WindowText> windowText(const QString& window, int first, int count) const = 0;
    // Where the UI put a user window, in the main window's pixels, docked or floating
    virtual QString placeUserWindow(const QString& name, const QRect& geometry, bool floating) = 0;
    // The dock's close button: hides the user window
    virtual QString closeUserWindow(const QString& name) = 0;
    // Enter on a window's command line holding text
    virtual QString sendWindowCommandLine(const QString& name, const QString& text) = 0;
};
