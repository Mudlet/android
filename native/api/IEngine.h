#pragma once

#include "IFrontend.h"
#include "Link.h"

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

// What the host asks of one profile's session, the stand-in's or libmudlet's (MudletEngine); IEngineControl has what
// is the whole engine's
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
    // The link with that id, as runs name it, of the console window (a mini console, user window or MXP frame), or
    // of the main console where window is empty or "main"; else why not, as "unknown"
    virtual QString link(const QString& window, int id, Link& link) const = 0;
    // Follows it as a tap does, or picks item from its menu; else why nothing ran, as "disabled"
    virtual QString followLink(const QString& window, int id, std::optional<int> item) = 0;
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
    // What the player has typed in a text box so far, the whole of its text, which is then set to what the box holds,
    // as Mudlet keeps it
    virtual QString setTextBoxText(const QString& name, QString& text) = 0;
    // The player's tap on a tab of an MXP frame's tab header, which brings that page, the MXP frame name, forward
    virtual QString selectFrameTab(const QString& name) = 0;
    // What the UI's pointer did on a window
    virtual QString pointer(const QString& window, const PointerEvent& event) = 0;
    // The image file, or icon, at path as PNG, scaled to size unless it is empty, or that frame of an animated one;
    // else why there is none. Size is at most PngImage::maxSide a side.
    virtual std::variant<PngImage, QString> image(const QString& path, const QSize& size, std::optional<int> frame) const = 0;
    // A click on a button or menu entry of IFrontend::buttonBars(), by its id
    virtual QString pressButton(int id) = 0;
    // A click on a command of IFrontend::commands(), by its id
    virtual QString activateCommand(int id) = 0;
    // What the UI's pointer did on the map, at a point of the last IFrontend::map() frame: a press, a double-click or
    // a wheel turn; its buttons are not read
    virtual QString mapPointer(const PointerEvent& event) = 0;
    // Viewing the map, or editing it
    virtual QString setMapViewOnly(bool viewOnly) = 0;
    // The mapper window's map drawn from now on at scale device pixels to each of the window's, so that it is as
    // sharp as the UI shows it, though never more than PngImage::maxSide a side; 1 again when the UI goes, as
    // SocketBridge asks. Scale is from 1 to maxMapScale.
    static constexpr double maxMapScale = 8.0;
    virtual QString setMapScale(double scale) = 0;
    // The app's own map, apart from the mapper window, drawn for IFrontend::appMap() at size, in the map's pixels, with
    // scale device pixels to each, from now on and for the next profile too; an empty size stops it, as SocketBridge
    // asks when the UI goes. Scale is from 1 to maxAppMapScale.
    static constexpr double maxAppMapScale = 4.0;
    virtual QString setAppMapSize(const QSize& size, double scale) = 0;
    // What mapPointer() does, at a point of the last IFrontend::appMap() frame, in the map's pixels
    virtual QString appMapPointer(const PointerEvent& event) = 0;
    // The rooms whose name holds text, ignoring case, and the room whose id it is; else why none were looked for
    virtual std::variant<RoomList, QString> findRooms(const QString& text) const = 0;
    // The app's map centred on that room, which it selects, until the player moves
    virtual QString showRoom(int room) = 0;
    // Downloads the map the game offered and loads it in place of the profile's, as IFrontend::mapDownload() follows
    virtual QString downloadMap() = 0;
    // Aborts that download, as the Abort of Mudlet's progress dialog does, while it still can be
    virtual QString cancelMapDownload() = 0;
};
