#pragma once

#include <QByteArray>
#include <QList>
#include <QRect>
#include <QSize>
#include <QString>
#include <QStringList>

#include <optional>

struct ColorRun
{
    int start = 0;
    int length = 0;
    // 0-15 for the ANSI palette, rgbFlag | 0xRRGGBB for an exact colour, -1 for the default
    int fg = -1;
    int bg = -1;
    int attributes = 0;
    // The link the run is part of among the engine's links, 0 for none
    int link = 0;
    static constexpr int rgbFlag = 0x1000000;
    enum Attribute { bold = 1, italic = 2, underline = 4, strikeOut = 8, reverse = 16, overline = 32, blink = 64, fastBlink = 128, concealed = 256 };
    bool operator==(const ColorRun&) const = default;
};

// A window a profile's scripts made, as THeadlessSession::ScriptWindow describes it, for the UI to draw
struct ScriptWindowInfo
{
    QString name;
    // Upstream's enum name: miniConsole, userWindow, label, commandLine, textBox, scrollBox, mapper or mxpFrame
    QString kind;
    // The window it is in, and that window's kind; both empty for the main window's area, a dock or an external frame
    QString parent;
    QString parentKind;
    // In the parent's pixels; empty for a console's own command line
    QRect geometry;
    bool visible = false;
    bool shown = false;
    int stackOrder = 0;
    // As ColorRun's, -1 for none; its alpha from 0 to 255, which a label's may have below 255
    int background = -1;
    int backgroundAlpha = 255;
    // Empty and 0 for none
    QString fontFamily;
    double fontSize = 0;
    bool inDock = false;
    // A user window's
    struct Dock
    {
        bool autoDock = true;
        // left, right, top or bottom
        QString area;
        bool floating = false;
        QString title;
        bool operator==(const Dock&) const = default;
    };
    std::optional<Dock> dock;
    // A mini console's, user window's or MXP frame's
    struct Console
    {
        // The row it is scrolled back to, none while it follows new lines
        std::optional<int> scrolledBackTo;
        int columns = 0;
        int rows = 0;
        bool scrollBar = false;
        bool timeStamps = false;
        bool operator==(const Console&) const = default;
    };
    std::optional<Console> console;
    bool operator==(const ScriptWindowInfo&) const = default;
};

// Rows of a mini console, user window or MXP frame, after its wrap
struct WindowText
{
    struct Row
    {
        QString text;
        // A fg is always a colour, as the UI does not know the console's own; a bg of -1 is the window's
        QList<ColorRun> runs;
        bool prompt = false;
        // Carries on from the row above
        bool wrapped = false;
    };
    // How many rows the console holds, and the one rows starts at, from 0
    int lineCount = 0;
    int first = 0;
    QList<Row> rows;
    // The last rows a window's text is sent with unasked
    static constexpr int recentRows = 300;
};

// What a label shows, as THeadlessSession::LabelContent describes it: its geometry, background and font are its
// ScriptWindowInfo's
struct LabelInfo
{
    QString name;
    // Qt rich text; empty while an image or movie shows
    QString text;
    // The file of a raster image while it shows, or of an SVG one, drawn under the text with the tint, rotation and shear
    QString image;
    bool svg = false;
    // As ColorRun's, -1 for none, with its alpha from 0 to 255
    int svgTint = -1;
    int svgTintAlpha = 255;
    double svgRotation = 0;
    double svgShearX = 0;
    double svgShearY = 0;
    // The file of a movie while it plays or is paused; the frame it shows while paused, -1 while it plays
    QString movie;
    bool moviePaused = false;
    int movieFrame = -1;
    // In percent of the file's own speed
    int movieSpeed = 100;
    // Invalid for the file's own
    QSize movieSize;
    QString toolTip;
    bool clickThrough = false;
    QString accessibleName;
    bool accessibleButton = false;
    // The label's own, which Mudlet does not interpret
    QString styleSheet;
    bool operator==(const LabelInfo&) const = default;
};

// A button on a button bar, or an entry of a button's menu, as THeadlessSession::Button describes it
struct BarButton
{
    // Its action's id, which IEngine::pressButton() takes
    int id = 0;
    QString name;
    // The icon's file, for IEngine::image(); empty for none
    QString icon;
    // Its own, over its bar's; empty for a menu entry
    QString styleSheet;
    // A push-down button stays down, checked, from one press to the next
    bool pushDown = false;
    bool checked = false;
    // A folder, whose press opens a menu of its children
    bool menu = false;
    QList<BarButton> children;
};

// A bar of buttons a profile's scripts or packages made, as THeadlessSession::ButtonBar describes it
struct ButtonBar
{
    int id = 0;
    QString name;
    QString styleSheet;
    // Upstream's enum name: top, left or right for the strip of the main console it is in, floating for a toolbar
    QString place;
    bool vertical = false;
    // A horizontal bar fills each column top to bottom with this many buttons, a vertical one each row left to right
    int columns = 1;
    // How many places of that grid are left empty before the first button
    int fillerOffset = 0;
    QList<BarButton> buttons;
};

// A command a package's addCommand() added, as THeadlessSession::AddonCommand describes it; its pulse colours and
// interval are left out
struct AddonCommandInfo
{
    // What addCommand() returned, which IEngine::activateCommand() takes
    int id = 0;
    // Empty for one no package's code added
    QString package;
    QString name;
    QString icon;
    QString tooltip;
    // The submenus it sits in, separated by '/', and its shortcut as the script wrote it; both empty off the menu
    QString menuPath;
    QString shortcut;
    bool onMenu = false;
    bool onToolbar = false;
    bool enabled = true;
    bool checkable = false;
    bool checked = false;
    bool pinned = false;
    bool pulse = false;
};

// The main window's area: the size the UI gave (empty for none), the main console's grid in characters, and where its
// text goes in pixels, which is the area less the borders scripts set
struct MainArea
{
    QSize size;
    QSize grid;
    QRect textRect;
    bool operator==(const MainArea&) const = default;
};

// The profile's 2D map as its mapper window shows it, drawn at that window's size in pixels, so that a point of it is a
// point of the window
struct MapFrame
{
    QByteArray png;
    QSize size;
    // Whether the map is only viewed, not edited
    bool viewOnly = true;
};

// Everything the engine tells its host. Every method must be implementable
// over a byte stream, so arguments are plain values only.
class IFrontend
{
public:
    virtual ~IFrontend() = default;
    virtual void line(const QString& text, const QList<ColorRun>& runs, bool isPrompt) = 0;
    // The line still being written below the last one, shown until it is replaced; empty for none
    virtual void tail(const QString& text, const QList<ColorRun>& runs, bool isPrompt) = 0;
    // The console was emptied: every line sent before, and the tail, are gone
    virtual void cleared() = 0;
    virtual void notify(const QString& title, const QString& text) = 0;
    virtual void status(const QString& key, const QString& value) = 0;
    // The game hides what the player types, as for a password, or has stopped
    virtual void hiddenInput(bool wanted) = 0;
    // The input line's history, newest first, once a profile has loaded; IEngine::history() is read at other times
    virtual void history(const QStringList& lines) = 0;
    // A script set the whole input line
    virtual void commandLine(const QString& text) = 0;
    // What a script asks screen readers to say; assertive interrupts what they are saying
    virtual void announce(const QString& text, bool assertive) = 0;
    // What IEngine::link() gives for these links may have changed
    virtual void linksChanged(const QList<int>& ids) = 0;
    // A script's alert() or the game's bell asks for the player's attention for that long, 0 until they come back
    virtual void attention(int ms) = 0;
    // What the player typed ahead of the game hiding input is to move from the input line into the masked one
    virtual void moveTypedLine() = 0;
    // Every window the profile's scripts made, parents first, and what each of its labels shows: whole each time,
    // empty with no profile open
    virtual void windows(const QList<ScriptWindowInfo>& windows, const QList<LabelInfo>& labels) = 0;
    // A console's last WindowText::recentRows rows, as they may have changed; IEngine::windowText() is read at other times
    virtual void windowText(const QString& window, const WindowText& text) = 0;
    // Sent when it changes
    virtual void mainArea(const MainArea& area) = 0;
    // A script set a window's command line, or Mudlet emptied it after a command
    virtual void windowCommandLine(const QString& name, const QString& text) = 0;
    // The profile's button bars, in the order of its actions: whole each time, empty with no profile open
    virtual void buttonBars(const QList<ButtonBar>& bars) = 0;
    // The commands the profile's packages added, in the order they were added: whole each time, empty with no profile
    // open
    virtual void commands(const QList<AddonCommandInfo>& commands) = 0;
    // The map drawn again while the mapper window shows; none once it hides or goes, or the map could not be drawn
    virtual void map(const std::optional<MapFrame>& frame) = 0;
};
