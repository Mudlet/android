#pragma once

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
    // As ColorRun's, -1 for none
    int background = -1;
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

// The main window's area: the size the UI gave (empty for none), the main console's grid in characters, and where its
// text goes in pixels, which is the area less the borders scripts set
struct MainArea
{
    QSize size;
    QSize grid;
    QRect textRect;
    bool operator==(const MainArea&) const = default;
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
    // Every window the profile's scripts made, parents first: whole each time, empty with no profile open
    virtual void windows(const QList<ScriptWindowInfo>& windows) = 0;
    // A console's last WindowText::recentRows rows, as they may have changed; IEngine::windowText() is read at other times
    virtual void windowText(const QString& window, const WindowText& text) = 0;
    // Sent when it changes
    virtual void mainArea(const MainArea& area) = 0;
    // A script set a window's command line, or Mudlet emptied it after a command
    virtual void windowCommandLine(const QString& name, const QString& text) = 0;
};
