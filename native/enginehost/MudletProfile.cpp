#include "MudletProfile.h"

#include "MudletEngine.h"
#include "THeadlessSession.h"

#include <QBuffer>
#include <QDateTime>
#include <QDir>
#include <QEventLoop>
#include <QFileInfo>
#include <QHash>
#include <QIcon>
#include <QImage>
#include <QImageReader>
#include <QPainter>
#include <QPixmap>
#include <QRegularExpression>
#include <QUrl>

#include <algorithm>
#include <utility>

namespace {

using ScriptWindow = THeadlessSession::ScriptWindow;
using TextRun = THeadlessSession::TextRun;
using TextStyle = THeadlessSession::TextStyle;

// A pattern for percent-encoded text: the text as it is, but each %XX's hex digits in either case. escape() makes
// each '%' "\%" and leaves hex digits as they are.
QString percentEncodedPattern(const QString& text)
{
    static const QRegularExpression escapedPercent(QStringLiteral("\\\\%([0-9A-Fa-f]{2})"));
    return QRegularExpression::escape(text).replace(escapedPercent, QStringLiteral("%(?i:\\1)"));
}

// Mudlet's console message as the reason the map download from url failed: without its "[ ERROR ] - " tag, on one
// line, with the URL's password and query as *** and of any URL in it only the scheme and host. The patch already cuts
// the URL in Qt's own text to that, but the server's own words, which the message keeps, can repeat the URL whole, its
// user name, password and token included, or its path and query on their own. The password is masked wherever it
// shows, the query wherever it follows a '?', and the URL's own user name taken whole where a URL repeats it, each
// decoded, as Qt shows it (PrettyDecoded) and encodes it, and as the game's URL writes it, the encoded forms with their
// hex digits in either case.
QString mapDownloadError(QString reason, const QString& url)
{
    static const QRegularExpression tag(QStringLiteral("^\\s*\\[\\s*\\w+\\s*\\]\\s*-\\s*"));
    static const QRegularExpression space(QStringLiteral("\\s+"));
    // The URL's user name, password and query as the game wrote them, read as QUrl reads them: the user info ends at
    // its first '@', and the user name at the user info's first ':'
    static const QRegularExpression written(QStringLiteral("^(?:[A-Za-z][A-Za-z0-9+.-]*://)?(?:([^/?#:@]*)(?::([^/?#@]*))?@)?[^?#]*(?:\\?([^#]*))?"));
    const QUrl downloaded = QUrl::fromUserInput(url);
    const QRegularExpressionMatch writtenParts = written.match(url.trimmed());
    // The patterns of a part of the URL as a server can repeat it, each with the length of the text it stands for:
    // decoded, as Qt shows or encodes it, and as the game wrote it, whose percent-encoding Qt normalises. Decoded, it
    // is matched as it is, not in any case, which could take a word of the reason. A part QUrl does not find goes in
    // no request, and the game's text then reads otherwise than QUrl reads it, so it has none
    using Part = QString (QUrl::*)(QUrl::ComponentFormattingOptions) const;
    const auto forms = [&](Part part, int group) {
        QList<std::pair<qsizetype, QString>> patterns;
        if (const QString decoded = (downloaded.*part)(QUrl::FullyDecoded); !decoded.isEmpty()) {
            patterns.append({decoded.size(), QRegularExpression::escape(decoded)});
            for (const QString& form : {(downloaded.*part)(QUrl::PrettyDecoded), (downloaded.*part)(QUrl::FullyEncoded), writtenParts.captured(group)}) {
                patterns.append({form.size(), percentEncodedPattern(form)});
            }
        }
        // One that is empty would match everywhere, and one that is no pattern (a lone surrogate) would stop anyUrl
        patterns.removeIf([](const auto& pattern) {
            return pattern.first == 0 || !QRegularExpression(pattern.second).isValid();
        });
        return patterns;
    };
    // The password and the query, where a token goes, first, as decoded they can hold the '/', '@' or space that would
    // end a URL early for anyUrl; the longest first, as one can hold another
    QList<std::pair<qsizetype, QString>> secrets = forms(&QUrl::password, 2);
    for (const auto& [size, pattern] : forms(&QUrl::query, 3)) {
        secrets.append({size, QStringLiteral("(?<=\\?)") + pattern});
    }
    std::stable_sort(secrets.begin(), secrets.end(), [](const auto& a, const auto& b) {
        return a.first > b.first;
    });
    for (const auto& [size, pattern] : std::as_const(secrets)) {
        reason.replace(QRegularExpression(pattern), QStringLiteral("***"));
    }
    // Any URL: its scheme and host. The host follows the user info's last '@' before the path, query or fragment, as a
    // browser reads a URL, so a user name repeated decoded can hold an '@'; the URL's own user name is taken whole,
    // so a space, '/', '?' or '#' in it does not end the user info early
    QStringList userNames;
    for (const auto& [size, pattern] : forms(&QUrl::userName, 1)) {
        userNames.append(pattern);
    }
    const QRegularExpression anyUrl(QStringLiteral("([A-Za-z][A-Za-z0-9+.-]*://)(?:(?:%1)?[^/\\s?#]*@)?([^/\\s?#]*)\\S*").arg(userNames.join(QLatin1Char('|'))));
    reason.remove(tag);
    reason.replace(anyUrl, QStringLiteral("\\1\\2"));
    reason.replace(space, QStringLiteral(" "));
    reason = reason.trimmed();
    return reason.isEmpty() ? QStringLiteral("the map download failed") : reason;
}

QString luaLongString(const QString& text)
{
    return QStringLiteral("[==[%1]==]").arg(text);
}

int colourCode(const QColor& colour, bool profileDefault)
{
    return profileDefault || !colour.isValid() ? -1 : int(ColorRun::rgbFlag | (colour.rgb() & 0xFFFFFF));
}

int attributeBits(THeadlessSession::TextStyles styles)
{
    static constexpr std::pair<TextStyle, int> bits[] = {
            {TextStyle::Bold, ColorRun::bold},
            {TextStyle::Italic, ColorRun::italic},
            {TextStyle::Underline, ColorRun::underline},
            {TextStyle::StrikeOut, ColorRun::strikeOut},
            {TextStyle::Reverse, ColorRun::reverse},
            {TextStyle::Overline, ColorRun::overline},
            {TextStyle::Blink, ColorRun::blink},
            {TextStyle::FastBlink, ColorRun::fastBlink},
            {TextStyle::Concealed, ColorRun::concealed},
    };
    int attributes = 0;
    for (const auto& [style, bit] : bits) {
        if (styles.testFlag(style)) {
            attributes |= bit;
        }
    }
    return attributes;
}

// Runs that are the profile's own colours with no style or link are left out. A link's id
// is the session's plus linkBase; linkTop is raised to the highest one.
// exactForeground: the default foreground as its colour too, as for a script window, whose own the UI does not know.
QList<ColorRun> colorRuns(const QList<TextRun>& runs, int linkBase, int& linkTop, bool exactForeground = false)
{
    QList<ColorRun> out;
    for (const TextRun& run : runs) {
        const ColorRun converted{run.start,
                                 run.length,
                                 colourCode(run.foreground, run.defaultForeground && !exactForeground),
                                 colourCode(run.background, run.defaultBackground),
                                 attributeBits(run.styles),
                                 run.link == 0 ? 0 : linkBase + run.link};
        if (converted.length > 0 && (converted.fg != -1 || converted.bg != -1 || converted.attributes != 0 || converted.link != 0)) {
            out.append(converted);
            linkTop = std::max(linkTop, converted.link);
        }
    }
    return out;
}

// The runs over [from, to) of a text, counted from from
QList<ColorRun> sliceRuns(const QList<ColorRun>& runs, int from, int to)
{
    QList<ColorRun> out;
    for (ColorRun run : runs) {
        const int start = std::max(run.start, from);
        const int end = std::min(run.start + run.length, to);
        if (start < end) {
            run.start = start - from;
            run.length = end - start;
            out.append(run);
        }
    }
    return out;
}

QString kindName(ScriptWindow::Kind kind)
{
    switch (kind) {
    case ScriptWindow::Kind::miniConsole:
        return QStringLiteral("miniConsole");
    case ScriptWindow::Kind::userWindow:
        return QStringLiteral("userWindow");
    case ScriptWindow::Kind::label:
        return QStringLiteral("label");
    case ScriptWindow::Kind::commandLine:
        return QStringLiteral("commandLine");
    case ScriptWindow::Kind::textBox:
        return QStringLiteral("textBox");
    case ScriptWindow::Kind::scrollBox:
        return QStringLiteral("scrollBox");
    case ScriptWindow::Kind::mapper:
        return QStringLiteral("mapper");
    case ScriptWindow::Kind::mxpFrame:
        return QStringLiteral("mxpFrame");
    }
    return {};
}

QString dockAreaName(Qt::DockWidgetArea area)
{
    switch (area) {
    case Qt::LeftDockWidgetArea:
        return QStringLiteral("left");
    case Qt::TopDockWidgetArea:
        return QStringLiteral("top");
    case Qt::BottomDockWidgetArea:
        return QStringLiteral("bottom");
    default:
        return QStringLiteral("right");
    }
}

bool isConsole(ScriptWindow::Kind kind)
{
    return kind == ScriptWindow::Kind::miniConsole || kind == ScriptWindow::Kind::userWindow || kind == ScriptWindow::Kind::mxpFrame;
}

ScriptWindowInfo windowInfo(const ScriptWindow& window)
{
    ScriptWindowInfo info;
    info.name = window.name;
    info.kind = kindName(window.kind);
    info.parent = window.parent;
    if (!window.parent.isEmpty()) {
        info.parentKind = kindName(window.parentKind);
    }
    info.geometry = window.geometry;
    info.visible = window.visible;
    info.shown = window.shown;
    info.stackOrder = window.stackOrder;
    info.background = colourCode(window.background, false);
    if (info.background >= 0) {
        info.backgroundAlpha = window.background.alpha();
    }
    // The other kinds have a default QFont, which draws nothing
    if (isConsole(window.kind) || window.kind == ScriptWindow::Kind::label || window.kind == ScriptWindow::Kind::textBox) {
        info.fontFamily = window.font.family();
        info.fontSize = std::max(window.font.pointSizeF(), 0.0);
    }
    info.inDock = window.inDock;
    if (window.dock) {
        info.dock = ScriptWindowInfo::Dock{window.dock->autoDock, dockAreaName(window.dock->area), window.dock->floating, window.dock->title};
    }
    if (isConsole(window.kind)) {
        info.console = ScriptWindowInfo::Console{window.scrolledBackTo, window.grid.width(), window.grid.height(), window.scrollBar, window.timeStamps};
    }
    if (window.frameTabs) {
        ScriptWindowInfo::FrameTabs tabs;
        for (const ScriptWindow::FrameTabs::Page& page : window.frameTabs->pages) {
            tabs.pages.append(page.name);
            tabs.titles.append(page.title);
        }
        tabs.current = window.frameTabs->current;
        info.frameTabs = tabs;
    }
    info.styleSheet = window.styleSheet;
    return info;
}

QString liveRegionName(THeadlessSession::LabelContent::LiveRegion liveRegion)
{
    switch (liveRegion) {
    case THeadlessSession::LabelContent::LiveRegion::polite:
        return QStringLiteral("polite");
    case THeadlessSession::LabelContent::LiveRegion::assertive:
        return QStringLiteral("assertive");
    case THeadlessSession::LabelContent::LiveRegion::off:
        break;
    }
    return {};
}

LabelInfo labelInfo(const QString& name, const THeadlessSession::LabelContent& content)
{
    LabelInfo info;
    info.name = name;
    info.text = content.text;
    info.image = content.image;
    info.svg = content.svg;
    info.svgTint = colourCode(content.svgTint, false);
    if (info.svgTint >= 0) {
        info.svgTintAlpha = content.svgTint.alpha();
    }
    info.svgRotation = content.svgRotation;
    info.svgShearX = content.svgShearX;
    info.svgShearY = content.svgShearY;
    info.movie = content.movie;
    info.moviePaused = content.moviePaused;
    info.movieFrame = content.movieFrame;
    info.movieSpeed = content.movieSpeed;
    info.movieSize = content.movieSize;
    info.toolTip = content.toolTip;
    info.clickThrough = content.clickThrough;
    info.accessibleName = content.accessibleName;
    info.accessibleButton = content.accessibleButton;
    info.liveRegion = liveRegionName(content.liveRegion);
    if (const auto& value = content.accessibleValue) {
        info.accessibleValue = LabelInfo::AccessibleValue{.value = value->value, .min = value->min, .max = value->max};
    }
    info.styleSheet = content.styleSheet;
    return info;
}

TextBoxInfo textBoxInfo(const QString& name, const THeadlessSession::TextBoxContent& content)
{
    return {name, content.text, content.readOnly, content.placeholder, content.styleSheet, content.tabMovesFocus};
}

QString placeName(THeadlessSession::ButtonBar::Place place)
{
    switch (place) {
    case THeadlessSession::ButtonBar::Place::top:
        return QStringLiteral("top");
    case THeadlessSession::ButtonBar::Place::left:
        return QStringLiteral("left");
    case THeadlessSession::ButtonBar::Place::right:
        return QStringLiteral("right");
    case THeadlessSession::ButtonBar::Place::floating:
        return QStringLiteral("floating");
    }
    return {};
}

BarButton barButton(const THeadlessSession::Button& button)
{
    BarButton info;
    info.id = button.id;
    info.name = button.name;
    info.icon = button.icon;
    info.styleSheet = button.styleSheet;
    info.pushDown = button.pushDown;
    info.checked = button.checked;
    info.menu = button.menu;
    for (const THeadlessSession::Button& child : button.children) {
        info.children.append(barButton(child));
    }
    return info;
}

ButtonBar buttonBar(const THeadlessSession::ButtonBar& bar)
{
    ButtonBar info;
    info.id = bar.id;
    info.name = bar.name;
    info.styleSheet = bar.styleSheet;
    info.place = placeName(bar.place);
    info.vertical = bar.orientation == Qt::Vertical;
    info.columns = bar.columns;
    info.fillerOffset = bar.fillerOffset;
    for (const THeadlessSession::Button& button : bar.buttons) {
        info.buttons.append(barButton(button));
    }
    return info;
}

AddonCommandInfo addonCommandInfo(const THeadlessSession::AddonCommand& command)
{
    AddonCommandInfo info;
    info.id = command.id;
    info.package = command.package;
    info.name = command.name;
    info.icon = command.icon;
    info.tooltip = command.tooltip;
    info.menuPath = command.menuPath;
    info.shortcut = command.shortcut;
    info.onMenu = command.onMenu;
    info.onToolbar = command.onToolbar;
    info.enabled = command.enabled;
    info.checkable = command.checkable;
    info.checked = command.checked;
    info.pinned = command.pinned;
    info.pulse = command.pulse;
    return info;
}

// The mapper window's size while it shows, none otherwise
std::optional<QSize> shownMapperSize(const QList<ScriptWindow>& windows)
{
    const auto mapper = std::ranges::find(windows, ScriptWindow::Kind::mapper, &ScriptWindow::kind);
    if (mapper == windows.cend() || !mapper->shown) {
        return std::nullopt;
    }
    return mapper->geometry.size();
}


// A pointer event on a map, as the session takes it; none for a type the map does not take
std::optional<THeadlessSession::MapPointerEvent> mapPointerEvent(const PointerEvent& event)
{
    THeadlessSession::MapPointerEvent sessionEvent;
    switch (event.type) {
    case PointerEvent::Type::press:
        sessionEvent.type = THeadlessSession::MapPointerEvent::Type::press;
        break;
    case PointerEvent::Type::doubleClick:
        sessionEvent.type = THeadlessSession::MapPointerEvent::Type::doubleClick;
        break;
    case PointerEvent::Type::wheel:
        sessionEvent.type = THeadlessSession::MapPointerEvent::Type::wheel;
        break;
    default:
        return std::nullopt;
    }
    sessionEvent.position = event.position;
    sessionEvent.button = Qt::MouseButton(event.button);
    sessionEvent.modifiers = Qt::KeyboardModifiers::fromInt(event.modifiers);
    sessionEvent.angleDelta = QPoint(0, event.wheelY);
    return sessionEvent;
}

THeadlessSession::MapTarget sessionMap(IEngine::MapTarget map)
{
    return map == IEngine::MapTarget::app ? THeadlessSession::MapTarget::app : THeadlessSession::MapTarget::script;
}

// As in libmudlet, "main" names the main console too, whose ids are offset
bool isMainConsole(const QString& window)
{
    return window.isEmpty() || window == QLatin1String("main");
}

PackageInstallResult packageInstallResult(const THeadlessSession::PackageResult result)
{
    switch (result) {
    case THeadlessSession::PackageResult::installed:
        return PackageInstallResult::installed;
    case THeadlessSession::PackageResult::installedWithErrors:
        return PackageInstallResult::installedWithErrors;
    case THeadlessSession::PackageResult::alreadyInstalled:
        return PackageInstallResult::alreadyInstalled;
    case THeadlessSession::PackageResult::failed:
        return PackageInstallResult::failed;
    }
    return PackageInstallResult::failed;
}

THeadlessSession::ScriptItemKind sessionKind(const IEngine::ScriptItemKind kind)
{
    switch (kind) {
    case IEngine::ScriptItemKind::trigger:
        return THeadlessSession::ScriptItemKind::trigger;
    case IEngine::ScriptItemKind::alias:
        return THeadlessSession::ScriptItemKind::alias;
    case IEngine::ScriptItemKind::script:
        return THeadlessSession::ScriptItemKind::script;
    case IEngine::ScriptItemKind::timer:
        return THeadlessSession::ScriptItemKind::timer;
    case IEngine::ScriptItemKind::key:
        return THeadlessSession::ScriptItemKind::key;
    case IEngine::ScriptItemKind::button:
        break;
    }
    return THeadlessSession::ScriptItemKind::button;
}

IEngine::ScriptItemKind engineKind(const THeadlessSession::ScriptItemKind kind)
{
    switch (kind) {
    case THeadlessSession::ScriptItemKind::trigger:
        return IEngine::ScriptItemKind::trigger;
    case THeadlessSession::ScriptItemKind::alias:
        return IEngine::ScriptItemKind::alias;
    case THeadlessSession::ScriptItemKind::script:
        return IEngine::ScriptItemKind::script;
    case THeadlessSession::ScriptItemKind::timer:
        return IEngine::ScriptItemKind::timer;
    case THeadlessSession::ScriptItemKind::key:
        return IEngine::ScriptItemKind::key;
    case THeadlessSession::ScriptItemKind::button:
        break;
    }
    return IEngine::ScriptItemKind::button;
}

IEngine::ScriptSearchField engineField(const THeadlessSession::ScriptSearchField field)
{
    switch (field) {
    case THeadlessSession::ScriptSearchField::name:
        return IEngine::ScriptSearchField::name;
    case THeadlessSession::ScriptSearchField::command:
        return IEngine::ScriptSearchField::command;
    case THeadlessSession::ScriptSearchField::commandDown:
        return IEngine::ScriptSearchField::commandDown;
    case THeadlessSession::ScriptSearchField::commandUp:
        return IEngine::ScriptSearchField::commandUp;
    case THeadlessSession::ScriptSearchField::pattern:
        return IEngine::ScriptSearchField::pattern;
    case THeadlessSession::ScriptSearchField::event:
        return IEngine::ScriptSearchField::event;
    case THeadlessSession::ScriptSearchField::css:
        return IEngine::ScriptSearchField::css;
    case THeadlessSession::ScriptSearchField::lua:
        break;
    }
    return IEngine::ScriptSearchField::lua;
}

IEngine::VariableType engineType(const THeadlessSession::VariableType type)
{
    switch (type) {
    case THeadlessSession::VariableType::boolean:
        return IEngine::VariableType::boolean;
    case THeadlessSession::VariableType::number:
        return IEngine::VariableType::number;
    case THeadlessSession::VariableType::string:
        return IEngine::VariableType::string;
    case THeadlessSession::VariableType::table:
        return IEngine::VariableType::table;
    case THeadlessSession::VariableType::function:
        return IEngine::VariableType::function;
    case THeadlessSession::VariableType::other:
        break;
    }
    return IEngine::VariableType::other;
}

THeadlessSession::VariableType sessionType(const IEngine::VariableType type)
{
    switch (type) {
    case IEngine::VariableType::boolean:
        return THeadlessSession::VariableType::boolean;
    case IEngine::VariableType::number:
        return THeadlessSession::VariableType::number;
    case IEngine::VariableType::string:
        return THeadlessSession::VariableType::string;
    case IEngine::VariableType::table:
        return THeadlessSession::VariableType::table;
    case IEngine::VariableType::function:
        return THeadlessSession::VariableType::function;
    case IEngine::VariableType::other:
        break;
    }
    return THeadlessSession::VariableType::other;
}

QString cut(const QString& text)
{
    return text.size() > IEngine::maxErrorConsoleText ? text.left(IEngine::maxErrorConsoleText) + QChar(0x2026) : text;
}

qsizetype chars(const ErrorConsoleEntry& entry)
{
    return entry.object.size() + entry.function.size() + entry.event.size() + entry.message.size();
}

// As the protocol names them
QString kindName(const THeadlessSession::ScriptItemKind kind)
{
    switch (kind) {
    case THeadlessSession::ScriptItemKind::trigger:
        return QStringLiteral("trigger");
    case THeadlessSession::ScriptItemKind::alias:
        return QStringLiteral("alias");
    case THeadlessSession::ScriptItemKind::script:
        return QStringLiteral("script");
    case THeadlessSession::ScriptItemKind::timer:
        return QStringLiteral("timer");
    case THeadlessSession::ScriptItemKind::key:
        return QStringLiteral("key");
    case THeadlessSession::ScriptItemKind::button:
        break;
    }
    return QStringLiteral("button");
}

TMainWindowInfo::Device sessionDevice(const MainWindowDevice& device)
{
    TMainWindowInfo::Device given;
    given.pointer = device.coarsePointer ? TMainWindowInfo::Pointer::coarse : TMainWindowInfo::Pointer::fine;
    given.hover = device.hover;
    given.devicePixelRatio = device.devicePixelRatio;
    given.pixelScale = device.pixelScale;
    return given;
}

} // namespace

MudletProfile::MudletProfile(MudletEngine& engine, const QString& profile)
: QObject(&engine)
, mEngine(engine)
, mFrontend(engine.mHost->controlFrontend())
, mSession(std::make_unique<THeadlessSession>())
, mProfile(profile)
{
    connectSession();
    mCounterTimer.setSingleShot(true);
    mCounterTimer.setInterval(100);
    connect(&mCounterTimer, &QTimer::timeout, this, &MudletProfile::flushCounters);
    mRestoreTimer.setSingleShot(true);
    connect(&mRestoreTimer, &QTimer::timeout, this, &MudletProfile::beginRestore);
}

MudletProfile::~MudletProfile()
{
    // Nothing the session sends as it goes may reach the members already destroyed
    QObject::disconnect(mSession.get(), nullptr, this, nullptr);
    mSession.reset();
}

void MudletProfile::renewSession()
{
    QObject::disconnect(mSession.get(), nullptr, this, nullptr);
    // Not deleted here, as this runs from one of its signals
    mSession.release()->deleteLater();
    mSession = std::make_unique<THeadlessSession>();
    connectSession();
}

void MudletProfile::rename(const QString& profile)
{
    if (mState == State::idle) {
        mProfile = profile;
    }
}

void MudletProfile::showIdle()
{
    mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 (not started)").arg(mProfile));
    mFrontend->status(QStringLiteral("connection"), QStringLiteral("disconnected"));
}

std::optional<SessionStore::Session> MudletProfile::kept() const
{
    if (running() || awaitingRestore()) {
        return mKept;
    }
    return std::nullopt;
}

// What the lines, mid-write signals among them, do here only touches the frontend: they must not call the session
void MudletProfile::connectSession()
{
    THeadlessSession* session = mSession.get();
    // The UI shows the box as text over the map: a picture of it is not read by TalkBack and moves with a drag
    session->setMapInfoPainted(false);
    connect(session, &THeadlessSession::startFinished, this, [this](THeadlessSession::StartResult result, const QString& error) {
        onStartFinished(int(result), error);
    });
    connect(session, &THeadlessSession::shutdownFinished, this, [this](bool saved, const QString& error) {
        mShutdownAsked = false;
        if (!mEngine.mClosing && mState == State::stopping) {
            onShutdownFinished(saved, error);
        }
    });
    // Counted only: what the UI shows comes from lineShown, once the line's triggers have run
    connect(session, &THeadlessSession::outputLine, this, [this](const QString&, const QString&, bool isPrompt) {
        ++(isPrompt ? mPromptsReceived : mLinesReceived);
        markCountersDirty();
    });
    connect(session,
            &THeadlessSession::lineShown,
            this,
            [this](const QString&, const QString& text, bool isPrompt, const QList<TextRun>& runs, qint64 arrivalMSecs, bool continuesLine, const QList<qint64>& lineIds) {
                onLineShown(text, isPrompt, colorRuns(runs, mLinkBase, mEngine.mLinkTop), arrivalMSecs, continuesLine, lineIds);
            });
    connect(session, &THeadlessSession::linesUpdated, this, [this](const QString&, const QList<THeadlessSession::LineUpdate>& updates) {
        for (const THeadlessSession::LineUpdate& update : updates) {
            onLineUpdated(update.lineId, update.text, colorRuns(update.runs, mLinkBase, mEngine.mLinkTop), update.isPrompt, update.deleted);
        }
    });
    connect(session, &THeadlessSession::localTextStyled, this, [this](const QString&, const QString& text, const QList<TextRun>& runs, bool continuesLine) {
        mLocalLines += text.count(QLatin1Char('\n'));
        markCountersDirty();
        onLocalText(text, colorRuns(runs, mLinkBase, mEngine.mLinkTop), continuesLine);
    });
    connect(session, &THeadlessSession::mainConsoleCleared, this, &MudletProfile::onMainConsoleCleared);
    connect(session, &THeadlessSession::errorConsoleText, this, [this](const QString&, const QString& text) {
        mErrorPending += text;
        qsizetype end;
        while ((end = mErrorPending.indexOf(QLatin1Char('\n'))) >= 0) {
            const QString line = mErrorPending.left(end);
            mErrorPending.remove(0, end + 1);
            if (!line.trimmed().isEmpty()) {
                ++mErrorLines;
                markCountersDirty();
                qWarning("mudlet error console: %s", qPrintable(line));
                mFrontend->status(QStringLiteral("luaError"), line);
            }
        }
    });
    connect(session, &THeadlessSession::errorConsoleEntry, this, [this](const QString&, const THeadlessSession::ErrorConsoleEntry& entry) {
        ErrorConsoleEntry kept{++mErrorConsoleCount, entry.msecs, entry.debug, cut(entry.object), cut(entry.function), cut(entry.event), cut(entry.message)};
        if (entry.id) {
            kept.kind = kindName(entry.kind);
            kept.item = entry.id;
            kept.line = entry.line;
        }
        mErrorConsole.push_back(kept);
        mErrorConsoleChars += chars(kept);
        while (std::ssize(mErrorConsole) > maxErrorConsoleEntries || mErrorConsoleChars > maxErrorConsoleChars) {
            mErrorConsoleChars -= chars(mErrorConsole.front());
            mErrorConsole.pop_front();
        }
        mFrontend->errorConsoleEntry(kept);
    });
    connect(session, &THeadlessSession::notificationRequested, this, [this](const QString&, const QString& title, const QString& text) {
        mFrontend->notify(title, text);
    });
    connect(session, &THeadlessSession::announcementRequested, this, [this](const QString&, const QString& text, bool assertive) {
        mFrontend->announce(text, assertive);
    });
    connect(session, &THeadlessSession::attentionRequested, this, [this](const QString&, int milliseconds) {
        mFrontend->attention(milliseconds);
    });
    connect(session, &THeadlessSession::typedLineMoveRequested, this, [this] {
        mFrontend->moveTypedLine();
    });
    connect(session, &THeadlessSession::saveFinished, this, [this](bool saved, const QString& error) {
        mEngine.saveFinished(this, saved, error);
    });
    connect(session, &THeadlessSession::consoleColorsChanged, this, [this](const QString&, const QColor& foreground, const QColor& background) {
        reportConsoleColors(foreground, background);
    });
    connect(session, &THeadlessSession::latencyChanged, this, [this](const QString&, std::optional<double> networkSeconds, double processingSeconds) {
        // docs/protocol.md, "lag": the latency box's two numbers, to the millisecond as it shows them
        const QString network = networkSeconds ? QString::number(*networkSeconds, 'f', 3) : QStringLiteral("-");
        mFrontend->status(QStringLiteral("lag"), QStringLiteral("%1 %2").arg(network, QString::number(processingSeconds, 'f', 3)));
    });
    connect(session, &THeadlessSession::timeStampsShownChanged, this, [this](const QString&, bool shown) {
        reportTimeStamps(shown);
    });
    connect(session, &THeadlessSession::linksChanged, this, [this](const QString&, const QList<int>& links) {
        QList<int> ids;
        ids.reserve(links.size());
        for (const int link : links) {
            ids.append(mLinkBase + link);
        }
        mFrontend->linksChanged(ids);
    });
    connect(session, &THeadlessSession::profileLoaded, this, [this](const QString& profile) {
        mEngine.mProfiles.markOpened(profile);
        mEngine.mHost->profileState(mProfile, IEngineHost::ProfileState::loaded);
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 loaded").arg(profile));
        mFrontend->status(QStringLiteral("profileLoadMs"), QString::number(mStartClock.elapsed()));
        mFrontend->history(mSession->commandHistory());
        reportConsoleColors(mSession->consoleForeground(), mSession->consoleBackground());
        reportTimeStamps(mSession->timeStampsShown());
        mLoaded = true;
        if (!mMainWindowSize.isEmpty() && !mSession->setMainWindowSize(mMainWindowSize)) {
            qWarning("MudletEngine: the main window's size %dx%d was refused: %s", mMainWindowSize.width(), mMainWindowSize.height(), qPrintable(mSession->errorString()));
            // So mainArea does not give it as the profile's, and the screen size sizes the main window
            mMainWindowSize = {0, 0};
        }
        // As early as the profile takes it, so a game that asks as it connects is not first told 90 by 25
        applyScreenSize();
        pushMainArea();
        pushWindows(true);
        pushButtonBars();
        pushCommands();
        pushKeyBindings();
        pushAppMap();
        setMapDownloadState(MapDownload::State::none);
        takeOfferedMap(mSession->offeredMapUrl());
        mFrontend->mapDownload(mMapDownload);
        mSounds = {false, mSession->soundsMuted(THeadlessSession::SoundSource::scripts), mSession->soundsMuted(THeadlessSession::SoundSource::game)};
        mFrontend->sounds(mSounds);
    });
    connect(session, &THeadlessSession::soundStarted, this, [this] {
        if (!std::exchange(mSounds.played, true)) {
            mFrontend->sounds(mSounds);
        }
    });
    connect(session, &THeadlessSession::soundsMutedChanged, this, [this](THeadlessSession::SoundSource source, bool muted) {
        (source == THeadlessSession::SoundSource::scripts ? mSounds.scriptsMuted : mSounds.gameMuted) = muted;
        mFrontend->sounds(mSounds);
    });
    connect(session, &THeadlessSession::secureConnectionOffered, this, [this] {
        qInfo("MudletEngine: %s's game offers its secure port", qPrintable(mProfile));
        setSecureOffer(true);
    });
    connect(session, &THeadlessSession::hiddenInputWanted, this, [this](const QString&, bool wanted) {
        mFrontend->hiddenInput(wanted);
    });
    connect(session, &THeadlessSession::commandLineTextRequested, this, [this](const QString&, const QString& text) {
        mFrontend->commandLine(text);
    });
    // The script windows' signals come from the event loop, so these may call the session
    connect(session, &THeadlessSession::scriptWindowsChanged, this, [this] {
        pushWindows(false);
    });
    connect(session, &THeadlessSession::windowTextChanged, this, [this](const QString&, const QStringList& windows) {
        for (const QString& window : windows) {
            pushWindowText(window);
        }
    });
    connect(session, &THeadlessSession::windowCommandLineTextChanged, this, [this](const QString&, const QStringList& commandLines) {
        for (const QString& name : commandLines) {
            if (const std::optional<QString> text = mSession->windowCommandLineText(name)) {
                mFrontend->windowCommandLine(name, *text);
            }
        }
    });
    connect(session, &THeadlessSession::buttonsChanged, this, &MudletProfile::pushButtonBars);
    connect(session, &THeadlessSession::addonCommandsChanged, this, &MudletProfile::pushCommands);
    connect(session, &THeadlessSession::packagesChanged, this, [this] {
        mFrontend->packagesChanged();
    });
    connect(session, &THeadlessSession::packageInstallFinished, this, [this](const QString& path, THeadlessSession::PackageResult result, const QString& error) {
        mFrontend->packageInstalled(path, packageInstallResult(result), error);
    });
    connect(session, &THeadlessSession::keyBindingsChanged, this, &MudletProfile::pushKeyBindings);
    // From the event loop; a pause asked for before the start arrived holds it already
    connect(session, &THeadlessSession::replayStarted, this, [this] {
        mReplay = {true, mSession->replayPaused(), 1};
        mFrontend->replay(mReplay);
    });
    connect(session, &THeadlessSession::replaySpeedChanged, this, [this](const QString&, int speed) {
        mReplay.speed = speed;
        mFrontend->replay(mReplay);
    });
    connect(session, &THeadlessSession::replayFinished, this, [this] {
        mReplay = {};
        mFrontend->replay(mReplay);
    });
    // A repaint comes from the event loop, once a turn; the view-only flag is in each frame
    const auto pushShownMap = [this] {
        pushMap(shownMapperSize(mSession->scriptWindows()));
        pushAppMap();
    };
    connect(session, &THeadlessSession::mapRepaintRequested, this, pushShownMap);
    connect(session, &THeadlessSession::mapViewOnlyChanged, this, pushShownMap);
    connect(session, &THeadlessSession::offeredMapChanged, this, [this] {
        takeOfferedMap(mSession->offeredMapUrl());
        mFrontend->mapDownload(mMapDownload);
    });
    // The progress of a map import or export a script began is not the download's. Its relays come from the event loop,
    // so the last of them can come after a download that began in the same pass: the download's progress is what
    // follows a start. No other map operation can start while a download runs, so the last start is the download's own,
    // which can be cancelled.
    connect(session, &THeadlessSession::mapProgressStarted, this, [this](const QString&, const QString&, const QString& label, int maximum) {
        if (mMapDownload.state != MapDownload::State::downloading) {
            return;
        }
        mMapDownloadProgressStarted = true;
        takeMapDownloadProgress(label, 0, maximum, true);
    });
    connect(session, &THeadlessSession::mapProgressChanged, this, [this](const QString&, const QString& label, int value, int maximum) {
        takeMapDownloadProgress(label, value, maximum, label != mMapDownload.label || maximum != mMapDownload.maximum);
    });
    // A map file runs within its call, whose progress is relayed at its steps; the end, relayed after it returns, is
    // the call's own
    connect(session, &THeadlessSession::mapProgressStarted, this, [this](const QString&, const QString&, const QString& label, int maximum) {
        // Mudlet's JSON map alone can be stopped, which a start says only to the session
        if (mMapFile) {
            takeMapFileProgress(label, 0, maximum, mMapFile->json);
        }
    });
    connect(session, &THeadlessSession::mapProgressChanged, this, [this](const QString&, const QString& label, int value, int maximum) {
        if (mMapFile) {
            takeMapFileProgress(label, value, maximum, mMapFile->file.cancelable);
        }
    });
    connect(session, &THeadlessSession::mapProgressCancelDisabled, this, [this] {
        if (mMapFile) {
            const MapFile& file = mMapFile->file;
            takeMapFileProgress(file.label, file.value, file.maximum, false);
        }
        if (mMapDownloadProgressStarted && std::exchange(mMapDownload.cancelable, false)) {
            pushMapDownloadProgress(true);
        }
    });
    connect(session, &THeadlessSession::mapDownloadFinished, this, [this, pushShownMap](const QString&, bool loaded, const QString& error) {
        if (mMapDownload.state != MapDownload::State::downloading) {
            return;
        }
        if (loaded) {
            setMapDownloadState(MapDownload::State::loaded);
            qInfo("MudletEngine: the map from %s loaded", qPrintable(mMapDownload.host));
        } else if (mMapDownloadCancelled) {
            setMapDownloadState(MapDownload::State::cancelled);
            qInfo("MudletEngine: the map download from %s was cancelled", qPrintable(mMapDownload.host));
        } else {
            setMapDownloadState(MapDownload::State::failed, mapDownloadError(error, mMapDownloadUrl));
            qWarning("MudletEngine: the map download from %s failed: %s", qPrintable(mMapDownload.host), qPrintable(mMapDownload.error));
        }
        mFrontend->mapDownload(mMapDownload);
        // The end names the host the map came from; the game may have offered another since
        const MapDownload ended = mMapDownload;
        takeOfferedMap(mSession->offeredMapUrl());
        if (mMapDownload != ended) {
            mFrontend->mapDownload(mMapDownload);
        }
        if (loaded) {
            pushShownMap();
        }
    });
    connect(session, &THeadlessSession::mainConsoleAreaChanged, this, [this](const QString&, const QSize& grid, const QRect& textRect) {
        mFrontend->mainArea({mMainWindowSize, grid, textRect, colourCode(mSession->mainBorderColor(), false), mSession->adaptiveLayout()});
    });
    connect(session, &THeadlessSession::mainBorderColorChanged, this, &MudletProfile::pushMainArea);
    connect(session, &THeadlessSession::adaptiveLayoutChanged, this, &MudletProfile::pushMainArea);
    connect(session, &THeadlessSession::connecting, this, [this] {
        // Mudlet forgets the refused certificate as it dials
        mFrontend->certificateRefused(std::nullopt);
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("connecting"));
    });
    connect(session, &THeadlessSession::connected, this, [this] {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("connected"));
        // Closing: not to be brought back
        if (mState == State::stopping) {
            return;
        }
        mDisconnectRequested = false;
        mKept.connect = true;
        mEngine.persist();
        if (std::exchange(mRestoring, false)) {
            restoreStatus(QStringLiteral("reconnected"), restoreTarget());
        }
    });
    connect(session, &THeadlessSession::disconnected, this, [this] {
        // Mudlet takes the offer back with its connection
        setSecureOffer(false);
        pushRefusedCertificate();
        // Mudlet saves a recording as its connection ends
        if (mRecording && !mSession->replayRecording()) {
            pushRecordingEnded();
        }
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("disconnected"));
        if (mState == State::stopping) {
            return;
        }
        // Lost or refused rather than ended by the UI: wanted still only if Mudlet tries again, as the profile says
        if (!std::exchange(mDisconnectRequested, false)) {
            mKept.connect = profileReconnects();
        }
        mEngine.persist();
        if (!mRestoring) {
            return;
        }
        // Mudlet tries again on its own for a profile that reconnects automatically; the engine does not add tries
        if (mKept.connect) {
            restoreStatus(QStringLiteral("retrying"), restoreTarget());
        } else {
            mRestoring = false;
            restoreStatus(QStringLiteral("notReconnected"), restoreTarget());
        }
    });
    connect(session, &THeadlessSession::quitRequested, this, [this] {
        // A close of its own finishes it
        if (mState == State::stopping) {
            return;
        }
        mState = State::idle;
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 closed").arg(mProfile));
        onProfileClosed();
        closed();
    });
}

// docs/protocol.md, "The restore status": a phase, then what it is about
void MudletProfile::restoreStatus(const QString& phase, const QString& detail)
{
    mEngine.restoreStatus(phase, detail);
}

void MudletProfile::setRestore(const SessionStore::Session& session)
{
    mKept = session;
    rename(session.profile);
}

void MudletProfile::scheduleRestore(int delayMs)
{
    if (mKept.connect && delayMs > 0) {
        restoreStatus(QStringLiteral("waiting"), QStringLiteral("%1 %2").arg(delayMs / 1000).arg(restoreTarget()));
        mRestoreTimer.start(delayMs);
        return;
    }
    // From the event loop, once the engine listens, as the load takes a while
    mRestoreTimer.start(0);
}

void MudletProfile::beginRestore()
{
    if (mState != State::idle || mKept.profile.isEmpty()) {
        return;
    }
    mRestoring = true;
    mRestoreStart = true;
    if (mKept.connect) {
        restoreStatus(QStringLiteral("reconnecting"), restoreTarget());
    } else {
        restoreStatus(QStringLiteral("restoring"), mProfile);
    }
    start(mKept.host, quint16(mKept.port), mKept.connect, mKept.tls);
}

void MudletProfile::cancelRestore()
{
    const bool waiting = mRestoreTimer.isActive();
    if (!waiting && !mRestoring) {
        return;
    }
    mRestoreTimer.stop();
    mRestoring = false;
    restoreStatus(QStringLiteral("cancelled"));
    // A restore that had begun goes on to the next profile once its start is over
    if (waiting) {
        mEngine.restoreEnded();
    }
}

void MudletProfile::dropRestore()
{
    cancelRestore();
    if (mState == State::idle) {
        mKept = {};
    }
}

QString MudletProfile::restoreTarget() const
{
    return mKept.host.isEmpty() ? mProfile : QStringLiteral("%1:%2").arg(mKept.host).arg(mKept.port);
}

// Mudlet's "Reconnect automatically" for the profile, as its connection dialog stores it
bool MudletProfile::profileReconnects() const
{
    return mEngine.mProfiles.read(mProfile).autoReconnect;
}

void MudletProfile::open(const Open& open)
{
    rename(open.profile);
    mKept = open.kept();
    mDisconnectRequested = false;
    // After any Lua a waiting restore held back, which runs all the same
    mPendingLua.append(open.lua);
    start(open.host, open.port, open.connect, open.tls, open.passwords);
}

void MudletProfile::start(const QString& host, quint16 port, bool connect, std::optional<bool> tls, const ProfilePasswords& passwords)
{
    mStartHost = host;
    mStartPort = port;
    mStartConnect = connect;
    mStartTls = tls;
    // For this run only: THeadlessSession stores none, and a restore has none
    mStartPasswords = passwords;
    mSaved.reset();
    mNewPasswords.reset();
    mAddressProblem.clear();
    mProfileTls = tls;
    if (const QString folder = mEngine.mProfiles.find(mProfile); !tls.has_value() && !folder.isEmpty()) {
        mProfileTls = mEngine.mProfiles.read(folder).tls;
    }
    const MudletEngine::UiSizes& ui = mEngine.mUi;
    mScreenSize = ui.screen;
    mMainWindowSize = ui.mainWindow;
    mMapScale = ui.mapScale;
    mMapDensity = ui.mapDensity;
    mAppMapSize = ui.appMap;
    mAppMapScale = ui.appMapScale;
    // The new profile counts its links from 1 again
    mLinkBase = mEngine.mLinkTop;
    mKept.profile = mProfile;
    mKept.tls = tls;
    mState = State::starting;
    mLoaded = false;
    mLoadBegun = false;
    mCloseWhenStarted = false;
    mForSwitch = false;
    mEngine.profileStarting(this);
    mFrontend = mEngine.mHost->profileOpened(mProfile, this, mRestoring ? IEngineHost::ProfileState::restoring : IEngineHost::ProfileState::starting, !passwords.game.isEmpty());
    mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 starting").arg(mProfile));
    mStartClock.start();
    beginLoad();
}

// THeadlessSession refuses a start while another profile of the pool loads
void MudletProfile::beginLoad()
{
    if (mState != State::starting || mLoadBegun) {
        return;
    }
    if (mEngine.loadingOther(this)) {
        mEngine.later(this, [this] {
            beginLoad();
        });
        return;
    }
    THeadlessSession::Options options;
    options.profile = mProfile;
    options.connect = mStartConnect;
    options.host = mStartHost;
    options.port = mStartPort;
    // Stored with the profile, as the connection dialog's box stores it; unset, the profile's own
    options.tls = mStartTls;
    const ProfilePasswords passwords = std::exchange(mStartPasswords, {});
    options.password = passwords.game;
    options.proxyPassword = passwords.proxy;
    options.configDir = mEngine.mConfigDir;
    // So the profile's scripts read the UI's size from their first line, not 90 by 25 until profileLoaded; the screen
    // size stands in for the main window only while the UI has given it none, as in applyScreenSize()
    if (!mMainWindowSize.isEmpty()) {
        options.mainWindowSize = mMainWindowSize;
    } else {
        options.screenSize = mScreenSize;
    }
    // Kept until the start, and a session that joins the pool takes the pool's instead
    mSession->setApplicationActive(mEngine.mAppActive);
    mSession->setWindowMinimized(mEngine.mMinimized);
    applyMainWindowDevice();
    mLoadBegun = true;
    mSession->startAsync(options);
}

void MudletProfile::onStartFinished(int result, const QString& error)
{
    mFrontend->status(QStringLiteral("startMs"), QString::number(mStartClock.elapsed()));
    mLoadBegun = false;
    const bool restoreStart = std::exchange(mRestoreStart, false);
    const QStringList pending = std::exchange(mPendingLua, {});
    if (THeadlessSession::StartResult(result) != THeadlessSession::StartResult::started) {
        mState = State::idle;
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("start failed (%1): %2").arg(result).arg(error));
        if (!pending.isEmpty()) {
            mFrontend->status(QStringLiteral("luaResult"), QStringLiteral("no profile"));
        }
        if (std::exchange(mRestoring, false)) {
            restoreStatus(QStringLiteral("failed"), error);
        }
        if (mSession->host()) {
            renewSession();
        }
        closed();
        mEngine.loadEnded(restoreStart);
        return;
    }
    mState = State::started;
    // Again, for a setScreenSize or setMainWindowSize that came between profileLoaded and now; the same size changes nothing
    applyScreenSize();
    if (mRestoring && !mKept.connect) {
        mRestoring = false;
        restoreStatus(QStringLiteral("restored"), mProfile);
    }
    // Before a connect that waited for the start
    applyPasswords();
    for (const QString& code : pending) {
        runNow(code);
    }
    // After it: no connect is taken while a save waits, so one that waited was asked for with the details loaded
    applySaved();
    if (mCloseWhenStarted) {
        close(mForSwitch);
    }
    mEngine.loadEnded(restoreStart);
}

void MudletProfile::runNow(const QString& code)
{
    const bool ok = mSession->runLua(code);
    mFrontend->status(QStringLiteral("luaResult"), ok ? QStringLiteral("ran") : QStringLiteral("refused: %1").arg(mSession->errorString()));
}

void MudletProfile::whenPoolFree(const std::function<void()>& action)
{
    if (mEngine.loadingOther(this)) {
        mEngine.later(this, action);
    } else {
        action();
    }
}

void MudletProfile::connectTo(const QString& host, quint16 port, std::optional<bool> tls)
{
    if (mForSwitch && mEngine.mPendingOpen) {
        mEngine.mPendingOpen->host = host;
        mEngine.mPendingOpen->port = port;
        mEngine.mPendingOpen->tls = tls;
        mEngine.mPendingOpen->connect = true;
        mEngine.persist();
        return;
    }
    if (mState == State::stopping) {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("error: the profile is closing"));
        return;
    }
    // A connect refused changes nothing, a restore included
    if (const QString problem = connectProblem(tls); !problem.isEmpty()) {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("error: %1").arg(problem));
        return;
    }
    const QString code = QStringLiteral("connectToServer(%1, %2)").arg(luaLongString(host)).arg(port);
    cancelRestore();
    mDisconnectRequested = false;
    mKept.host = host;
    mKept.port = port;
    mKept.connect = true;
    mEngine.persist();
    switch (mState) {
    case State::idle:
        start(host, port, true, tls);
        return;
    case State::starting:
        mPendingLua.append(code);
        return;
    case State::started:
        whenPoolFree([this, code] {
            if (!mSession->runLua(code)) {
                mFrontend->status(QStringLiteral("connection"), QStringLiteral("error: %1").arg(mSession->errorString()));
            }
        });
        return;
    case State::stopping:
        return;
    }
}

// connectToServer() dials with the TLS choice Mudlet holds for the running profile, which only its start and a save
// of the profile set: a connect made while that is not the one asked for, or not the one stored, could send the
// login over a connection the player did not choose.
QString MudletProfile::connectProblem(std::optional<bool> tls) const
{
    if (mState == State::idle) {
        return {};
    }
    if (mSaved) {
        return QStringLiteral("the profile has yet to take the details saved for it; connect again in a moment");
    }
    if (!mAddressProblem.isEmpty()) {
        return QStringLiteral("Mudlet refused the address saved for the profile: %1").arg(mAddressProblem);
    }
    if (tls.has_value() && tls != mProfileTls) {
        QString connects = QStringLiteral("with its stored TLS choice");
        if (mProfileTls.has_value()) {
            connects = *mProfileTls ? QStringLiteral("with TLS") : QStringLiteral("without TLS");
        }
        return QStringLiteral("the profile connects %1; save it with the other choice to change that").arg(connects);
    }
    return {};
}

bool MudletProfile::takesInput()
{
    if (mState != State::started) {
        mFrontend->status(QStringLiteral("sendError"), QStringLiteral("no profile running"));
        return false;
    }
    return true;
}

void MudletProfile::send(const QString& line)
{
    if (takesInput()) {
        whenPoolFree([this, line] {
            if (!mSession->submit(line)) {
                mFrontend->status(QStringLiteral("sendError"), mSession->errorString());
            }
        });
    }
}

// Empty with no profile open
QStringList MudletProfile::history() const
{
    return mSession->commandHistory();
}

// The errors never quote the line, which THeadlessSession clears from its own copy once sent
void MudletProfile::sendHidden(const QString& line)
{
    if (takesInput()) {
        whenPoolFree([this, line] {
            if (!mSession->submitHiddenInput(line)) {
                mFrontend->status(QStringLiteral("sendError"), mSession->errorString());
            }
        });
    }
}

void MudletProfile::dismissHidden()
{
    if (takesInput()) {
        whenPoolFree([this] {
            if (!mSession->dismissHiddenInput()) {
                mFrontend->status(QStringLiteral("sendError"), mSession->errorString());
            }
        });
    }
}

// As Tab, or the up arrow with "Highlight history" on, on the GUI's command line holding text: calls that hand back
// the line the last one gave go on to the next word or older line
QString MudletProfile::complete(const QString& text, bool fromHistory)
{
    if (mState != State::started || !mSession->setCommandLineText(text)) {
        return {};
    }
    return (fromHistory ? mSession->completeCommandLineFromHistory() : mSession->completeCommandLine()).value_or(QString());
}

void MudletProfile::disconnectFromGame()
{
    // The user's disconnect: a restart of the engine brings the profile back, but does not connect it
    const bool waiting = mRestoreTimer.isActive();
    cancelRestore();
    if (mForSwitch && mEngine.mPendingOpen) {
        mEngine.mPendingOpen->connect = false;
        mEngine.persist();
        return;
    }
    mDisconnectRequested = true;
    mKept.connect = false;
    mEngine.persist();
    if (waiting && !mPendingLua.isEmpty()) {
        // Lua held back for the restore runs all the same
        start({}, 0, false, mKept.tls);
    }
    if (mState == State::started) {
        whenPoolFree([this] {
            if (!mSession->runLua(QStringLiteral("disconnect()"))) {
                mFrontend->status(QStringLiteral("connection"), QStringLiteral("error: %1").arg(mSession->errorString()));
            }
        });
    }
}

void MudletProfile::runLua(const QString& code)
{
    if (mForSwitch && mEngine.mPendingOpen) {
        mEngine.mPendingOpen->lua.append(code);
        return;
    }
    switch (mState) {
    case State::idle:
        mPendingLua.append(code);
        // A restore that waits to connect starts the profile, and runs it then
        if (!mRestoreTimer.isActive()) {
            start({}, 0, false, std::nullopt);
        }
        return;
    case State::starting:
        mPendingLua.append(code);
        return;
    case State::started:
        whenPoolFree([this, code] {
            runNow(code);
        });
        return;
    case State::stopping:
        mFrontend->status(QStringLiteral("luaResult"), QStringLiteral("refused: the profile is closing"));
        return;
    }
}

void MudletProfile::connectProfile()
{
    if (mKept.connect || !running()) {
        return;
    }
    if (const QString problem = connectProblem(std::nullopt); !problem.isEmpty()) {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("error: %1").arg(problem));
        return;
    }
    const Profile profile = mEngine.mProfiles.read(mProfile);
    const QString code = QStringLiteral("connectToServer(%1, %2)").arg(luaLongString(profile.host)).arg(profile.port);
    mDisconnectRequested = false;
    mKept.host.clear();
    mKept.port = 0;
    mKept.connect = true;
    mEngine.persist();
    if (mState == State::starting) {
        mPendingLua.append(code);
        return;
    }
    whenPoolFree([this, code] {
        if (!mSession->runLua(code)) {
            mFrontend->status(QStringLiteral("connection"), QStringLiteral("error: %1").arg(mSession->errorString()));
        }
    });
}

void MudletProfile::openAgain(const ProfilePasswords& passwords, bool connect)
{
    if (mState == State::starting && !mLoadBegun) {
        mStartPasswords = passwords;
    } else {
        mNewPasswords = passwords;
        applyPasswords();
    }
    mEngine.mHost->profilePasswordGiven(mProfile, !passwords.game.isEmpty());
    if (connect) {
        connectProfile();
    }
}

void MudletProfile::profileSaved(const Profile& stored, bool addressChanged)
{
    if (addressChanged) {
        // What it last connected to was the address this one replaces
        mKept.host.clear();
        mKept.port = 0;
        mKept.tls.reset();
        mEngine.persist();
    }
    // A load still to begin reads the stored details itself, in place of the address it was to start with
    if (mState == State::starting && !mLoadBegun) {
        if (addressChanged) {
            mStartHost.clear();
            mStartPort = 0;
            mStartTls.reset();
            mProfileTls = stored.tls;
        }
        return;
    }
    mSaved = stored;
    applySaved();
}

// THeadlessSession refuses a change to the profile while it, or another of the pool, loads. Starting or closing:
// onStartFinished() and a close that is refused hand it over; another loading: again does, once that load is over.
bool MudletProfile::takesChanges(const std::function<void()>& again)
{
    if (mState != State::started) {
        return false;
    }
    if (mEngine.loadingOther(this)) {
        mEngine.later(this, again);
        return false;
    }
    return true;
}

// Mudlet reads a profile's address, TLS choice, automatic reconnect and login as it loads, so a running one is handed
// what was saved: its next connection is made and logged in with it, and the one that is open stays as it is. The
// login is never logged.
void MudletProfile::applySaved()
{
    if (!mSaved || !takesChanges([this] {
            applySaved();
        })) {
        return;
    }
    const Profile saved = *std::exchange(mSaved, std::nullopt);
    if (mSession->setLogin(saved.login)) {
        qInfo("MudletEngine: %s is given its login for its next connection", qPrintable(mProfile));
    } else {
        qWarning("MudletEngine: %s did not take its new login: %s", qPrintable(mProfile), qPrintable(mSession->errorString()));
    }
    if (mSession->setAddress(saved.host, saved.port, saved.tls)) {
        mProfileTls = saved.tls;
        mAddressProblem.clear();
    } else {
        mAddressProblem = mSession->errorString();
        qWarning("MudletEngine: %s did not take its new address: %s", qPrintable(mProfile), qPrintable(mAddressProblem));
    }
    if (!mSession->setAutoReconnect(saved.autoReconnect)) {
        qWarning("MudletEngine: %s did not take its automatic reconnect: %s", qPrintable(mProfile), qPrintable(mSession->errorString()));
    }
}

// As applySaved(), for the passwords openProfile gave the running profile. Neither is ever logged.
void MudletProfile::applyPasswords()
{
    if (!mNewPasswords || !takesChanges([this] {
            applyPasswords();
        })) {
        return;
    }
    const ProfilePasswords passwords = *std::exchange(mNewPasswords, std::nullopt);
    if (mSession->setPassword(passwords.game)) {
        qInfo("MudletEngine: %s is given a password, or none, for its next connection", qPrintable(mProfile));
    } else {
        qWarning("MudletEngine: %s did not take the password given it: %s", qPrintable(mProfile), qPrintable(mSession->errorString()));
    }
    if (!mSession->setProxyPassword(passwords.proxy)) {
        qWarning("MudletEngine: %s did not take the proxy password given it: %s", qPrintable(mProfile), qPrintable(mSession->errorString()));
    }
}

void MudletProfile::close(bool forSwitch)
{
    switch (mState) {
    case State::idle:
    case State::stopping:
        return;
    case State::starting:
        if (mLoadBegun) {
            // onStartFinished() closes it once that start is over
            mCloseWhenStarted = true;
            mForSwitch = forSwitch;
            return;
        }
        // Its load waited for another's, and is not to begin
        mState = State::idle;
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 closed").arg(mProfile));
        closed();
        return;
    case State::started:
        break;
    }
    mState = State::stopping;
    mCloseWhenStarted = false;
    mForSwitch = forSwitch;
    // Not to be brought back by a restart of the engine from now
    mEngine.persist();
    mEngine.mHost->profileState(mProfile, IEngineHost::ProfileState::closing);
    mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 closing").arg(mProfile));
    requestShutdown();
}

// THeadlessSession refuses a shutdown while a profile of the pool loads, or while a save runs; the engine calls
// continueClose() once that save is over
void MudletProfile::requestShutdown()
{
    if (mState != State::stopping || mShutdownAsked) {
        return;
    }
    if (mEngine.loadingOther(this)) {
        mEngine.later(this, [this] {
            requestShutdown();
        });
        return;
    }
    if (mEngine.mSaver) {
        return;
    }
    mShutdownAsked = true;
    mSession->shutdownAsync(true);
}

void MudletProfile::continueClose()
{
    requestShutdown();
}

void MudletProfile::onShutdownFinished(bool saved, const QString& error)
{
    // Refused, as while a script runs: the profile is still running
    if (mSession->host()) {
        mState = State::started;
        mForSwitch = false;
        mEngine.mHost->profileState(mProfile, IEngineHost::ProfileState::loaded);
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 not closed: %2").arg(mProfile, error));
        applySaved();
        applyPasswords();
        mEngine.closeRefused();
        return;
    }
    mState = State::idle;
    onProfileClosed();
    if (saved) {
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 closed").arg(mProfile));
    } else {
        qWarning("MudletEngine: %s closed without its save: %s", qPrintable(mProfile), qPrintable(error));
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 closed without its save: %2").arg(mProfile, error));
    }
    closed();
}

// What it showed goes to the control socket again, as the profile socket goes
void MudletProfile::closed()
{
    mKept = {};
    mRestoring = false;
    mForSwitch = false;
    mCloseWhenStarted = false;
    mFrontend = mEngine.mHost->controlFrontend();
    mEngine.profileClosed(this);
}

void MudletProfile::setWindowMinimized(bool minimized)
{
    mSession->setWindowMinimized(minimized);
}

void MudletProfile::applyMainWindowDevice()
{
    if (!mMainWindowDevice || mState == State::stopping) {
        return;
    }
    if (!mSession->setMainWindowDevice(sessionDevice(*mMainWindowDevice))) {
        qWarning("MudletEngine: the main window's device, scale %g, was refused: %s", mMainWindowDevice->pixelScale, qPrintable(mSession->errorString()));
    }
}

bool MudletProfile::showInFront()
{
    return mState == State::started && mSession->runLua(QStringLiteral("setActiveProfile(%1)").arg(luaLongString(mProfile)));
}

// The profile gets its sysExitEvent and its save, waited for a few seconds at most, as the process is ending. One
// already closing is only waited for, as a second shutdown would be refused at once.
void MudletProfile::closeAtExit()
{
    if (mState != State::started && mState != State::stopping) {
        return;
    }
    QEventLoop loop;
    QObject::connect(mSession.get(), &THeadlessSession::shutdownFinished, &loop, [this, &loop](bool succeeded, const QString& error) {
        if (!succeeded) {
            qWarning("MudletEngine: %s was not saved at exit: %s", qPrintable(mProfile), qPrintable(error));
        }
        loop.quit();
    });
    QTimer::singleShot(5000, &loop, [this, &loop] {
        qWarning("MudletEngine: the save of %s at exit did not finish in 5 s", qPrintable(mProfile));
        loop.quit();
    });
    if (!mShutdownAsked) {
        mShutdownAsked = true;
        mSession->shutdownAsync(true);
    }
    loop.exec();
}

void MudletProfile::onProfileClosed()
{
    mLoaded = false;
    mFrontend->windows({}, {}, {});
    mFrontend->buttonBars({});
    mFrontend->commands({});
    mFrontend->keyBindings({});
    pushMap(std::nullopt);
    pushAppMap();
    setMapDownloadState(MapDownload::State::none);
    takeOfferedMap({});
    mFrontend->mapDownload(mMapDownload);
    mSounds = {};
    mFrontend->sounds(mSounds);
    setSecureOffer(false);
    mFrontend->certificateRefused(std::nullopt);
    mFrontend->scriptsStopped(false);
    reportTimeStamps(false);
    mFrontend->status(QStringLiteral("lag"), QString());
    mRecording = false;
    mReplaysBefore.clear();
    mRecordedFile.clear();
    mFrontend->replayRecording(false, QString());
    mReplay = {};
    mFrontend->replay(mReplay);
}

void MudletProfile::pushWindows(bool withText)
{
    QList<ScriptWindowInfo> windows;
    QList<LabelInfo> labels;
    QList<TextBoxInfo> textBoxes;
    const QList<ScriptWindow> sessionWindows = mSession->scriptWindows();
    for (const ScriptWindow& window : sessionWindows) {
        windows.append(windowInfo(window));
        if (window.kind == ScriptWindow::Kind::label) {
            if (const std::optional<THeadlessSession::LabelContent> content = mSession->labelContent(window.name)) {
                labels.append(labelInfo(window.name, *content));
            }
        } else if (window.kind == ScriptWindow::Kind::textBox) {
            if (const std::optional<THeadlessSession::TextBoxContent> content = mSession->textBoxContent(window.name)) {
                textBoxes.append(textBoxInfo(window.name, *content));
            }
        }
    }
    mFrontend->windows(windows, labels, textBoxes);
    // mapRepaintRequested does not come for a mapper that only changed size, or hid
    if (const std::optional<QSize> mapperSize = shownMapperSize(sessionWindows); mapperSize != mMapSize) {
        pushMap(mapperSize);
    }
    if (!withText) {
        return;
    }
    for (const ScriptWindowInfo& window : std::as_const(windows)) {
        if (window.console) {
            pushWindowText(window.name);
        }
    }
}

void MudletProfile::pushWindowText(const QString& window)
{
    if (const std::optional<WindowText> text = windowText(window, -WindowText::recentRows, WindowText::recentRows)) {
        mFrontend->windowText(window, *text);
    }
}

// Also when only the size the UI gave changed, which mainConsoleAreaChanged does not tell
void MudletProfile::pushMainArea()
{
    mFrontend->mainArea({mMainWindowSize, mSession->mainConsoleGrid(), mSession->mainConsoleTextRect(), colourCode(mSession->mainBorderColor(), false), mSession->adaptiveLayout()});
}

void MudletProfile::pushButtonBars()
{
    QList<ButtonBar> bars;
    for (const THeadlessSession::ButtonBar& bar : mSession->buttonBars()) {
        bars.append(buttonBar(bar));
    }
    mFrontend->buttonBars(bars);
}

void MudletProfile::pushCommands()
{
    QList<AddonCommandInfo> commands;
    for (const THeadlessSession::AddonCommand& command : mSession->addonCommands()) {
        commands.append(addonCommandInfo(command));
    }
    mFrontend->commands(commands);
}

void MudletProfile::pushKeyBindings()
{
    QList<KeyBinding> bindings;
    for (const QKeyCombination combination : mSession->keyBindings()) {
        bindings.append({combination.key(), static_cast<int>(combination.keyboardModifiers().toInt())});
    }
    mFrontend->keyBindings(bindings);
}

void MudletProfile::pushMap(const std::optional<QSize>& mapperSize)
{
    mMapSize = mapperSize;
    const std::optional<MapFrame> frame = mapperSize ? drawMap(*mapperSize) : std::nullopt;
    if (!frame) {
        mMapPicture.clear();
    }
    mFrontend->map(frame);
}

std::optional<MapFrame> MudletProfile::drawMap(const QSize& size)
{
    // A map widget opened before the UI gave the main window a size has none
    if (size.isEmpty()) {
        return std::nullopt;
    }
    if (size.width() > PngImage::maxSide || size.height() > PngImage::maxSide) {
        qWarning("MudletEngine: the mapper, %dx%d, is larger than %d pixels a side, so the map is not drawn", size.width(), size.height(), PngImage::maxSide);
        return std::nullopt;
    }
    // Drawn in the window's pixels onto an image of the device's, so the lines and names are as sharp as the screen,
    // and laid out in the UI's own map's units where it shows the window larger than those, so the rooms look as there;
    // by the scale the image is drawn at, so the layout, which a double-click paints, is never larger than the image
    const double scale = std::max(1.0, std::min({mMapScale, double(PngImage::maxSide) / size.width(), double(PngImage::maxSide) / size.height()}));
    QImage image(size * scale, QImage::Format_ARGB32_Premultiplied);
    if (image.isNull()) {
        qWarning("MudletEngine: no memory for the map at %dx%d by %g", size.width(), size.height(), scale);
        return std::nullopt;
    }
    image.setDevicePixelRatio(scale);
    image.fill(Qt::transparent);
    QPainter painter(&image);
    const bool drawn = mSession->renderMap(painter, size, std::max(1.0, scale / mMapDensity));
    painter.end();
    if (!drawn) {
        qWarning("MudletEngine: the map was not drawn: %s", qPrintable(mSession->errorString()));
        return std::nullopt;
    }
    const QByteArray png = mMapPicture.png(std::move(image));
    if (png.isEmpty()) {
        qWarning("MudletEngine: the map could not be made a PNG");
        return std::nullopt;
    }
    return MapFrame{png, size, mSession->mapViewOnly(), mapView(MapTarget::mapper), mapInfo(MapTarget::mapper)};
}

// Nothing while none is asked for: setAppMapSize() said so once
void MudletProfile::pushAppMap()
{
    if (mAppMapSize.isEmpty()) {
        return;
    }
    const std::optional<MapFrame> frame = loaded() ? drawAppMap() : std::nullopt;
    if (!frame) {
        mAppMapPicture.clear();
    }
    mFrontend->appMap(frame);
}

std::optional<MapFrame> MudletProfile::drawAppMap()
{
    // Drawn in the map's pixels onto an image of the device's, so the lines and names are as sharp as the screen
    const QSize pixels = mAppMapSize * mAppMapScale;
    QImage image(pixels, QImage::Format_ARGB32_Premultiplied);
    if (image.isNull()) {
        warnAppMapUndrawn(QStringLiteral("no memory for an image of %1x%2 pixels").arg(pixels.width()).arg(pixels.height()));
        return std::nullopt;
    }
    image.setDevicePixelRatio(mAppMapScale);
    image.fill(Qt::transparent);
    QPainter painter(&image);
    const bool drawn = mSession->renderAppMap(painter, mAppMapSize);
    painter.end();
    if (!drawn) {
        warnAppMapUndrawn(mSession->errorString());
        return std::nullopt;
    }
    const QByteArray png = mAppMapPicture.png(std::move(image));
    if (png.isEmpty()) {
        warnAppMapUndrawn(QStringLiteral("it could not be made a PNG"));
        return std::nullopt;
    }
    mAppMapUndrawn.clear();
    return MapFrame{png, mAppMapSize, mSession->mapViewOnly(), mapView(MapTarget::app), mapInfo(MapTarget::app)};
}

std::optional<MapViewInfo> MudletProfile::mapView(MapTarget map) const
{
    const std::optional<THeadlessSession::MapView> view = mSession->mapView(sessionMap(map));
    if (!view) {
        return std::nullopt;
    }
    MapViewInfo info{view->areaId,
                     view->areaName,
                     view->level,
                     view->zoom,
                     view->minZoom,
                     view->maxZoom,
                     view->following,
                     view->playerRoomId,
                     view->roomSize,
                     map == MapTarget::app ? mAppMapMoved : mMapMoved,
                     {}};
    info.rooms.reserve(view->rooms.size());
    for (const THeadlessSession::MapRoom& room : view->rooms) {
        info.rooms.append({room.id, room.position, room.name});
    }
    info.levels = view->levels;
    return info;
}

MapInfo MudletProfile::mapInfo(MapTarget map) const
{
    const THeadlessSession::MapInfo box = mSession->mapInfo(sessionMap(map));
    MapInfo info{{}, box.background.name(), box.background.alpha()};
    info.lines.reserve(box.lines.size());
    for (const THeadlessSession::MapInfoLine& line : box.lines) {
        info.lines.append(MapInfoLine{line.text, line.bold, line.italic, line.color.name()});
    }
    return info;
}

// Once for each reason in a row, as each repaint meets it again: a map with no rooms is warned of once
void MudletProfile::warnAppMapUndrawn(const QString& why)
{
    if (why == mAppMapUndrawn) {
        return;
    }
    mAppMapUndrawn = why;
    qWarning("MudletEngine: the app's map, %dx%d by %g, was not drawn: %s", mAppMapSize.width(), mAppMapSize.height(), mAppMapScale, qPrintable(why));
}

QString MudletProfile::setMainWindowSize(int width, int height)
{
    const QSize before = std::exchange(mMainWindowSize, QSize(width, height));
    mEngine.mUi.mainWindow = mMainWindowSize;
    // Otherwise taken as the profile loads
    if (!loaded()) {
        return {};
    }
    if (!mSession->setMainWindowSize(mMainWindowSize)) {
        mMainWindowSize = before;
        mEngine.mUi.mainWindow = before;
        return mSession->errorString();
    }
    // So the game is told the UI's console, not this size's grid; before the start finishes, onStartFinished() does
    if (mState == State::started) {
        applyScreenSize();
    }
    pushMainArea();
    return {};
}

QString MudletProfile::setMainWindowDevice(const MainWindowDevice& device)
{
    const std::optional<MainWindowDevice> before = std::exchange(mMainWindowDevice, device);
    mEngine.mUi.mainWindowDevice = device;
    // Otherwise taken as the profile begins loading, or once the pool's loading profile has loaded
    if (!loaded() || mEngine.anyLoading()) {
        return {};
    }
    if ((false)) {
        mMainWindowDevice = before;
        mEngine.mUi.mainWindowDevice = before;
        return mSession->errorString();
    }
    return {};
}

std::optional<WindowText> MudletProfile::windowText(const QString& window, int first, int count) const
{
    const std::optional<THeadlessSession::WindowText> lines = mSession->windowLines(window, first, count);
    if (!lines) {
        return std::nullopt;
    }
    WindowText text{lines->lineCount, lines->first, {}};
    // A window's link ids are its own, not offset as the main console's are
    int linkTop = 0;
    for (const THeadlessSession::WindowLine& line : lines->lines) {
        text.rows.append({line.text, colorRuns(line.runs, 0, linkTop, true), line.isPrompt, line.wrapped});
    }
    return text;
}

QString MudletProfile::placeUserWindow(const QString& name, const QRect& geometry, bool floating)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->setUserWindowGeometry(name, geometry, floating) ? QString() : mSession->errorString();
}

QString MudletProfile::closeUserWindow(const QString& name)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->closeUserWindow(name) ? QString() : mSession->errorString();
}

QString MudletProfile::sendWindowCommandLine(const QString& name, const QString& text)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->submitWindowCommandLine(name, text) ? QString() : mSession->errorString();
}

QString MudletProfile::setTextBoxText(const QString& name, QString& text)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    if (!mSession->setTextBoxText(name, text)) {
        return mSession->errorString();
    }
    // Mudlet keeps it as plain text, as the desktop's box does: a non-breaking space as a space, a line or paragraph
    // separator as a newline
    if (const std::optional<THeadlessSession::TextBoxContent> content = mSession->textBoxContent(name)) {
        text = content->text;
    }
    return {};
}

QString MudletProfile::selectFrameTab(const QString& name)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->selectFrameTab(name) ? QString() : mSession->errorString();
}

QString MudletProfile::followLabelLink(const QString& label, const QString& href)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    // Mudlet would open it with QDesktopServices, from this service, which has no activity to start a browser from;
    // the UI opens it itself. The scheme is read as TLabelModel::followLink() reads it: before the first colon of the
    // trimmed href, in any case, and only where something comes before that colon.
    const QString trimmed = href.trimmed();
    if (const int colon = trimmed.indexOf(QLatin1Char(':')); colon > 0) {
        if (const QString scheme = trimmed.left(colon).toLower(); scheme == QLatin1String("http") || scheme == QLatin1String("https")) {
            return QStringLiteral("the UI opens web links");
        }
    }
    return mSession->followLabelLink(label, href) == THeadlessSession::LinkResult::ran ? QString() : mSession->errorString();
}

QString MudletProfile::pointer(const QString& window, const PointerEvent& event)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    static constexpr std::pair<PointerEvent::Type, THeadlessSession::WindowPointerEvent::Type> types[] = {
            {PointerEvent::Type::press, THeadlessSession::WindowPointerEvent::Type::press},
            {PointerEvent::Type::release, THeadlessSession::WindowPointerEvent::Type::release},
            {PointerEvent::Type::doubleClick, THeadlessSession::WindowPointerEvent::Type::doubleClick},
            {PointerEvent::Type::move, THeadlessSession::WindowPointerEvent::Type::move},
            {PointerEvent::Type::enter, THeadlessSession::WindowPointerEvent::Type::enter},
            {PointerEvent::Type::leave, THeadlessSession::WindowPointerEvent::Type::leave},
            {PointerEvent::Type::wheel, THeadlessSession::WindowPointerEvent::Type::wheel},
    };
    THeadlessSession::WindowPointerEvent sessionEvent;
    for (const auto& [ours, theirs] : types) {
        if (ours == event.type) {
            sessionEvent.type = theirs;
            break;
        }
    }
    sessionEvent.position = event.position;
    sessionEvent.button = Qt::MouseButton(event.button);
    sessionEvent.buttons = Qt::MouseButtons::fromInt(event.buttons);
    sessionEvent.modifiers = Qt::KeyboardModifiers::fromInt(event.modifiers);
    sessionEvent.angleDelta = QPoint(0, event.wheelY);
    return mSession->pointer(window, sessionEvent) ? QString() : mSession->errorString();
}

std::variant<PngImage, QString> MudletProfile::image(const QString& path, const QSize& size, std::optional<int> frame) const
{
    QImage image;
    QImageReader reader(path);
    if (reader.canRead()) {
        const QSize own = reader.size();
        if (!size.isEmpty()) {
            reader.setScaledSize(size);
        } else if (own.width() > PngImage::maxSide || own.height() > PngImage::maxSide) {
            return QStringLiteral("%1 is larger than %2 pixels a side: ask for a size").arg(path).arg(PngImage::maxSide);
        }
        // Read up to it, as QMovie reaches a frame, since few formats jump to one
        for (int read = 0; read <= frame.value_or(0); ++read) {
            if (!reader.read(&image)) {
                return read > 0 ? QStringLiteral("no frame %1 in %2").arg(*frame).arg(path) : QStringLiteral("%1: %2").arg(path, reader.errorString());
            }
        }
    } else if (!size.isEmpty() && !frame) {
        // An icon file, or a name in the icon theme
        const QIcon icon = QFileInfo::exists(path) ? QIcon(path) : QIcon::fromTheme(path);
        image = icon.pixmap(size).toImage();
    }
    if (image.isNull()) {
        return QStringLiteral("%1: %2").arg(path, reader.errorString());
    }
    QByteArray png;
    QBuffer buffer(&png);
    buffer.open(QIODevice::WriteOnly);
    if (!image.save(&buffer, "PNG")) {
        return QStringLiteral("%1 could not be made a PNG").arg(path);
    }
    return PngImage{png, image.size()};
}

QString MudletProfile::pressButton(int id)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->pressButton(id) ? QString() : mSession->errorString();
}

std::variant<IEngine::KeyPress, QString> MudletProfile::pressKey(int key, int modifiers, const std::optional<QString>& text)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    const auto qtKey = static_cast<Qt::Key>(key);
    const Qt::KeyboardModifiers qtModifiers = Qt::KeyboardModifiers::fromInt(modifiers);
    switch (text ? mSession->pressKey(qtKey, qtModifiers, *text) : mSession->pressKey(qtKey, qtModifiers)) {
    case THeadlessSession::KeyResult::bindingRan:
        return KeyPress::bindingRan;
    case THeadlessSession::KeyResult::commandRan:
        return KeyPress::commandRan;
    case THeadlessSession::KeyResult::noBinding:
        return KeyPress::noBinding;
    case THeadlessSession::KeyResult::keyKept:
        return KeyPress::keyKept;
    case THeadlessSession::KeyResult::refused:
        break;
    }
    return mSession->errorString();
}

QString MudletProfile::activateCommand(int id)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->activateAddonCommand(id) ? QString() : mSession->errorString();
}

QString MudletProfile::mapPointer(const PointerEvent& event)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    const std::optional<THeadlessSession::MapPointerEvent> sessionEvent = mapPointerEvent(event);
    if (!sessionEvent) {
        return QStringLiteral("the map takes a press, a double-click or a wheel turn");
    }
    return mSession->mapPointer(*sessionEvent) ? QString() : mSession->errorString();
}

QString MudletProfile::setMapViewOnly(bool viewOnly)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->setMapViewOnly(viewOnly) ? QString() : mSession->errorString();
}

QString MudletProfile::setMapScale(double scale, double density)
{
    mEngine.mUi.mapScale = scale;
    mEngine.mUi.mapDensity = density;
    if (scale == mMapScale && density == mMapDensity) {
        return {};
    }
    mMapScale = scale;
    mMapDensity = density;
    if (mMapSize && loaded()) {
        pushMap(mMapSize);
    }
    return {};
}

QString MudletProfile::setAppMapSize(const QSize& size, double scale)
{
    if (size.width() * scale > PngImage::maxSide || size.height() * scale > PngImage::maxSide) {
        return QStringLiteral("the map at %1x%2 by %3 is larger than %4 pixels a side").arg(size.width()).arg(size.height()).arg(scale).arg(PngImage::maxSide);
    }
    mAppMapSize = size.isEmpty() ? QSize(0, 0) : size;
    mAppMapScale = scale;
    mEngine.mUi.appMap = mAppMapSize;
    mEngine.mUi.appMapScale = scale;
    if (!mAppMapSize.isEmpty()) {
        pushAppMap();
        return {};
    }
    if (loaded()) {
        mSession->closeAppMap();
    }
    mAppMapPicture.clear();
    mFrontend->appMap(std::nullopt);
    return {};
}

QString MudletProfile::appMapPointer(const PointerEvent& event)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    const std::optional<THeadlessSession::MapPointerEvent> sessionEvent = mapPointerEvent(event);
    if (!sessionEvent) {
        return QStringLiteral("the map takes a press, a double-click or a wheel turn");
    }
    return mSession->appMapPointer(*sessionEvent) ? QString() : mSession->errorString();
}

std::variant<RoomList, QString> MudletProfile::findRooms(const QString& text) const
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    bool isId = false;
    const int id = text.trimmed().toInt(&isId);
    QList<RoomMatch> found;
    QHash<int, bool> inPlayerArea;
    for (const THeadlessSession::RoomMatch& room : mSession->findRooms(text)) {
        RoomMatch::Match match = RoomMatch::Match::contains;
        if (room.name.compare(text, Qt::CaseInsensitive) == 0) {
            match = RoomMatch::Match::exact;
        } else if (room.name.startsWith(text, Qt::CaseInsensitive)) {
            match = RoomMatch::Match::prefix;
        } else if (isId && room.id == id && !room.name.contains(text, Qt::CaseInsensitive)) {
            match = RoomMatch::Match::id;
        }
        found.append({room.id, room.name, room.areaId, room.areaName, match, room.steps});
        inPlayerArea.insert(room.id, room.inPlayerArea);
    }
    // The room whose id the text is ranks as an exact name; one with no way there after those with one
    const auto rank = [](const RoomMatch& room) {
        return room.match == RoomMatch::Match::id ? 0 : static_cast<int>(room.match);
    };
    std::sort(found.begin(), found.end(), [&](const RoomMatch& a, const RoomMatch& b) {
        if (rank(a) != rank(b)) {
            return rank(a) < rank(b);
        }
        if (inPlayerArea.value(a.id) != inPlayerArea.value(b.id)) {
            return inPlayerArea.value(a.id);
        }
        if (a.steps != b.steps) {
            return b.steps < 0 || (a.steps >= 0 && a.steps < b.steps);
        }
        const int byName = a.name.compare(b.name, Qt::CaseInsensitive);
        return byName != 0 ? byName < 0 : a.id < b.id;
    });
    RoomList list;
    list.more = found.size() > RoomList::maxRooms;
    list.rooms = found.first(std::min<qsizetype>(found.size(), RoomList::maxRooms));
    return list;
}

QString MudletProfile::showRoom(MapTarget map, int room)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    const bool shown = map == MapTarget::app ? mSession->showRoomOnAppMap(room) : mSession->centreMapOnRoom(room);
    return shown ? QString() : mSession->errorString();
}

QString MudletProfile::moveMap(MapTarget map, const QPointF& pan, double zoom, const QPointF& about, qint64 seq)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    if (!mSession->moveMapView(sessionMap(map), pan, zoom, about)) {
        return mSession->errorString();
    }
    (map == MapTarget::app ? mAppMapMoved : mMapMoved) = seq;
    return {};
}

QString MudletProfile::followPlayer(MapTarget map)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->followPlayerOnMap(sessionMap(map)) ? QString() : mSession->errorString();
}

QString MudletProfile::showMapLevel(MapTarget map, int level)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->showMapLevel(sessionMap(map), level) ? QString() : mSession->errorString();
}

QString MudletProfile::showMapArea(MapTarget map, int area)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->showMapArea(sessionMap(map), area) ? QString() : mSession->errorString();
}

std::variant<RoomDetails, QString> MudletProfile::roomInfo(int room) const
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    const std::optional<THeadlessSession::RoomInfo> info = mSession->roomInfo(room);
    if (!info) {
        return QStringLiteral("there is no room %1 in the map").arg(room);
    }
    RoomDetails details{info->id, info->name, info->areaId, info->areaName, info->x, info->y, info->z, info->steps, {}, info->symbol, info->env, info->color.name(), info->weight, info->locked};
    for (const THeadlessSession::RoomExit& exit : info->exits) {
        details.exits.append({exit.direction, exit.to, exit.toName, exit.locked});
    }
    return details;
}

QString MudletProfile::walkToRoom(int room)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->walkToRoom(room) ? QString() : mSession->errorString();
}

std::variant<QList<MapAreaInfo>, QString> MudletProfile::mapAreas() const
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    QList<MapAreaInfo> areas;
    for (const THeadlessSession::MapArea& area : mSession->mapAreas()) {
        areas.append({area.id, area.name});
    }
    return areas;
}

std::variant<RoomDetails, QString> MudletProfile::editRoom(int room, const RoomEdit& edit)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    const THeadlessSession::RoomEdit sessionEdit{edit.name, edit.symbol, edit.env, edit.weight, edit.locked, edit.position};
    return mSession->editRoom(room, sessionEdit) ? roomInfo(room) : mSession->errorString();
}

std::variant<RoomDetails, QString> MudletProfile::setRoomExit(int room, const QString& direction, int to, bool bothWays)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->setRoomExit(room, direction, to, bothWays) ? roomInfo(room) : mSession->errorString();
}

std::variant<RoomDetails, QString> MudletProfile::lockRoomExit(int room, const QString& direction, bool locked)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->lockRoomExit(room, direction, locked) ? roomInfo(room) : mSession->errorString();
}

std::variant<RoomDetails, QString> MudletProfile::addRoom(int beside, const QString& direction)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    const int added = mSession->addRoomBeside(beside, direction);
    return added ? roomInfo(added) : mSession->errorString();
}

QString MudletProfile::deleteRoom(int room)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->deleteRoom(room) ? QString() : mSession->errorString();
}

QString MudletProfile::setPlayerRoom(int room)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->setPlayerRoom(room) ? QString() : mSession->errorString();
}

QString MudletProfile::renameArea(int area, const QString& name)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->renameArea(area, name) ? QString() : mSession->errorString();
}

std::variant<QList<MapEnvInfo>, QString> MudletProfile::mapEnvs() const
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    QList<MapEnvInfo> envs;
    for (const THeadlessSession::MapEnv& env : mSession->mapEnvs()) {
        envs.append({env.env, env.color.name()});
    }
    return envs;
}

QString MudletProfile::downloadMap()
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    if (mMapDownload.state == MapDownload::State::downloading) {
        return QStringLiteral("the map is already downloading");
    }
    const QString url = mSession->offeredMapUrl();
    if (!mSession->downloadOfferedMap()) {
        return mSession->errorString();
    }
    takeOfferedMap(url);
    setMapDownloadState(MapDownload::State::downloading);
    mMapDownloadUrl = url;
    // Only the host: the rest of the URL can hold a user name, password or token
    qInfo("MudletEngine: downloading the map from %s", qPrintable(mMapDownload.host));
    pushMapDownloadProgress(true);
    return {};
}

void MudletProfile::pushRefusedCertificate()
{
    const auto refused = mSession->refusedCertificate();
    if (!refused) {
        mFrontend->certificateRefused(std::nullopt);
        return;
    }
    qInfo("MudletEngine: %s's secure connection was refused for its certificate", qPrintable(mProfile));
    mFrontend->certificateRefused(RefusedCertificate{refused->issuedTo, refused->issuer, refused->expires, refused->serial, refused->expired, refused->selfSigned, refused->others, refused->errors});
}

void MudletProfile::setSecureOffer(bool offered)
{
    mSecureOffer = offered;
    mFrontend->secureOffer(offered);
}

QString MudletProfile::setScriptsStopped(bool stopped)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    if (!mSession->setScriptsStopped(stopped)) {
        return mSession->errorString();
    }
    mFrontend->scriptsStopped(mSession->scriptsStopped());
    return {};
}

QString MudletProfile::setTimeStamps(bool shown)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    if (!mSession->setTimeStampsShown(shown)) {
        return mSession->errorString();
    }
    return {};
}

void MudletProfile::reportTimeStamps(bool shown)
{
    mFrontend->status(QStringLiteral("timeStamps"), shown ? QStringLiteral("true") : QStringLiteral("false"));
}

QString MudletProfile::replayFolder() const
{
    // As MudletApp::getMudletPath(enums::profileReplayAndLogFilesPath) names it
    return QStringLiteral("%1/profiles/%2/log").arg(mEngine.mConfigDir, mProfile);
}

QStringList MudletProfile::replays() const
{
    return QDir(replayFolder()).entryList({QStringLiteral("*.dat")}, QDir::Files);
}

QString MudletProfile::setReplayRecording(bool record)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    const QStringList before = record ? replays() : QStringList();
    if (!mSession->setReplayRecording(record)) {
        // A stop that could not save still ends the recording
        if (mRecording && !mSession->replayRecording()) {
            pushRecordingEnded();
        }
        return mSession->errorString();
    }
    if (record) {
        mRecording = true;
        mReplaysBefore = before;
        mFrontend->replayRecording(true, mRecordedFile);
    } else {
        pushRecordingEnded();
    }
    return {};
}

void MudletProfile::pushRecordingEnded()
{
    mRecording = false;
    QStringList saved = replays();
    saved.removeIf([this](const QString& file) {
        return mReplaysBefore.contains(file);
    });
    mReplaysBefore.clear();
    // None where Mudlet could not save it, so the one saved before is still the last
    if (!saved.isEmpty()) {
        mRecordedFile = saved.constFirst();
    }
    mFrontend->replayRecording(false, mRecordedFile);
}

QString MudletProfile::playReplay(const QString& path)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    // A name alone is one in the log folder, as for a script's loadReplay()
    return mSession->playReplay(QDir(replayFolder()).absoluteFilePath(path)) ? QString() : mSession->errorString();
}

QString MudletProfile::setReplayPaused(bool paused)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    if (!(paused ? mSession->pauseReplay() : mSession->resumeReplay())) {
        return mSession->errorString();
    }
    // Libmudlet says nothing of a pause; one before replayStarted is told with the start
    if (mReplay.playing) {
        mReplay.paused = paused;
        mFrontend->replay(mReplay);
    }
    return {};
}

QString MudletProfile::stopReplay()
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->stopReplay() ? QString() : mSession->errorString();
}

QString MudletProfile::changeReplaySpeed(bool faster)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->changeReplaySpeed(faster) ? QString() : mSession->errorString();
}

QString MudletProfile::answerSecureOffer(bool accept)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    if (!mSecureOffer) {
        return QStringLiteral("the game has offered no secure port to answer");
    }
    // A save waiting to be applied would put back the address a yes replaces
    if (mSaved || mEngine.loadingOther(this)) {
        return QStringLiteral("the profile is busy; answer again in a moment");
    }
    // Before the answer, which closes the connection. Mudlet lets the offer go even when it does not take the answer.
    setSecureOffer(false);
    if (!mSession->answerSecureConnectionOffer(accept)) {
        return mSession->errorString();
    }
    if (accept) {
        // Mudlet stored the secure port with TLS on, as its connection dialog would, and connects there: a restore and
        // the UI's next connect go there too
        qInfo("MudletEngine: %s takes its game's secure port", qPrintable(mProfile));
        mProfileTls = true;
        mKept.host.clear();
        mKept.port = 0;
        mKept.tls.reset();
        mEngine.persist();
        mEngine.mHost->profilesChanged();
    } else {
        qInfo("MudletEngine: %s declines its game's secure port, and is not asked again", qPrintable(mProfile));
    }
    return {};
}

QString MudletProfile::cancelMapDownload()
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    if (mMapDownload.state != MapDownload::State::downloading) {
        return QStringLiteral("no map is downloading");
    }
    if (!mMapDownload.cancelable) {
        return QStringLiteral("the map has arrived and is being loaded, which cannot be cancelled");
    }
    if (!mSession->cancelMapOperation()) {
        return mSession->errorString();
    }
    mMapDownloadCancelled = true;
    return {};
}

QString MudletProfile::loadMapFile(const QString& path, const std::function<bool()>& cancelAsked)
{
    return runMapFile(MapFile::Operation::load, path, cancelAsked);
}

QString MudletProfile::saveMapFile(const QString& path, const std::function<bool()>& cancelAsked)
{
    return runMapFile(MapFile::Operation::save, path, cancelAsked);
}

QString MudletProfile::deleteMap()
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    if (mMapDownload.state == MapDownload::State::downloading) {
        return QStringLiteral("the map is downloading");
    }
    return mSession->deleteMap() ? QString() : mSession->errorString();
}

QString MudletProfile::runMapFile(MapFile::Operation operation, const QString& path, const std::function<bool()>& cancelAsked)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    if (mMapDownload.state == MapDownload::State::downloading) {
        return QStringLiteral("the map is downloading");
    }
    if (!QFileInfo(path).isAbsolute()) {
        return QStringLiteral("the map file must be given by its whole path");
    }
    mMapFile = RunningMapFile{MapFile{operation}, cancelAsked, false, path.endsWith(QLatin1String(".json"), Qt::CaseInsensitive)};
    mFrontend->mapFile(mMapFile->file);
    const bool done = operation == MapFile::Operation::load ? mSession->loadMapFile(path) : mSession->saveMapFile(path);
    MapFile ended{operation, done ? MapFile::State::done : mMapFile->cancelled ? MapFile::State::cancelled : MapFile::State::failed};
    if (ended.state == MapFile::State::failed) {
        ended.error = mSession->errorString();
        qWarning("MudletEngine: the map file was not %s: %s", operation == MapFile::Operation::load ? "loaded" : "saved", qPrintable(ended.error));
    }
    mMapFile.reset();
    mFrontend->mapFile(ended);
    return {};
}

void MudletProfile::takeMapFileProgress(const QString& label, int value, int maximum, bool cancelable)
{
    MapFile& file = mMapFile->file;
    file.label = label;
    file.value = value;
    file.maximum = maximum;
    file.cancelable = cancelable && !mMapFile->cancelled;
    if (file.cancelable && mMapFile->cancelAsked && mMapFile->cancelAsked()) {
        if (mSession->cancelMapOperation()) {
            mMapFile->cancelled = true;
            file.cancelable = false;
        } else {
            qWarning("MudletEngine: the map file could not be stopped: %s", qPrintable(mSession->errorString()));
        }
    }
    mFrontend->mapFile(file);
}

std::variant<IEngine::Proxy, QString> MudletProfile::proxy()
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    THeadlessSession::Proxy proxy;
    if (!mSession->proxy(proxy)) {
        return mSession->errorString();
    }
    return Proxy{proxy.use, proxy.address, proxy.port, proxy.username};
}

QString MudletProfile::setProxy(const Proxy& proxy)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->setProxy({proxy.use, proxy.host, proxy.port, proxy.user}) ? QString() : mSession->errorString();
}

std::variant<QVariantMap, QString> MudletProfile::settings(const QStringList& keys)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    QVariantMap values;
    if (!mSession->settings(keys, values)) {
        return mSession->errorString();
    }
    return values;
}

QString MudletProfile::setSettings(const QVariantMap& values, QMap<QString, QString>& refused)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->setSettings(values, refused) ? QString() : mSession->errorString();
}

std::variant<QList<PackageInfo>, QString> MudletProfile::packages() const
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    QList<PackageInfo> packages;
    for (const THeadlessSession::InstalledPackage& package : mSession->installedPackages()) {
        packages.append({package.name, package.title, package.author, package.version, package.description, package.icon});
    }
    return packages;
}

QString MudletProfile::installPackage(const QString& path)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    mSession->installPackageAsync(path);
    return {};
}

QString MudletProfile::uninstallPackage(const QString& name)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->uninstallPackage(name) ? QString() : mSession->errorString();
}

std::variant<QList<ScriptItemInfo>, QString> MudletProfile::scriptItems(ScriptItemKind kind) const
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    QList<ScriptItemInfo> items;
    for (const THeadlessSession::ScriptItemNode& node : mSession->scriptItems(sessionKind(kind))) {
        items.append({node.id, node.parent, node.name, node.folder, node.enabled, node.active, node.package, node.error, node.summary});
    }
    return items;
}

std::variant<QVariantMap, QString> MudletProfile::scriptItem(ScriptItemKind kind, int item)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    QVariantMap fields;
    if (!mSession->scriptItem(sessionKind(kind), item, fields)) {
        return mSession->errorString();
    }
    return fields;
}

std::variant<int, QString> MudletProfile::addScriptItem(ScriptItemKind kind, int parent, int position, bool folder, const QString& name)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    const int id = mSession->addScriptItem(sessionKind(kind), parent, position, folder, name);
    if (!id) {
        return mSession->errorString();
    }
    return id;
}

QString MudletProfile::editScriptItem(ScriptItemKind kind, int item, const QVariantMap& fields)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->editScriptItem(sessionKind(kind), item, fields) ? QString() : mSession->errorString();
}

QString MudletProfile::enableScriptItem(ScriptItemKind kind, int item, bool enabled)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->setScriptItemEnabled(sessionKind(kind), item, enabled) ? QString() : mSession->errorString();
}

QString MudletProfile::moveScriptItem(ScriptItemKind kind, int item, int parent, int position)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->moveScriptItem(sessionKind(kind), item, parent, position) ? QString() : mSession->errorString();
}

QString MudletProfile::deleteScriptItem(ScriptItemKind kind, int item)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->deleteScriptItem(sessionKind(kind), item) ? QString() : mSession->errorString();
}

std::variant<IEngine::ScriptSearch, QString> MudletProfile::searchScriptItems(const QString& text, bool caseSensitive, bool wholeWord, int limit) const
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    ScriptSearch search;
    for (const THeadlessSession::ScriptSearchMatch& match : mSession->searchScriptItems(text, caseSensitive, wholeWord, limit, search.more)) {
        search.matches.append({engineKind(match.kind), match.id, match.name, engineField(match.field), match.index, match.text, match.start});
    }
    return search;
}

std::variant<IEngine::Variables, QString> MudletProfile::variables(const QList<VariableKey>& path, bool showHidden)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    QList<THeadlessSession::VariableKey> sessionPath;
    for (const VariableKey& key : path) {
        sessionPath.append({key.name, sessionType(key.type)});
    }
    const THeadlessSession::VariableListing listing = mSession->variables(sessionPath, showHidden);
    if (!listing.found) {
        return mSession->errorString();
    }
    Variables variables{.hiddenLeftOut = listing.hiddenLeftOut};
    for (const THeadlessSession::Variable& variable : listing.variables) {
        variables.variables.append({{variable.key.name, engineType(variable.key.keyType)}, engineType(variable.type), variable.value, variable.hidden, variable.members, variable.membersIndexed});
    }
    return variables;
}

std::optional<IEngine::LuaProblem> MudletProfile::checkLua(const QString& code) const
{
    const std::optional<THeadlessSession::LuaProblem> problem = THeadlessSession::checkLua(code);
    return problem ? std::optional<LuaProblem>(LuaProblem{problem->line, problem->message}) : std::nullopt;
}

QList<ErrorConsoleEntry> MudletProfile::errorConsole() const
{
    return {mErrorConsole.cbegin(), mErrorConsole.cend()};
}

void MudletProfile::clearErrorConsole()
{
    mErrorConsole.clear();
    mErrorConsoleChars = 0;
    mFrontend->errorConsoleCleared();
}

void MudletProfile::takeOfferedMap(const QString& url)
{
    mMapDownload.offered = !url.isEmpty();
    if (mMapDownload.state != MapDownload::State::downloading) {
        mMapDownload.host = QUrl::fromUserInput(url).host();
    }
}

void MudletProfile::setMapDownloadState(MapDownload::State state, const QString& error)
{
    mMapDownload.state = state;
    mMapDownload.label.clear();
    mMapDownload.value = 0;
    mMapDownload.maximum = 0;
    mMapDownload.cancelable = state == MapDownload::State::downloading;
    mMapDownload.error = error;
    mMapDownloadPercent = -1;
    mMapDownloadCancelled = false;
    mMapDownloadProgressStarted = false;
    if (state != MapDownload::State::downloading) {
        mMapDownloadUrl.clear();
    }
}

void MudletProfile::takeMapDownloadProgress(const QString& label, int value, int maximum, bool force)
{
    if (!mMapDownloadProgressStarted) {
        return;
    }
    mMapDownload.label = label;
    mMapDownload.value = value;
    mMapDownload.maximum = maximum;
    pushMapDownloadProgress(force);
}

void MudletProfile::pushMapDownloadProgress(bool force)
{
    const int percent = mMapDownload.maximum > 0 ? int(std::clamp(qint64(mMapDownload.value) * 100 / mMapDownload.maximum, qint64(0), qint64(100))) : -1;
    if (!force && percent == mMapDownloadPercent) {
        return;
    }
    mMapDownloadPercent = percent;
    mFrontend->mapDownload(mMapDownload);
}

void MudletProfile::setScreenSize(int columns, int rows)
{
    mScreenSize = QSize(columns, rows);
    mEngine.mUi.screen = mScreenSize;
    if (mState == State::started) {
        applyScreenSize();
    }
}

// What the game is told. Once the profile has the main window's size the UI gave, which scripts read and place their
// windows by, the UI's console only tells the game its own columns and rows; until then it sizes the main window too.
void MudletProfile::applyScreenSize()
{
    if (mScreenSize.isEmpty()) {
        return;
    }
    const int columns = mScreenSize.width();
    const int rows = mScreenSize.height();
    const bool taken = mMainWindowSize.isEmpty() ? mSession->setScreenSize(columns, rows) : mSession->setGameScreenSize(columns, rows);
    if (!taken) {
        qWarning("MudletEngine: the screen size %dx%d was refused: %s", columns, rows, qPrintable(mSession->errorString()));
    }
}

QString MudletProfile::link(const QString& window, int id, Link& link) const
{
    std::optional<THeadlessSession::Link> found;
    if (!isMainConsole(window)) {
        // A window's link ids are its own, not offset as the main console's are
        found = mSession->windowLink(window, id);
    } else if (id > mLinkBase) {
        // An id of an earlier profile's line names none of this one's links
        found = mSession->link(id - mLinkBase);
    }
    if (!found) {
        return QStringLiteral("unknown");
    }
    link.tooltip = found->tooltip;
    link.menu = found->menu;
    link.menuTitle = found->menuTitle;
    link.items.clear();
    for (const THeadlessSession::LinkItem& item : found->items) {
        link.items.append({item.label, item.enabled, item.separator});
    }
    link.spoiler = found->spoiler;
    link.revealed = found->revealed;
    link.disabled = found->disabled;
    link.concealed = found->concealed;
    link.visited = found->visited;
    link.selected = found->selected;
    link.fg = found->foreground ? std::optional<int>(colourCode(*found->foreground, false)) : std::nullopt;
    link.bg = found->background ? std::optional<int>(colourCode(*found->background, false)) : std::nullopt;
    link.styles = found->styles ? std::optional<int>(attributeBits(*found->styles)) : std::nullopt;
    return {};
}

// Empty where it ran, or revealed a spoiler, which linksChanged, or for a window its windowText, then tells of; else
// the LinkResult's name
QString MudletProfile::followLink(const QString& window, int id, std::optional<int> item)
{
    const bool mainConsole = isMainConsole(window);
    if (mainConsole && id <= mLinkBase) {
        return QStringLiteral("unknown");
    }
    const THeadlessSession::LinkResult result = mainConsole ? mSession->followLink(id - mLinkBase, item) : mSession->followWindowLink(window, id, item);
    switch (result) {
    case THeadlessSession::LinkResult::ran:
    case THeadlessSession::LinkResult::revealedSpoiler:
        return {};
    case THeadlessSession::LinkResult::disabled:
        return QStringLiteral("disabled");
    case THeadlessSession::LinkResult::concealed:
        return QStringLiteral("concealed");
    case THeadlessSession::LinkResult::noSuchItem:
        return QStringLiteral("noSuchItem");
    case THeadlessSession::LinkResult::unknown:
        return QStringLiteral("unknown");
    case THeadlessSession::LinkResult::failed:
        return QStringLiteral("failed: %1").arg(mSession->errorString());
    case THeadlessSession::LinkResult::refused:
        return QStringLiteral("refused: %1").arg(mSession->errorString());
    }
    return QStringLiteral("unknown");
}

void MudletProfile::reportConsoleColors(const QColor& foreground, const QColor& background)
{
    if (foreground.isValid() && background.isValid()) {
        mFrontend->status(QStringLiteral("consoleColors"), QStringLiteral("%1 %2").arg(foreground.name(), background.name()));
    }
}

// A line as the main console shows it once its triggers ran. It stays open, as the tail, until the next line or
// piece of text that does not continue it, so that one that does can still be put on its end.
void MudletProfile::onLineShown(const QString& text, bool isPrompt, const QList<ColorRun>& runs, qint64 arrivalMSecs, bool continuesLine, const QList<qint64>& lineIds)
{
    if (continuesLine && mOpen.kind != OpenLine::Kind::none) {
        appendToOpen(text, runs, lineIds);
        mOpen.kind = OpenLine::Kind::shown;
        mOpen.prompt = mOpen.prompt || isPrompt;
    } else {
        if (mOpen.kind == OpenLine::Kind::local) {
            mDropLineFeed = true;
        }
        commitOpen();
        mOpen = {OpenLine::Kind::shown, text, runs, isPrompt, arrivalMSecs >= 0 ? arrivalMSecs : QDateTime::currentMSecsSinceEpoch(), lineIds};
    }
    commitCompleteLines(OpenLine::Kind::shown);
    scheduleTailSync();
}

// A line of the open text is still the tail, which the next sync sends as it now is
void MudletProfile::onLineUpdated(qint64 lineId, const QString& text, const QList<ColorRun>& runs, bool isPrompt, bool deleted)
{
    const qsizetype part = lineId ? mOpen.lineIds.indexOf(lineId) : -1;
    if (part < 0) {
        mFrontend->lineUpdated(lineId, text, runs, isPrompt, deleted);
        return;
    }
    qsizetype from = 0;
    for (qsizetype i = 0; i < part; ++i) {
        from = mOpen.text.indexOf(QLatin1Char('\n'), from) + 1;
    }
    qsizetype to = mOpen.text.indexOf(QLatin1Char('\n'), from);
    if (to < 0) {
        to = mOpen.text.size();
    }
    const int size = int(mOpen.text.size());
    if (deleted) {
        if (!mOpen.text.contains(QLatin1Char('\n'))) {
            mOpen = {};
            scheduleTailSync();
            return;
        }
        // With the line feed that parts it from the next line, or for the last line, from the one before it
        if (to < size) {
            ++to;
        } else {
            --from;
        }
        QList<ColorRun> kept = sliceRuns(mOpen.runs, 0, int(from));
        for (ColorRun run : sliceRuns(mOpen.runs, int(to), size)) {
            run.start += int(from);
            kept.append(run);
        }
        mOpen.text.remove(from, to - from);
        mOpen.runs = kept;
        mOpen.lineIds.removeAt(part);
    } else {
        QList<ColorRun> edited = sliceRuns(mOpen.runs, 0, int(from));
        for (ColorRun run : runs) {
            run.start += int(from);
            edited.append(run);
        }
        const int shift = int(text.size() - (to - from));
        for (ColorRun run : sliceRuns(mOpen.runs, int(to), size)) {
            run.start += int(to) + shift;
            edited.append(run);
        }
        mOpen.text.replace(from, to - from, text);
        mOpen.runs = edited;
        if (part == mOpen.lineIds.size() - 1) {
            mOpen.prompt = isPrompt;
        }
    }
    scheduleTailSync();
}

// Text written to the main console that the game did not send, in pieces that need not end a line
void MudletProfile::onLocalText(const QString& text, const QList<ColorRun>& runs, bool continuesLine)
{
    QString piece = text;
    QList<ColorRun> pieceRuns = runs;
    if (std::exchange(mDropLineFeed, false) && piece.startsWith(QLatin1Char('\n'))) {
        piece.remove(0, 1);
        pieceRuns = sliceRuns(pieceRuns, 1, int(text.size()));
    }
    if (piece.isEmpty()) {
        return;
    }
    if (mOpen.kind == OpenLine::Kind::local || (continuesLine && mOpen.kind != OpenLine::Kind::none)) {
        appendToOpen(piece, pieceRuns);
    } else {
        commitOpen();
        mOpen = {OpenLine::Kind::local, piece, pieceRuns, false, QDateTime::currentMSecsSinceEpoch()};
    }
    commitCompleteLines(OpenLine::Kind::local);
    scheduleTailSync();
}

void MudletProfile::onMainConsoleCleared()
{
    mOpen = {};
    mDropLineFeed = false;
    mSentTail = {};
    mFrontend->cleared();
}

void MudletProfile::appendToOpen(const QString& text, const QList<ColorRun>& runs, const QList<qint64>& lineIds)
{
    const qsizetype breaks = text.count(QLatin1Char('\n'));
    if (breaks) {
        mOpen.lineIds.resize(mOpen.text.count(QLatin1Char('\n')) + 1);
        for (qsizetype i = 1; i <= breaks; ++i) {
            mOpen.lineIds.append(lineIds.value(i));
        }
    }
    const int offset = int(mOpen.text.size());
    mOpen.text.append(text);
    for (ColorRun run : runs) {
        run.start += offset;
        mOpen.runs.append(run);
    }
}

// Sends each line the open text's line feeds end. What follows the last one stays open: the rest of a line lineShown
// sent whole, which may be empty, or the start of a line localText has yet to end.
void MudletProfile::commitCompleteLines(OpenLine::Kind remainder)
{
    qsizetype end;
    while ((end = mOpen.text.indexOf(QLatin1Char('\n'))) >= 0) {
        const int size = int(mOpen.text.size());
        mFrontend->line(mOpen.text.left(end), sliceRuns(mOpen.runs, 0, int(end)), mOpen.prompt && remainder == OpenLine::Kind::local, mOpen.time, mOpen.lineIds.value(0));
        mOpen.runs = sliceRuns(mOpen.runs, int(end) + 1, size);
        mOpen.text.remove(0, end + 1);
        if (!mOpen.lineIds.isEmpty()) {
            mOpen.lineIds.removeFirst();
        }
        if (remainder == OpenLine::Kind::local) {
            mOpen.kind = OpenLine::Kind::local;
            mOpen.prompt = false;
        }
    }
    if (remainder == OpenLine::Kind::local && mOpen.text.isEmpty()) {
        mOpen = {};
    }
}

void MudletProfile::commitOpen()
{
    if (mOpen.kind == OpenLine::Kind::none) {
        return;
    }
    mFrontend->line(mOpen.text, mOpen.runs, mOpen.prompt, mOpen.time, mOpen.lineIds.value(0));
    mOpen = {};
    // The UI would otherwise show the line twice until the tail is next synced
    if (!mSentTail.text.isEmpty()) {
        mSentTail = {};
        mFrontend->tail({}, {}, false, 0);
    }
}

// Once a turn, so that a flood of lines sends only those lines and one tail
void MudletProfile::scheduleTailSync()
{
    if (mTailSyncPending) {
        return;
    }
    mTailSyncPending = true;
    QTimer::singleShot(0, this, &MudletProfile::syncTail);
}

void MudletProfile::syncTail()
{
    mTailSyncPending = false;
    if (mOpen.text == mSentTail.text && mOpen.runs == mSentTail.runs && mOpen.prompt == mSentTail.prompt && mOpen.time == mSentTail.time) {
        return;
    }
    mSentTail = mOpen;
    mFrontend->tail(mSentTail.text, mSentTail.runs, mSentTail.prompt, mSentTail.time);
}

void MudletProfile::markCountersDirty()
{
    mCountersDirty = true;
    if (!mCounterTimer.isActive()) {
        mCounterTimer.start();
    }
}

void MudletProfile::flushCounters()
{
    if (!mCountersDirty) {
        return;
    }
    mCountersDirty = false;
    mFrontend->status(QStringLiteral("linesReceived"), QString::number(mLinesReceived));
    mFrontend->status(QStringLiteral("promptsReceived"), QString::number(mPromptsReceived));
    mFrontend->status(QStringLiteral("localLines"), QString::number(mLocalLines));
    mFrontend->status(QStringLiteral("errorConsoleLines"), QString::number(mErrorLines));
}
