#pragma once

#include "IFrontend.h"
#include "Link.h"
#include "Profile.h"

#include <QByteArray>
#include <QList>
#include <QPointF>
#include <QRect>
#include <QSize>
#include <QString>

#include <optional>
#include <variant>

// The UI's pointer on a script window, as THeadlessSession::WindowPointerEvent describes it
struct PointerEvent
{
    // As upstream names them on the wire
    enum class Type { press, release, doubleClick, move, enter, leave, wheel };
    Type type = Type::move;
    // In the window's pixels, from its top left corner
    QPointF position;
    // Qt's values: the button that changed, for a press, release or double-click, and those held after it
    int button = 0;
    int buttons = 0;
    // Qt::KeyboardModifiers
    int modifiers = 0;
    // The wheel's turn in eighths of a degree, as QWheelEvent::angleDelta().y()
    int wheelY = 0;
};

// An image as PNG, and its size in pixels
struct PngImage
{
    // The largest side IEngine::image() scales to or reads, so that a huge file is not decoded whole for a UI that
    // could not take it in one frame
    static constexpr int maxSide = 4096;
    QByteArray png;
    QSize size;
};

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
    // What the UI's pointer did on a window
    virtual QString pointer(const QString& window, const PointerEvent& event) = 0;
    // The image file, or icon, at path as PNG, scaled to size unless it is empty, or that frame of an animated one;
    // else why there is none. Size is at most PngImage::maxSide a side.
    virtual std::variant<PngImage, QString> image(const QString& path, const QSize& size, std::optional<int> frame) const = 0;
    // A click on a button or menu entry of IFrontend::buttonBars(), by its id
    virtual QString pressButton(int id) = 0;
    // A click on a command of IFrontend::commands(), by its id
    virtual QString activateCommand(int id) = 0;
};
