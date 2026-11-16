#include "MudletProfile.h"

#include "MudletEngine.h"
#include "THeadlessSession.h"

#include <QBuffer>
#include <QCryptographicHash>
#include <QEventLoop>
#include <QFileInfo>
#include <QIcon>
#include <QImage>
#include <QImageReader>
#include <QPainter>
#include <QPixmap>
#include <QRegularExpression>
#include <QUrl>

#include <algorithm>

namespace {

using ScriptWindow = THeadlessSession::ScriptWindow;
using TextRun = THeadlessSession::TextRun;
using TextStyle = THeadlessSession::TextStyle;

// Mudlet's console message as the reason the map download from url failed: without its "[ ERROR ] - " tag, on one
// line, and of any URL in it only the scheme and host, as a game can put a user name, password or token in the rest.
// The patch's messages show no more already; this holds should Mudlet's or Qt's text change.
QString mapDownloadError(QString reason, const QString& url)
{
    static const QRegularExpression tag(QStringLiteral("^\\s*\\[\\s*\\w+\\s*\\]\\s*-\\s*"));
    static const QRegularExpression anyUrl(QStringLiteral("([A-Za-z][A-Za-z0-9+.-]*://)(?:[^/\\s@]*@)?([^/\\s?#]*)\\S*"));
    static const QRegularExpression space(QStringLiteral("\\s+"));
    // First, as a password decoded can hold the '/' or '@' that would end the URL early for anyUrl
    const QUrl downloaded = QUrl::fromUserInput(url);
    for (const QString& password : {downloaded.password(QUrl::FullyDecoded), downloaded.password(QUrl::FullyEncoded)}) {
        if (!password.isEmpty()) {
            reason.replace(password, QStringLiteral("***"));
        }
    }
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

// Runs that are the profile's own colours with no style or link are left out, as the stand-in leaves them. A link's id
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

// As in libmudlet, "main" names the main console too, whose ids are offset
bool isMainConsole(const QString& window)
{
    return window.isEmpty() || window == QLatin1String("main");
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
    connect(session, &THeadlessSession::lineShown, this, [this](const QString&, const QString& text, bool isPrompt, const QList<TextRun>& runs, qint64, bool continuesLine) {
        onLineShown(text, isPrompt, colorRuns(runs, mLinkBase, mEngine.mLinkTop), continuesLine);
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
        pushAppMap();
        setMapDownloadState(MapDownload::State::none);
        takeOfferedMap(mSession->offeredMapUrl());
        mFrontend->mapDownload(mMapDownload);
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
    connect(session, &THeadlessSession::mapProgressCancelDisabled, this, [this] {
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
        mFrontend->mainArea({mMainWindowSize, grid, textRect});
    });
    connect(session, &THeadlessSession::connecting, this, [this] {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("connecting"));
    });
    connect(session, &THeadlessSession::connected, this, [this] {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("connected"));
        mConnected = true;
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
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("disconnected"));
        mConnected = false;
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
    start(open.host, open.port, open.connect, open.tls, open.password);
}

void MudletProfile::start(const QString& host, quint16 port, bool connect, std::optional<bool> tls, const QString& password)
{
    mStartHost = host;
    mStartPort = port;
    mStartConnect = connect;
    mStartTls = tls;
    // For this run only: THeadlessSession stores none, and a restore has none
    mStartPassword = password;
    mStartedLogin = mEngine.mProfiles.read(mProfile).login;
    mStartedPassword = passwordDigest(password);
    mNewPassword.reset();
    mConnected = false;
    mProfileTls = tls;
    if (const QString folder = mEngine.mProfiles.find(mProfile); !tls.has_value() && !folder.isEmpty()) {
        mProfileTls = mEngine.mProfiles.read(folder).tls;
    }
    const MudletEngine::UiSizes& ui = mEngine.mUi;
    mScreenSize = ui.screen;
    mMainWindowSize = ui.mainWindow;
    mMapScale = ui.mapScale;
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
    const auto state = mRestoring ? IEngineHost::ProfileState::restoring : IEngineHost::ProfileState::starting;
    if (mFrontend == mEngine.mHost->controlFrontend()) {
        mFrontend = mEngine.mHost->profileOpened(mProfile, this, state);
    } else {
        // Started again, as with a new login, on the socket it had
        mEngine.mHost->profileState(mProfile, state);
    }
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
    options.password = std::exchange(mStartPassword, {});
    options.configDir = mEngine.mConfigDir;
    // Kept until the start, and a session that joins the pool takes the pool's instead
    mSession->setApplicationActive(mEngine.mAppActive);
    mSession->setWindowMinimized(mEngine.mMinimized);
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
    for (const QString& code : pending) {
        runNow(code);
    }
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
    // A running profile connects as it was started: THeadlessSession takes the TLS choice only in start(), and
    // connectToServer() keeps the profile's. A connect refused for that changes nothing, a restore included.
    if (mState != State::idle && tls.has_value() && tls != mProfileTls) {
        QString started = QStringLiteral("with its stored choice");
        if (mProfileTls.has_value()) {
            started = *mProfileTls ? QStringLiteral("with TLS") : QStringLiteral("without TLS");
        }
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("error: TLS is chosen when the profile starts, and it started %1; stop the engine to change it").arg(started));
        return;
    }
    if (mNewPassword && running() && !mConnected) {
        qInfo("MudletEngine: %s starts again to connect with its new login or password", qPrintable(mProfile));
        cancelRestore();
        Open again;
        again.profile = mProfile;
        again.password = *mNewPassword;
        again.host = host;
        again.port = port;
        again.tls = tls;
        startAgain(again);
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

void MudletProfile::openAgain(const QString& password, bool connect)
{
    if (mEngine.mProfiles.read(mProfile).login == mStartedLogin && passwordDigest(password) == mStartedPassword) {
        mNewPassword.reset();
        if (connect) {
            connectProfile();
        }
        return;
    }
    if (mConnected || !connect) {
        qInfo("MudletEngine: %s has a new login or password, for its next connect", qPrintable(mProfile));
        mNewPassword = password;
        return;
    }
    qInfo("MudletEngine: %s starts again with its new login or password", qPrintable(mProfile));
    Open again;
    again.profile = mProfile;
    again.password = password;
    startAgain(again);
}

void MudletProfile::startAgain(const Open& open)
{
    mAgain = open;
    close(false);
}

bool MudletProfile::startIfAgain()
{
    if (!mAgain) {
        return false;
    }
    const Open again = *std::exchange(mAgain, std::nullopt);
    this->open(again);
    return true;
}

QByteArray MudletProfile::passwordDigest(const QString& password)
{
    return QCryptographicHash::hash(password.toUtf8(), QCryptographicHash::Sha256);
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
        if (!startIfAgain()) {
            closed();
        }
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
        mAgain.reset();
        mEngine.mHost->profileState(mProfile, IEngineHost::ProfileState::loaded);
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 not closed: %2").arg(mProfile, error));
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
    if (!startIfAgain()) {
        closed();
    }
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
    mMapSize.reset();
    mFrontend->map(std::nullopt);
    pushAppMap();
    setMapDownloadState(MapDownload::State::none);
    takeOfferedMap({});
    mFrontend->mapDownload(mMapDownload);
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
    mFrontend->mainArea({mMainWindowSize, mSession->mainConsoleGrid(), mSession->mainConsoleTextRect()});
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

void MudletProfile::pushMap(const std::optional<QSize>& mapperSize)
{
    mMapSize = mapperSize;
    mFrontend->map(mapperSize ? drawMap(*mapperSize) : std::nullopt);
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
    // Drawn in the window's pixels onto an image of the device's, so the lines and names are as sharp as the screen
    const double scale = std::max(1.0, std::min({mMapScale, double(PngImage::maxSide) / size.width(), double(PngImage::maxSide) / size.height()}));
    QImage image(size * scale, QImage::Format_ARGB32_Premultiplied);
    if (image.isNull()) {
        qWarning("MudletEngine: no memory for the map at %dx%d by %g", size.width(), size.height(), scale);
        return std::nullopt;
    }
    image.setDevicePixelRatio(scale);
    image.fill(Qt::transparent);
    QPainter painter(&image);
    const bool drawn = mSession->renderMap(painter, size);
    painter.end();
    if (!drawn) {
        qWarning("MudletEngine: the map was not drawn: %s", qPrintable(mSession->errorString()));
        return std::nullopt;
    }
    QByteArray png;
    QBuffer buffer(&png);
    buffer.open(QIODevice::WriteOnly);
    if (!image.save(&buffer, "PNG")) {
        qWarning("MudletEngine: the map could not be made a PNG");
        return std::nullopt;
    }
    return MapFrame{png, size, mSession->mapViewOnly()};
}

// Nothing while none is asked for: setAppMapSize() said so once
void MudletProfile::pushAppMap()
{
    if (!mAppMapSize.isEmpty()) {
        mFrontend->appMap(loaded() ? drawAppMap() : std::nullopt);
    }
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
    QByteArray png;
    QBuffer buffer(&png);
    buffer.open(QIODevice::WriteOnly);
    if (!image.save(&buffer, "PNG")) {
        warnAppMapUndrawn(QStringLiteral("it could not be made a PNG"));
        return std::nullopt;
    }
    mAppMapUndrawn.clear();
    return MapFrame{png, mAppMapSize, mSession->mapViewOnly()};
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

QString MudletProfile::setMapScale(double scale)
{
    mEngine.mUi.mapScale = scale;
    if (scale == mMapScale) {
        return {};
    }
    mMapScale = scale;
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
    QList<THeadlessSession::RoomMatch> found = mSession->findRooms(text);
    std::sort(found.begin(), found.end(), [](const THeadlessSession::RoomMatch& a, const THeadlessSession::RoomMatch& b) {
        const int byName = a.name.compare(b.name, Qt::CaseInsensitive);
        return byName != 0 ? byName < 0 : a.id < b.id;
    });
    RoomList list;
    list.more = found.size() > RoomList::maxRooms;
    for (const THeadlessSession::RoomMatch& room : found.first(std::min<qsizetype>(found.size(), RoomList::maxRooms))) {
        list.rooms.append({room.id, room.name, room.areaName});
    }
    return list;
}

QString MudletProfile::showRoom(int room)
{
    if (!loaded()) {
        return QStringLiteral("no profile running");
    }
    return mSession->showRoomOnAppMap(room) ? QString() : mSession->errorString();
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
void MudletProfile::onLineShown(const QString& text, bool isPrompt, const QList<ColorRun>& runs, bool continuesLine)
{
    if (continuesLine && mOpen.kind != OpenLine::Kind::none) {
        appendToOpen(text, runs);
        mOpen.kind = OpenLine::Kind::shown;
        mOpen.prompt = mOpen.prompt || isPrompt;
    } else {
        if (mOpen.kind == OpenLine::Kind::local) {
            mDropLineFeed = true;
        }
        commitOpen();
        mOpen = {OpenLine::Kind::shown, text, runs, isPrompt};
    }
    commitCompleteLines(OpenLine::Kind::shown);
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
        mOpen = {OpenLine::Kind::local, piece, pieceRuns, false};
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

void MudletProfile::appendToOpen(const QString& text, const QList<ColorRun>& runs)
{
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
        mFrontend->line(mOpen.text.left(end), sliceRuns(mOpen.runs, 0, int(end)), mOpen.prompt && remainder == OpenLine::Kind::local);
        mOpen.runs = sliceRuns(mOpen.runs, int(end) + 1, size);
        mOpen.text.remove(0, end + 1);
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
    mFrontend->line(mOpen.text, mOpen.runs, mOpen.prompt);
    mOpen = {};
    // The UI would otherwise show the line twice until the tail is next synced
    if (!mSentTail.text.isEmpty()) {
        mSentTail = {};
        mFrontend->tail({}, {}, false);
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
    if (mOpen.text == mSentTail.text && mOpen.runs == mSentTail.runs && mOpen.prompt == mSentTail.prompt) {
        return;
    }
    mSentTail = mOpen;
    mFrontend->tail(mSentTail.text, mSentTail.runs, mSentTail.prompt);
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
