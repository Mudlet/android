#pragma once

#include "IFrontend.h"
#include "Link.h"

#include <QByteArray>
#include <QList>
#include <QMap>
#include <QPointF>
#include <QRect>
#include <QSize>
#include <QString>
#include <QVariant>

#include <array>
#include <functional>
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

// What the UI knows of where it draws the main window, which scripts read with getMainWindowInfo() (libmudlet's
// TMainWindowInfo::Device): whether the pointer is a finger and can hover, the screen's density, and Mudlet's pixels
// per dp as the UI scales the main window
struct MainWindowDevice
{
    bool coarsePointer = false;
    bool hover = true;
    double devicePixelRatio = 1;
    double pixelScale = 1;
    bool operator==(const MainWindowDevice&) const = default;
};

// What the host asks of one profile's session (MudletEngine's MudletProfile); IEngineControl has what
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
    // What the main window is drawn on, kept for the next profile too
    virtual QString setMainWindowDevice(const MainWindowDevice& device) = 0;
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
    // The player's tap on a link in a label's text, href as the label's text holds it, followed as Mudlet's GUI follows
    // a click on it
    virtual QString followLabelLink(const QString& label, const QString& href) = 0;
    // What the UI's pointer did on a window
    virtual QString pointer(const QString& window, const PointerEvent& event) = 0;
    // The image file, or icon, at path as PNG, scaled to size unless it is empty, or that frame of an animated one;
    // else why there is none. Size is at most PngImage::maxSide a side.
    virtual std::variant<PngImage, QString> image(const QString& path, const QSize& size, std::optional<int> frame) const = 0;
    // A click on a button or menu entry of IFrontend::buttonBars(), by its id
    virtual QString pressButton(int id) = 0;
    // A click on a command of IFrontend::commands(), by its id
    virtual QString activateCommand(int id) = 0;
    // A key pressed on the UI's input line, key and modifiers as Qt's key event gives them, and text what it typed
    // where the UI knows (THeadlessSession::pressKey()): a binding of it ran, a command whose shortcut it is ran, no
    // binding took it, or Mudlet's line keeps it for itself, for the UI to handle; else why it was refused
    enum class KeyPress { bindingRan, commandRan, noBinding, keyKept };
    virtual std::variant<KeyPress, QString> pressKey(int key, int modifiers, const std::optional<QString>& text) = 0;
    // What the UI's pointer did on the map, at a point of the last IFrontend::map() frame: a press, a double-click or
    // a wheel turn; its buttons are not read
    virtual QString mapPointer(const PointerEvent& event) = 0;
    // Viewing the map, or editing it
    virtual QString setMapViewOnly(bool viewOnly) = 0;
    // The mapper window's map drawn from now on at scale device pixels to each of the window's, so that it is as
    // sharp as the UI shows it, though never more than PngImage::maxSide a side; 1 again when the UI goes, as
    // SocketBridge asks. Scale is from 1 to maxMapScale. Density is the device pixels to each of the units the UI lays
    // its own map out in, from 1 to maxAppMapScale: a scale above it lays the map out in those units, so its rooms
    // are drawn as on that map; one at or above scale lays it out in the window's pixels.
    static constexpr double maxMapScale = 8.0;
    virtual QString setMapScale(double scale, double density) = 0;
    // The app's own map, apart from the mapper window, drawn for IFrontend::appMap() at size, in the map's pixels, with
    // scale device pixels to each, from now on and for the next profile too; an empty size stops it, as SocketBridge
    // asks when the UI goes. Scale is from 1 to maxAppMapScale.
    static constexpr double maxAppMapScale = 4.0;
    virtual QString setAppMapSize(const QSize& size, double scale) = 0;
    // What mapPointer() does, at a point of the last IFrontend::appMap() frame, in the map's pixels
    virtual QString appMapPointer(const PointerEvent& event) = 0;
    // The rooms whose name holds text, ignoring case, and the room whose id it is; else why none were looked for
    virtual std::variant<RoomList, QString> findRooms(const QString& text) const = 0;
    // Which map showRoom(), moveMap() and followPlayer() act on: the mapper window's (IFrontend::map()) or the app's
    // own (IFrontend::appMap())
    enum class MapTarget { mapper, app };
    // That map centred on the room until the player moves: the app's selecting it, the mapper's leaving its
    // selection, as the desktop's map search does
    virtual QString showRoom(MapTarget map, int room) = 0;
    // A drag and a pinch on that map: what its last frame drew moved by pan, in the frame's pixels, then zoomed by zoom
    // (above 0, 2 showing the rooms twice as large) about the point about of it; the frames that draw it carry seq as
    // MapViewInfo::moved. It stops following the player where it leaves the player off the middle.
    virtual QString moveMap(MapTarget map, const QPointF& pan, double zoom, const QPointF& about, qint64 seq) = 0;
    // That map centred on the player's room, following the player again
    virtual QString followPlayer(MapTarget map) = 0;
    // That map showing another level of the area it drew, or another area, until the player moves
    virtual QString showMapLevel(MapTarget map, int level) = 0;
    virtual QString showMapArea(MapTarget map, int area) = 0;
    // A room of the map: its area, place, exits and steps from the player
    virtual std::variant<RoomDetails, QString> roomInfo(int room) const = 0;
    // The player walked to that room, as a double-click on it does
    virtual QString walkToRoom(int room) = 0;
    // The map's areas that have rooms, by name
    virtual std::variant<QList<MapAreaInfo>, QString> mapAreas() const = 0;
    // What editRoom() sets of a room: each left empty is kept; position is x, y (north up) and z
    struct RoomEdit
    {
        std::optional<QString> name;
        std::optional<QString> symbol;
        std::optional<int> env;
        std::optional<int> weight;
        std::optional<bool> locked;
        std::optional<std::array<int, 3>> position;
    };
    // Edits of the map, as the desktop mapper's editing makes them: each that gives a room gives the one to show after
    // it, the room edited or the one made
    virtual std::variant<RoomDetails, QString> editRoom(int room, const RoomEdit& edit) = 0;
    // One of the room's twelve exits led to the room to, or removed for 0, and with bothWays the exit back too
    virtual std::variant<RoomDetails, QString> setRoomExit(int room, const QString& direction, int to, bool bothWays) = 0;
    virtual std::variant<RoomDetails, QString> lockRoomExit(int room, const QString& direction, bool locked) = 0;
    // A new room one place from that room that way, with exits both ways
    virtual std::variant<RoomDetails, QString> addRoom(int beside, const QString& direction) = 0;
    virtual QString deleteRoom(int room) = 0;
    // The player placed in that room, as a script's centerview() does
    virtual QString setPlayerRoom(int room) = 0;
    virtual QString renameArea(int area, const QString& name) = 0;
    // The environments a room can be given, by number
    virtual std::variant<QList<MapEnvInfo>, QString> mapEnvs() const = 0;
    // Downloads the map the game offered and loads it in place of the profile's, as IFrontend::mapDownload() follows
    virtual QString downloadMap() = 0;
    // Aborts that download, as the Abort of Mudlet's progress dialog does, while it still can be
    virtual QString cancelMapDownload() = 0;
    // Answers IFrontend::secureOffer() as the GUI's Yes and No do: yes stores the secure port with TLS on and connects
    // there; no connects again where it was and stops the profile asking. Else why it was not taken.
    virtual QString answerSecureOffer(bool accept) = 0;
    // The GUI's emergency stop: true turns off the profile's triggers, aliases, timers and key bindings and keeps events
    // from its scripts, false turns on again what it turned off, as IFrontend::scriptsStopped() follows. Else why not.
    virtual QString setScriptsStopped(bool stopped) = 0;
    // The main console's timestamp button: whether each line's time shows beside it, saved for the profile's next
    // load, as status timeStamps follows. Else why not.
    virtual QString setTimeStamps(bool shown) = 0;
    // The main console's replay recording button: true records what the game sends into a new file in the profile's
    // log folder, false stops and saves it, as IFrontend::replayRecording() follows. Else why not.
    virtual QString setReplayRecording(bool record) = 0;
    // The GUI's replay button and toolbar: plays the replay file at that whole path in the profile, holds the replay
    // or lets it go on, ends it, and doubles or halves its speed within 1 and 1024 times, as IFrontend::replay()
    // follows. Else why not.
    virtual QString playReplay(const QString& path) = 0;
    virtual QString setReplayPaused(bool paused) = 0;
    virtual QString stopReplay() = 0;
    virtual QString changeReplaySpeed(bool faster) = 0;

    // Loads the map file at that whole path in place of the profile's map, or saves the map to it, as the Mapper tab of
    // Mudlet's preferences does, within the call, with IFrontend::mapFile() saying how it goes and how it ended; at
    // each step of its progress while it can be stopped, cancelAsked() says whether to stop it. Else why it did not
    // begin.
    virtual QString loadMapFile(const QString& path, const std::function<bool()>& cancelAsked) = 0;
    virtual QString saveMapFile(const QString& path, const std::function<bool()>& cancelAsked) = 0;
    // Empties the profile's map as the Mapper tab's "Delete map" does, keeping the map files saved before; else why not
    virtual QString deleteMap() = 0;
    // The profile's SOCKS5 proxy, as the Connection tab of Mudlet's preferences holds it, but for its password, which
    // only openProfile gives
    struct Proxy
    {
        bool use = false;
        QString host;
        // 0 for none
        int port = 0;
        QString user;
    };
    // Else why it was not read
    virtual std::variant<Proxy, QString> proxy() = 0;
    // Kept with the profile and used from its next connection; else why it was not changed
    virtual QString setProxy(const Proxy& proxy) = 0;
    // The profile's settings named in keys, by the keys Lua's getConfig() takes, each a bool, a double or a string; a
    // key the engine does not know is left out. Else why none were read.
    virtual std::variant<QVariantMap, QString> settings(const QStringList& keys) = 0;
    // Changes them as Lua's setConfig() does, putting each key it does not take into refused with why; else why none
    // were changed
    virtual QString setSettings(const QVariantMap& values, QMap<QString, QString>& refused) = 0;
    // The profile's triggers, aliases, scripts, timers, keys and buttons, as the desktop's script editor reads and
    // changes them (THeadlessSession's scriptItems() and the rest); each gives why it did not where it did not
    enum class ScriptItemKind { trigger, alias, script, timer, key, button };
    // Depth first, each folder before what is in it, in the editor's order
    virtual std::variant<QList<ScriptItemInfo>, QString> scriptItems(ScriptItemKind kind) const = 0;
    // All of an item's fields, by the names editScriptItem() takes
    virtual std::variant<QVariantMap, QString> scriptItem(ScriptItemKind kind, int item) = 0;
    // The new item's id: in the folder parent, or at the top level for 0, at position among the items shown there
    virtual std::variant<int, QString> addScriptItem(ScriptItemKind kind, int parent, int position, bool folder, const QString& name) = 0;
    // Sets the fields given, keeps the rest, and saves the item as the editor's save does; a compile error is no
    // refusal, as scriptItem() then gives it
    virtual QString editScriptItem(ScriptItemKind kind, int item, const QVariantMap& fields) = 0;
    virtual QString enableScriptItem(ScriptItemKind kind, int item, bool enabled) = 0;
    virtual QString moveScriptItem(ScriptItemKind kind, int item, int parent, int position) = 0;
    virtual QString deleteScriptItem(ScriptItemKind kind, int item) = 0;
    // Where in an item searchScriptItems() found its text (THeadlessSession::ScriptSearchField)
    enum class ScriptSearchField { name, command, commandDown, commandUp, pattern, event, css, lua };
    struct ScriptSearchMatch
    {
        ScriptItemKind kind = ScriptItemKind::trigger;
        int item = 0;
        QString name;
        ScriptSearchField field = ScriptSearchField::name;
        // Which pattern, event handler or line of the field, from 0
        int index = 0;
        // That field or line, and where in it the match starts, from 0
        QString text;
        int start = 0;
    };
    struct ScriptSearch
    {
        QList<ScriptSearchMatch> matches;
        // There were more than the limit
        bool more = false;
    };
    // Where text is in the items scriptItems() gives, as the desktop editor's search finds it, at most limit places
    virtual std::variant<ScriptSearch, QString> searchScriptItems(const QString& text, bool caseSensitive, bool wholeWord, int limit) const = 0;
    // The profile's Lua variables as the desktop editor's Variables view lists them (THeadlessSession::variables())
    enum class VariableType { other, boolean, number, string, table, function };
    struct VariableKey
    {
        QString name;
        VariableType type = VariableType::string;
    };
    struct Variable
    {
        VariableKey key;
        VariableType type = VariableType::other;
        // The whole of a string, number or boolean; empty for the rest
        QString value;
        bool hidden = false;
        // A table's members, and whether every one has a number for its key
        int members = 0;
        bool membersIndexed = false;
    };
    struct Variables
    {
        QList<Variable> variables;
        // How many hidden ones were left out
        int hiddenLeftOut = 0;
    };
    // The members of the table path names, or the globals for an empty path, in the Variables view's order, the hidden
    // ones only when showHidden
    virtual std::variant<Variables, QString> variables(const QList<VariableKey>& path, bool showHidden) = 0;
    // The profile's packages in the order they were installed, as the desktop's Package Manager lists them
    virtual std::variant<QList<PackageInfo>, QString> packages() const = 0;
    // Installs the package file at path as the Package Manager's install from a file does, sysInstall included;
    // IFrontend::packageInstalled() answers unless it is refused here
    virtual QString installPackage(const QString& path) = 0;
    // Removes it as the Package Manager's Remove does, sysUninstall included
    virtual QString uninstallPackage(const QString& name) = 0;
    // What is wrong with Lua that does not compile (THeadlessSession::checkLua()): the line Lua names, from 1, or 0
    // where it names none, and its message
    struct LuaProblem
    {
        int line = 0;
        QString message;
    };
    // Nothing for code that compiles. The code is not run, and no profile need be open
    virtual std::optional<LuaProblem> checkLua(const QString& code) const = 0;
    // The error console's entries since the profile loaded or the console was last cleared, oldest first: the last
    // maxErrorConsoleEntries of them, fewer if their text passes maxErrorConsoleChars, as one message carries them all.
    // A text longer than maxErrorConsoleText is cut, with an ellipsis after it
    static constexpr qsizetype maxErrorConsoleEntries = 1000;
    static constexpr qsizetype maxErrorConsoleChars = 1000000;
    static constexpr qsizetype maxErrorConsoleText = 10000;
    virtual QList<ErrorConsoleEntry> errorConsole() const = 0;
    virtual void clearErrorConsole() = 0;
};
