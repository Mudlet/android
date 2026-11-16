#include "MudletEngine.h"

#include "THeadlessSession.h"

#ifdef Q_OS_ANDROID
#include "AndroidNotifier.h"
#endif

#include <QBuffer>
#include <QCoreApplication>
#include <QDataStream>
#include <QDateTime>
#include <QDir>
#include <QEventLoop>
#include <QFile>
#include <QFileInfo>
#include <QFontDatabase>
#include <QFontInfo>
#include <QFontMetrics>
#include <QGuiApplication>
#include <QIcon>
#include <QImage>
#include <QImageReader>
#include <QPainter>
#include <QPixmap>
#include <QRegularExpression>
#include <QStandardPaths>
#include <QUrl>

#include <algorithm>

namespace {

void showProfileInNotification([[maybe_unused]] const QString& profile)
{
#ifdef Q_OS_ANDROID
    AndroidNotifier::showProfile(profile);
#endif
}

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
    return info;
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
    info.styleSheet = content.styleSheet;
    return info;
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

QString settleConfigDir()
{
    // As the GUI's, as the session reads Mudlet.ini and the keychain under them, and AppDataLocation follows them
    QCoreApplication::setOrganizationName(QStringLiteral("Mudlet"));
    QCoreApplication::setApplicationName(QStringLiteral("Mudlet"));
    // The profiles, Mudlet.ini and the passwords Mudlet does not give the KeyStore, in the app's own files
    QString dir = qEnvironmentVariable("MUDLET_CONFIG_DIR");
    if (dir.isEmpty()) {
        dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/mudlet");
    }
    return QDir(dir).absolutePath();
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

} // namespace

MudletEngine::MudletEngine(IFrontend* frontend, int heartbeatMs, QObject* parent)
: QObject(parent)
, mFrontend(frontend)
, mProfile(qEnvironmentVariable("MUDLET_PROFILE", QStringLiteral("Android")))
, mDefaultProfile(mProfile)
, mConfigDir(settleConfigDir())
, mProfiles(mConfigDir)
{
    // What a restart of the engine brings back, in the app's files beside the profiles; on Android the app names
    // both files (EngineSession.kt)
    QString statePath = qEnvironmentVariable("MUDLET_ENGINE_STATE");
    if (statePath.isEmpty()) {
        statePath = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/engine-session.json");
    }
    QString stopMarkerPath = qEnvironmentVariable("MUDLET_ENGINE_STOPPED");
    if (stopMarkerPath.isEmpty()) {
        stopMarkerPath = QFileInfo(statePath).absoluteDir().filePath(QStringLiteral("engine-stopped"));
    }
    mStore = std::make_unique<SessionStore>(statePath, stopMarkerPath);

    mSession = std::make_unique<THeadlessSession>();
    connectSession();

    mCounterTimer.setSingleShot(true);
    mCounterTimer.setInterval(100);
    connect(&mCounterTimer, &QTimer::timeout, this, &MudletEngine::flushCounters);

    reportFont();
    mFrontend->status(QStringLiteral("engineKind"), QStringLiteral("libmudlet"));
    mFrontend->status(QStringLiteral("configDir"), mConfigDir);
    mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 (not started)").arg(mProfile));
    mFrontend->status(QStringLiteral("connection"), QStringLiteral("disconnected"));

    mLastHeartbeatMs = QDateTime::currentMSecsSinceEpoch();
    mHeartbeat.setInterval(heartbeatMs);
    connect(&mHeartbeat, &QTimer::timeout, this, &MudletEngine::onHeartbeat);
    mHeartbeat.start();

    mRestoreTimer.setSingleShot(true);
    connect(&mRestoreTimer, &QTimer::timeout, this, &MudletEngine::beginRestore);
    takeRestore();
}

MudletEngine::~MudletEngine()
{
    // An orderly stop, as the user's: the next engine starts with no profile
    mClosing = true;
    mStore->clear();
    if (mState == State::started || mState == State::stopping) {
        // A switch that waits for a save has not begun its close
        shutdownSession(mState == State::stopping && !mSaving);
    }
    // Nothing the session sends as it goes may reach the members already destroyed
    QObject::disconnect(mSession.get(), nullptr, this, nullptr);
    mSession.reset();
}

// The profile gets its sysExitEvent and its save, waited for a few seconds at most, as the process is ending. One
// already closing for a switch is only waited for, as a second shutdown would be refused at once.
void MudletEngine::shutdownSession(bool alreadyClosing)
{
    QEventLoop loop;
    QObject::connect(mSession.get(), &THeadlessSession::shutdownFinished, &loop, [&loop](bool succeeded, const QString& error) {
        if (!succeeded) {
            qWarning("MudletEngine: the profile was not saved at exit: %s", qPrintable(error));
        }
        loop.quit();
    });
    QTimer::singleShot(5000, &loop, [&loop] {
        qWarning("MudletEngine: the profile's save at exit did not finish in 5 s");
        loop.quit();
    });
    if (!alreadyClosing) {
        // THeadlessSession refuses a shutdown while a save runs
        if (mSaving) {
            QObject::connect(mSession.get(), &THeadlessSession::saveFinished, &loop, [this] {
                mSession->shutdownAsync(true);
            });
        } else {
            mSession->shutdownAsync(true);
        }
    }
    loop.exec();
}

// What the lines, mid-write signals among them, do here only touches the frontend: they must not call the session
void MudletEngine::connectSession()
{
    THeadlessSession* session = mSession.get();
    connect(session, &THeadlessSession::startFinished, this, [this](THeadlessSession::StartResult result, const QString& error) {
        onStartFinished(int(result), error);
    });
    connect(session, &THeadlessSession::shutdownFinished, this, [this](bool saved, const QString& error) {
        if (!mClosing && mState == State::stopping) {
            onShutdownFinished(saved, error);
        }
    });
    // Counted only: what the UI shows comes from lineShown, once the line's triggers have run
    connect(session, &THeadlessSession::outputLine, this, [this](const QString&, const QString&, bool isPrompt) {
        ++(isPrompt ? mPromptsReceived : mLinesReceived);
        markCountersDirty();
    });
    connect(session, &THeadlessSession::lineShown, this, [this](const QString&, const QString& text, bool isPrompt, const QList<TextRun>& runs, qint64, bool continuesLine) {
        onLineShown(text, isPrompt, colorRuns(runs, mLinkBase, mLinkTop), continuesLine);
    });
    connect(session, &THeadlessSession::localTextStyled, this, [this](const QString&, const QString& text, const QList<TextRun>& runs, bool continuesLine) {
        mLocalLines += text.count(QLatin1Char('\n'));
        markCountersDirty();
        onLocalText(text, colorRuns(runs, mLinkBase, mLinkTop), continuesLine);
    });
    connect(session, &THeadlessSession::mainConsoleCleared, this, &MudletEngine::onMainConsoleCleared);
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
    connect(session, &THeadlessSession::saveFinished, this, &MudletEngine::onSaveFinished);
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
        mProfiles.markOpened(profile);
        showProfileInNotification(profile);
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 loaded").arg(profile));
        mFrontend->status(QStringLiteral("profileLoadMs"), QString::number(mStartClock.elapsed()));
        mFrontend->history(mSession->commandHistory());
        reportConsoleColors(mSession->consoleForeground(), mSession->consoleBackground());
        // As early as the profile takes it, so a game that asks as it connects is not first told 90 by 25
        applyScreenSize();
        mLoaded = true;
        if (!mMainWindowSize.isEmpty() && !mSession->setMainWindowSize(mMainWindowSize)) {
            qWarning("MudletEngine: the main window's size %dx%d was refused: %s", mMainWindowSize.width(), mMainWindowSize.height(), qPrintable(mSession->errorString()));
            // So mainArea does not give it as the profile's
            mMainWindowSize = {0, 0};
        }
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
    connect(session, &THeadlessSession::buttonsChanged, this, &MudletEngine::pushButtonBars);
    connect(session, &THeadlessSession::addonCommandsChanged, this, &MudletEngine::pushCommands);
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
    // The progress of a map download, import or export a script began is not the download's
    connect(session, &THeadlessSession::mapProgressStarted, this, [this](const QString&, const QString&, const QString& label, int maximum) {
        takeMapDownloadProgress(label, 0, maximum, true);
    });
    connect(session, &THeadlessSession::mapProgressChanged, this, [this](const QString&, const QString& label, int value, int maximum) {
        takeMapDownloadProgress(label, value, maximum, label != mMapDownload.label || maximum != mMapDownload.maximum);
    });
    connect(session, &THeadlessSession::mapProgressCancelDisabled, this, [this] {
        if (mMapDownload.state == MapDownload::State::downloading && std::exchange(mMapDownload.cancelable, false)) {
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
        // mKept is already the next profile's
        if (mState == State::stopping) {
            return;
        }
        mDisconnectRequested = false;
        mKept.connect = true;
        persist();
        if (std::exchange(mRestoring, false)) {
            restoreStatus(QStringLiteral("reconnected"), restoreTarget());
        }
    });
    connect(session, &THeadlessSession::disconnected, this, [this] {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("disconnected"));
        if (mState == State::stopping) {
            return;
        }
        // Lost or refused rather than ended by the UI: wanted still only if Mudlet tries again, as the profile says
        if (!std::exchange(mDisconnectRequested, false)) {
            mKept.connect = profileReconnects();
        }
        persist();
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
        // A switch's own close finishes it
        if (mState == State::stopping) {
            return;
        }
        mState = State::idle;
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 closed").arg(mProfile));
        showProfileInNotification({});
        mKept = {};
        persist();
        mRestoring = false;
        onProfileClosed();
    });
}

// At start-up: the profile the engine before this one ran, if it was killed rather than stopped
void MudletEngine::takeRestore()
{
    const SessionStore::Restore restore = mStore->takeRestore(QDateTime::currentMSecsSinceEpoch());
    switch (restore.kind) {
    case SessionStore::Restore::Kind::none:
        return;
    case SessionStore::Restore::Kind::gaveUp:
        qWarning("MudletEngine: not restoring %s: the engine stopped unexpectedly %d times in %lld minutes",
                 qPrintable(restore.session.profile),
                 restore.attempt,
                 SessionStore::restoreWindowMs / 60000);
        restoreStatus(QStringLiteral("gaveUp"), QStringLiteral("%1 %2").arg(restore.attempt).arg(SessionStore::restoreWindowMs / 60000));
        return;
    case SessionStore::Restore::Kind::restore:
        break;
    }
    mKept = restore.session;
    mSavedKept = mKept;
    mProfile = mKept.profile;
    mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 (not started)").arg(mProfile));
    if (mKept.connect && restore.reconnectDelayMs > 0) {
        restoreStatus(QStringLiteral("waiting"), QStringLiteral("%1 %2").arg(restore.reconnectDelayMs / 1000).arg(restoreTarget()));
        mRestoreTimer.start(restore.reconnectDelayMs);
        return;
    }
    // From the event loop, once the engine listens, as the load takes a while
    mRestoreTimer.start(0);
}

void MudletEngine::beginRestore()
{
    if (mState != State::idle) {
        return;
    }
    mRestoring = true;
    if (mKept.connect) {
        restoreStatus(QStringLiteral("reconnecting"), restoreTarget());
    } else {
        restoreStatus(QStringLiteral("restoring"), mProfile);
    }
    start(mKept.host, quint16(mKept.port), mKept.connect, mKept.tls);
}

// The UI took over: the restore no longer reports, nor connects later
void MudletEngine::cancelRestore()
{
    if (!mRestoreTimer.isActive() && !mRestoring) {
        return;
    }
    mRestoreTimer.stop();
    mRestoring = false;
    restoreStatus(QStringLiteral("cancelled"));
}

// docs/protocol.md, "The restore status": a phase, then what it is about
void MudletEngine::restoreStatus(const QString& phase, const QString& detail)
{
    const QString value = detail.isEmpty() ? phase : QStringLiteral("%1 %2").arg(phase, detail);
    qInfo("MudletEngine: restore: %s", qPrintable(value));
    mFrontend->status(QStringLiteral("restore"), value);
}

void MudletEngine::persist()
{
    if (mClosing || mKept == mSavedKept) {
        return;
    }
    mSavedKept = mKept;
    mStore->save(mKept);
}

QString MudletEngine::restoreTarget() const
{
    return mKept.host.isEmpty() ? mProfile : QStringLiteral("%1:%2").arg(mKept.host).arg(mKept.port);
}

// Mudlet's "Reconnect automatically" for the profile, as its connection dialog stores it
bool MudletEngine::profileReconnects() const
{
    return mProfiles.read(mProfile).autoReconnect;
}

void MudletEngine::reportFont()
{
    QFont font = QFontDatabase::systemFont(QFontDatabase::FixedFont);
    font.setPixelSize(14);
    mFrontend->status(QStringLiteral("fontFamily"), QFontInfo(font).family());
    mFrontend->status(QStringLiteral("advanceM"), QString::number(QFontMetrics(font).horizontalAdvance(QLatin1Char('M'))));
    mFrontend->status(QStringLiteral("platform"), QGuiApplication::platformName());
}

void MudletEngine::start(const QString& host, quint16 port, bool connect, std::optional<bool> tls, const QString& password)
{
    THeadlessSession::Options options;
    options.profile = mProfile;
    options.connect = connect;
    options.host = host;
    options.port = port;
    // Stored with the profile, as the connection dialog's box stores it; unset, the profile's own
    options.tls = tls;
    // For this run only: THeadlessSession stores none, and a restore has none
    options.password = password;
    mProfileTls = tls;
    if (const QString folder = mProfiles.find(mProfile); !tls.has_value() && !folder.isEmpty()) {
        mProfileTls = mProfiles.read(folder).tls;
    }
    options.configDir = mConfigDir;
    // The new profile counts its links from 1 again
    mLinkBase = mLinkTop;
    mKept.profile = mProfile;
    mKept.tls = tls;
    persist();
    mState = State::starting;
    mLoaded = false;
    mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 starting").arg(mProfile));
    mStartClock.start();
    mSession->startAsync(options);
}

void MudletEngine::onStartFinished(int result, const QString& error)
{
    mFrontend->status(QStringLiteral("startMs"), QString::number(mStartClock.elapsed()));
    const QStringList pending = std::exchange(mPendingLua, {});
    if (THeadlessSession::StartResult(result) != THeadlessSession::StartResult::started) {
        mState = State::idle;
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("start failed (%1): %2").arg(result).arg(error));
        if (!pending.isEmpty()) {
            mFrontend->status(QStringLiteral("luaResult"), QStringLiteral("no profile"));
        }
        mKept.profile.clear();
        mKept.connect = false;
        persist();
        if (std::exchange(mRestoring, false)) {
            restoreStatus(QStringLiteral("failed"), error);
        }
        if (mPendingOpen) {
            openPending();
        }
        return;
    }
    mState = State::started;
    // Again, for a setScreenSize that came between profileLoaded and now; the same size changes nothing
    applyScreenSize();
    // Refused while the profile loaded
    applyAppActive();
    if (mRestoring && !mKept.connect) {
        mRestoring = false;
        restoreStatus(QStringLiteral("restored"), mProfile);
    }
    for (const QString& code : pending) {
        runNow(code);
    }
    // Opened while this one started
    if (mPendingOpen) {
        beginSwitch();
    }
}

void MudletEngine::runNow(const QString& code)
{
    const bool ok = mSession->runLua(code);
    mFrontend->status(QStringLiteral("luaResult"), ok ? QStringLiteral("ran") : QStringLiteral("refused: %1").arg(mSession->errorString()));
}

void MudletEngine::connectTo(const QString& host, quint16 port, std::optional<bool> tls)
{
    if (mPendingOpen) {
        cancelRestore();
        mPendingOpen->host = host;
        mPendingOpen->port = port;
        mPendingOpen->tls = tls;
        mPendingOpen->connect = true;
        keepPending();
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
    const QString code = QStringLiteral("connectToServer(%1, %2)").arg(luaLongString(host)).arg(port);
    cancelRestore();
    mDisconnectRequested = false;
    mKept.host = host;
    mKept.port = port;
    mKept.connect = true;
    persist();
    switch (mState) {
    case State::idle:
        start(host, port, true, tls);
        return;
    case State::starting:
        mPendingLua.append(code);
        return;
    case State::started:
        if (!mSession->runLua(code)) {
            mFrontend->status(QStringLiteral("connection"), QStringLiteral("error: %1").arg(mSession->errorString()));
        }
        return;
    case State::stopping:
        return;
    }
}

bool MudletEngine::takesInput()
{
    if (mState != State::started) {
        mFrontend->status(QStringLiteral("sendError"), QStringLiteral("no profile running"));
        return false;
    }
    return true;
}

void MudletEngine::send(const QString& line)
{
    if (takesInput() && !mSession->submit(line)) {
        mFrontend->status(QStringLiteral("sendError"), mSession->errorString());
    }
}

// Empty with no profile open
QStringList MudletEngine::history() const
{
    return mSession->commandHistory();
}

// The errors never quote the line, which THeadlessSession clears from its own copy once sent
void MudletEngine::sendHidden(const QString& line)
{
    if (takesInput() && !mSession->submitHiddenInput(line)) {
        mFrontend->status(QStringLiteral("sendError"), mSession->errorString());
    }
}

void MudletEngine::dismissHidden()
{
    if (takesInput() && !mSession->dismissHiddenInput()) {
        mFrontend->status(QStringLiteral("sendError"), mSession->errorString());
    }
}

// As Tab, or the up arrow with "Highlight history" on, on the GUI's command line holding text: calls that hand back
// the line the last one gave go on to the next word or older line
QString MudletEngine::complete(const QString& text, bool fromHistory)
{
    if (mState != State::started || !mSession->setCommandLineText(text)) {
        return {};
    }
    return (fromHistory ? mSession->completeCommandLineFromHistory() : mSession->completeCommandLine()).value_or(QString());
}

void MudletEngine::disconnectFromGame()
{
    // The user's disconnect: a restart of the engine brings the profile back, but does not connect it
    const bool waiting = mRestoreTimer.isActive();
    cancelRestore();
    if (mPendingOpen) {
        mPendingOpen->connect = false;
        keepPending();
        return;
    }
    mDisconnectRequested = true;
    mKept.connect = false;
    persist();
    if (waiting && !mPendingLua.isEmpty()) {
        // Lua held back for the restore runs all the same
        start({}, 0, false, mKept.tls);
    }
    if (mState == State::started && !mSession->runLua(QStringLiteral("disconnect()"))) {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("error: %1").arg(mSession->errorString()));
    }
}

void MudletEngine::runLua(const QString& code)
{
    if (mPendingOpen) {
        mPendingOpen->lua.append(code);
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
        runNow(code);
        return;
    case State::stopping:
        // Not reached: a switch always has its open pending
        return;
    }
}

QString MudletEngine::saveProfile(const Profile& profile)
{
    Profile stored = profile;
    const QString folder = mProfiles.find(profile.name);
    if (!folder.isEmpty()) {
        stored.name = folder;
    } else if (const QString problem = Profile::newNameProblem(profile.name); !problem.isEmpty()) {
        return problem;
    }
    // A running profile read these when it started: they take effect at its next start
    if (const QString problem = mProfiles.save(stored, folder.isEmpty()); !problem.isEmpty()) {
        return QStringLiteral("could not store \"%1\": %2").arg(stored.name, problem);
    }
    return {};
}

QString MudletEngine::deleteProfile(const QString& name)
{
    const QString folder = mProfiles.find(name);
    if (folder.isEmpty()) {
        return QStringLiteral("there is no profile named \"%1\"").arg(name);
    }
    if (inUse(folder)) {
        return QStringLiteral("\"%1\" is open: open another profile before deleting it").arg(folder);
    }
    if (const QString problem = mProfiles.remove(folder); !problem.isEmpty()) {
        return problem;
    }
    // Not to be made again, empty, by the next connect
    if (folder.compare(mProfile, Qt::CaseInsensitive) == 0) {
        mProfile = mDefaultProfile;
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 (not started)").arg(mProfile));
    }
    return {};
}

// Running, about to run, closing, or waited for by a restore
bool MudletEngine::inUse(const QString& folder) const
{
    const auto same = [&folder](const QString& name) {
        return folder.compare(name, Qt::CaseInsensitive) == 0;
    };
    return (mState != State::idle && same(mProfile)) || (mPendingOpen && same(mPendingOpen->profile)) || (mRestoreTimer.isActive() && same(mKept.profile));
}

QString MudletEngine::openProfile(const QString& name, const QString& password, bool connect)
{
    const QString folder = mProfiles.find(name);
    if (folder.isEmpty()) {
        return QStringLiteral("there is no profile named \"%1\"").arg(name);
    }
    cancelRestore();
    mProfiles.markOpened(folder);
    if ((mState == State::starting || mState == State::started) && folder.compare(mProfile, Qt::CaseInsensitive) == 0) {
        // THeadlessSession takes a password only at a start, so a running profile logs in with the one it started with
        mPendingOpen.reset();
        if (connect) {
            connectProfile();
        }
        return {};
    }
    mPendingOpen.emplace();
    mPendingOpen->profile = folder;
    mPendingOpen->password = password;
    mPendingOpen->connect = connect;
    switch (mState) {
    case State::idle:
        openPending();
        break;
    case State::started:
        beginSwitch();
        break;
    case State::starting:
        // onStartFinished() switches once that start is over
    case State::stopping:
        keepPending();
        break;
    }
    return {};
}

// The running profile, started without connecting or since disconnected, connects to its own address
void MudletEngine::connectProfile()
{
    if (mKept.connect) {
        return;
    }
    const Profile profile = mProfiles.read(mProfile);
    const QString code = QStringLiteral("connectToServer(%1, %2)").arg(luaLongString(profile.host)).arg(profile.port);
    mDisconnectRequested = false;
    mKept.host.clear();
    mKept.port = 0;
    mKept.connect = true;
    persist();
    if (mState == State::starting) {
        mPendingLua.append(code);
    } else if (!mSession->runLua(code)) {
        mFrontend->status(QStringLiteral("connection"), QStringLiteral("error: %1").arg(mSession->errorString()));
    }
}

// The running profile closes and saves, as Mudlet's own close does, before the pending one starts
void MudletEngine::beginSwitch()
{
    mState = State::stopping;
    mKeptBeforeSwitch = mKept;
    keepPending();
    mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 closing").arg(mProfile));
    // Else onSaveFinished() begins it, as THeadlessSession refuses a shutdown while a save runs
    if (!mSaving) {
        mSession->shutdownAsync(true);
    }
}

void MudletEngine::onShutdownFinished(bool saved, const QString& error)
{
    // Refused, as while a script runs: the profile is still running
    if (mSession->host()) {
        mState = State::started;
        mPendingOpen.reset();
        mKept = mKeptBeforeSwitch;
        persist();
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 not closed: %2").arg(mProfile, error));
        return;
    }
    mState = State::idle;
    showProfileInNotification({});
    onProfileClosed();
    if (saved) {
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 closed").arg(mProfile));
    } else {
        qWarning("MudletEngine: %s closed without its save: %s", qPrintable(mProfile), qPrintable(error));
        mFrontend->status(QStringLiteral("profile"), QStringLiteral("%1 closed without its save: %2").arg(mProfile, error));
    }
    openPending();
}

void MudletEngine::openPending()
{
    PendingOpen open = std::move(*mPendingOpen);
    mPendingOpen.reset();
    mProfile = open.profile;
    mKept = open.kept();
    mDisconnectRequested = false;
    // After any Lua a waiting restore held back, which runs all the same
    mPendingLua.append(open.lua);
    start(open.host, open.port, open.connect, open.tls, open.password);
}

// From the switch's start, a restart of the engine brings back the profile being opened, not the one closing
void MudletEngine::keepPending()
{
    if (mState != State::stopping) {
        return;
    }
    mKept = mPendingOpen->kept();
    persist();
}

void MudletEngine::onProfileClosed()
{
    mLoaded = false;
    mFrontend->windows({}, {});
    mFrontend->buttonBars({});
    mFrontend->commands({});
    mMapSize.reset();
    mFrontend->map(std::nullopt);
    pushAppMap();
    setMapDownloadState(MapDownload::State::none);
    takeOfferedMap({});
    mFrontend->mapDownload(mMapDownload);
}

void MudletEngine::pushWindows(bool withText)
{
    QList<ScriptWindowInfo> windows;
    QList<LabelInfo> labels;
    const QList<ScriptWindow> sessionWindows = mSession->scriptWindows();
    for (const ScriptWindow& window : sessionWindows) {
        windows.append(windowInfo(window));
        if (window.kind != ScriptWindow::Kind::label) {
            continue;
        }
        if (const std::optional<THeadlessSession::LabelContent> content = mSession->labelContent(window.name)) {
            labels.append(labelInfo(window.name, *content));
        }
    }
    mFrontend->windows(windows, labels);
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

void MudletEngine::pushWindowText(const QString& window)
{
    if (const std::optional<WindowText> text = windowText(window, -WindowText::recentRows, WindowText::recentRows)) {
        mFrontend->windowText(window, *text);
    }
}

// Also when only the size the UI gave changed, which mainConsoleAreaChanged does not tell
void MudletEngine::pushMainArea()
{
    mFrontend->mainArea({mMainWindowSize, mSession->mainConsoleGrid(), mSession->mainConsoleTextRect()});
}

void MudletEngine::pushButtonBars()
{
    QList<ButtonBar> bars;
    for (const THeadlessSession::ButtonBar& bar : mSession->buttonBars()) {
        bars.append(buttonBar(bar));
    }
    mFrontend->buttonBars(bars);
}

void MudletEngine::pushCommands()
{
    QList<AddonCommandInfo> commands;
    for (const THeadlessSession::AddonCommand& command : mSession->addonCommands()) {
        commands.append(addonCommandInfo(command));
    }
    mFrontend->commands(commands);
}

void MudletEngine::pushMap(const std::optional<QSize>& mapperSize)
{
    mMapSize = mapperSize;
    mFrontend->map(mapperSize ? drawMap(*mapperSize) : std::nullopt);
}

std::optional<MapFrame> MudletEngine::drawMap(const QSize& size)
{
    // A map widget opened before the UI gave the main window a size has none
    if (size.isEmpty()) {
        return std::nullopt;
    }
    if (size.width() > PngImage::maxSide || size.height() > PngImage::maxSide) {
        qWarning("MudletEngine: the mapper, %dx%d, is larger than %d pixels a side, so the map is not drawn", size.width(), size.height(), PngImage::maxSide);
        return std::nullopt;
    }
    QImage image(size, QImage::Format_ARGB32_Premultiplied);
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
void MudletEngine::pushAppMap()
{
    if (!mAppMapSize.isEmpty()) {
        mFrontend->appMap(profileOpen() ? drawAppMap() : std::nullopt);
    }
}

std::optional<MapFrame> MudletEngine::drawAppMap()
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
void MudletEngine::warnAppMapUndrawn(const QString& why)
{
    if (why == mAppMapUndrawn) {
        return;
    }
    mAppMapUndrawn = why;
    qWarning("MudletEngine: the app's map, %dx%d by %g, was not drawn: %s", mAppMapSize.width(), mAppMapSize.height(), mAppMapScale, qPrintable(why));
}

QString MudletEngine::setMainWindowSize(int width, int height)
{
    const QSize before = std::exchange(mMainWindowSize, QSize(width, height));
    // Otherwise taken as the profile loads
    if (!profileOpen()) {
        return {};
    }
    if (!mSession->setMainWindowSize(mMainWindowSize)) {
        mMainWindowSize = before;
        return mSession->errorString();
    }
    pushMainArea();
    return {};
}

std::optional<WindowText> MudletEngine::windowText(const QString& window, int first, int count) const
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

QString MudletEngine::placeUserWindow(const QString& name, const QRect& geometry, bool floating)
{
    if (!profileOpen()) {
        return QStringLiteral("no profile running");
    }
    return mSession->setUserWindowGeometry(name, geometry, floating) ? QString() : mSession->errorString();
}

QString MudletEngine::closeUserWindow(const QString& name)
{
    if (!profileOpen()) {
        return QStringLiteral("no profile running");
    }
    return mSession->closeUserWindow(name) ? QString() : mSession->errorString();
}

QString MudletEngine::sendWindowCommandLine(const QString& name, const QString& text)
{
    if (!profileOpen()) {
        return QStringLiteral("no profile running");
    }
    return mSession->submitWindowCommandLine(name, text) ? QString() : mSession->errorString();
}

QString MudletEngine::pointer(const QString& window, const PointerEvent& event)
{
    if (!profileOpen()) {
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

std::variant<PngImage, QString> MudletEngine::image(const QString& path, const QSize& size, std::optional<int> frame) const
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

QString MudletEngine::pressButton(int id)
{
    if (!profileOpen()) {
        return QStringLiteral("no profile running");
    }
    return mSession->pressButton(id) ? QString() : mSession->errorString();
}

QString MudletEngine::activateCommand(int id)
{
    if (!profileOpen()) {
        return QStringLiteral("no profile running");
    }
    return mSession->activateAddonCommand(id) ? QString() : mSession->errorString();
}

QString MudletEngine::mapPointer(const PointerEvent& event)
{
    if (!profileOpen()) {
        return QStringLiteral("no profile running");
    }
    const std::optional<THeadlessSession::MapPointerEvent> sessionEvent = mapPointerEvent(event);
    if (!sessionEvent) {
        return QStringLiteral("the map takes a press, a double-click or a wheel turn");
    }
    return mSession->mapPointer(*sessionEvent) ? QString() : mSession->errorString();
}

QString MudletEngine::setMapViewOnly(bool viewOnly)
{
    if (!profileOpen()) {
        return QStringLiteral("no profile running");
    }
    return mSession->setMapViewOnly(viewOnly) ? QString() : mSession->errorString();
}

QString MudletEngine::setAppMapSize(const QSize& size, double scale)
{
    if (size.width() * scale > PngImage::maxSide || size.height() * scale > PngImage::maxSide) {
        return QStringLiteral("the map at %1x%2 by %3 is larger than %4 pixels a side").arg(size.width()).arg(size.height()).arg(scale).arg(PngImage::maxSide);
    }
    mAppMapSize = size.isEmpty() ? QSize(0, 0) : size;
    mAppMapScale = scale;
    if (!mAppMapSize.isEmpty()) {
        pushAppMap();
        return {};
    }
    if (profileOpen()) {
        mSession->closeAppMap();
    }
    mFrontend->appMap(std::nullopt);
    return {};
}

QString MudletEngine::appMapPointer(const PointerEvent& event)
{
    if (!profileOpen()) {
        return QStringLiteral("no profile running");
    }
    const std::optional<THeadlessSession::MapPointerEvent> sessionEvent = mapPointerEvent(event);
    if (!sessionEvent) {
        return QStringLiteral("the map takes a press, a double-click or a wheel turn");
    }
    return mSession->appMapPointer(*sessionEvent) ? QString() : mSession->errorString();
}

std::variant<RoomList, QString> MudletEngine::findRooms(const QString& text) const
{
    if (!profileOpen()) {
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

QString MudletEngine::showRoom(int room)
{
    if (!profileOpen()) {
        return QStringLiteral("no profile running");
    }
    return mSession->showRoomOnAppMap(room) ? QString() : mSession->errorString();
}

QString MudletEngine::downloadMap()
{
    if (!profileOpen()) {
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

QString MudletEngine::cancelMapDownload()
{
    if (!profileOpen()) {
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

void MudletEngine::takeOfferedMap(const QString& url)
{
    mMapDownload.offered = !url.isEmpty();
    if (mMapDownload.state != MapDownload::State::downloading) {
        mMapDownload.host = QUrl::fromUserInput(url).host();
    }
}

void MudletEngine::setMapDownloadState(MapDownload::State state, const QString& error)
{
    mMapDownload.state = state;
    mMapDownload.label.clear();
    mMapDownload.value = 0;
    mMapDownload.maximum = 0;
    mMapDownload.cancelable = state == MapDownload::State::downloading;
    mMapDownload.error = error;
    mMapDownloadPercent = -1;
    mMapDownloadCancelled = false;
    if (state != MapDownload::State::downloading) {
        mMapDownloadUrl.clear();
    }
}

void MudletEngine::takeMapDownloadProgress(const QString& label, int value, int maximum, bool force)
{
    if (mMapDownload.state != MapDownload::State::downloading) {
        return;
    }
    mMapDownload.label = label;
    mMapDownload.value = value;
    mMapDownload.maximum = maximum;
    pushMapDownloadProgress(force);
}

void MudletEngine::pushMapDownloadProgress(bool force)
{
    const int percent = mMapDownload.maximum > 0 ? int(std::clamp(qint64(mMapDownload.value) * 100 / mMapDownload.maximum, qint64(0), qint64(100))) : -1;
    if (!force && percent == mMapDownloadPercent) {
        return;
    }
    mMapDownloadPercent = percent;
    mFrontend->mapDownload(mMapDownload);
}

void MudletEngine::setScreenSize(int columns, int rows)
{
    mScreenSize = QSize(columns, rows);
    if (mState == State::started) {
        applyScreenSize();
    }
}

void MudletEngine::applyScreenSize()
{
    if (mScreenSize.isEmpty()) {
        return;
    }
    if (!mSession->setScreenSize(mScreenSize.width(), mScreenSize.height())) {
        qWarning("MudletEngine: setScreenSize(%d, %d) refused: %s", mScreenSize.width(), mScreenSize.height(), qPrintable(mSession->errorString()));
    }
}

void MudletEngine::setAppState(bool active, bool minimized)
{
    mSession->setWindowMinimized(minimized);
    const bool leaving = mAppActive && !active;
    mAppActive = active;
    applyAppActive();
    // After sysApplicationFocusChangeEvent, so the save keeps what its handlers did
    if (leaving) {
        save();
    }
}

// THeadlessSession keeps it while no profile runs, and refuses it while one loads or closes
void MudletEngine::applyAppActive()
{
    if (mState == State::starting || mState == State::stopping || mSession->applicationActive() == mAppActive) {
        return;
    }
    if (!mSession->setApplicationActive(mAppActive)) {
        qWarning("MudletEngine: setApplicationActive(%s) refused: %s", mAppActive ? "true" : "false", qPrintable(mSession->errorString()));
    }
}

// The profile's save, as at a close but with no sysExitEvent, while it runs
void MudletEngine::save()
{
    if (mClosing || mState != State::started || mSaving) {
        return;
    }
    mSaving = true;
    mSession->saveAsync();
}

void MudletEngine::onSaveFinished(bool saved, const QString& error)
{
    mSaving = false;
    if (saved) {
        mFrontend->status(QStringLiteral("save"), QStringLiteral("saved"));
    } else {
        qWarning("MudletEngine: the profile was not saved: %s", qPrintable(error));
        mFrontend->status(QStringLiteral("save"), QStringLiteral("not saved: %1").arg(error));
    }
    // A switch asked for during the save
    if (!mClosing && mState == State::stopping) {
        mSession->shutdownAsync(true);
    }
}

namespace {
// As in libmudlet, "main" names the main console too, whose ids are offset
bool isMainConsole(const QString& window)
{
    return window.isEmpty() || window == QLatin1String("main");
}
} // namespace

QString MudletEngine::link(const QString& window, int id, Link& link) const
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
QString MudletEngine::followLink(const QString& window, int id, std::optional<int> item)
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

void MudletEngine::reportConsoleColors(const QColor& foreground, const QColor& background)
{
    if (foreground.isValid() && background.isValid()) {
        mFrontend->status(QStringLiteral("consoleColors"), QStringLiteral("%1 %2").arg(foreground.name(), background.name()));
    }
}

// A line as the main console shows it once its triggers ran. It stays open, as the tail, until the next line or
// piece of text that does not continue it, so that one that does can still be put on its end.
void MudletEngine::onLineShown(const QString& text, bool isPrompt, const QList<ColorRun>& runs, bool continuesLine)
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
void MudletEngine::onLocalText(const QString& text, const QList<ColorRun>& runs, bool continuesLine)
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

void MudletEngine::onMainConsoleCleared()
{
    mOpen = {};
    mDropLineFeed = false;
    mSentTail = {};
    mFrontend->cleared();
}

void MudletEngine::appendToOpen(const QString& text, const QList<ColorRun>& runs)
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
void MudletEngine::commitCompleteLines(OpenLine::Kind remainder)
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

void MudletEngine::commitOpen()
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
void MudletEngine::scheduleTailSync()
{
    if (mTailSyncPending) {
        return;
    }
    mTailSyncPending = true;
    QTimer::singleShot(0, this, &MudletEngine::syncTail);
}

void MudletEngine::syncTail()
{
    mTailSyncPending = false;
    if (mOpen.text == mSentTail.text && mOpen.runs == mSentTail.runs && mOpen.prompt == mSentTail.prompt) {
        return;
    }
    mSentTail = mOpen;
    mFrontend->tail(mSentTail.text, mSentTail.runs, mSentTail.prompt);
}

void MudletEngine::markCountersDirty()
{
    mCountersDirty = true;
    if (!mCounterTimer.isActive()) {
        mCounterTimer.start();
    }
}

void MudletEngine::flushCounters()
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

void MudletEngine::onHeartbeat()
{
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    const qint64 gap = now - mLastHeartbeatMs;
    mLastHeartbeatMs = now;
    ++mHeartbeatTicks;
    mMaxHeartbeatGapMs = std::max(mMaxHeartbeatGapMs, gap);
    mFrontend->status(QStringLiteral("heartbeatTicks"), QString::number(mHeartbeatTicks));
    mFrontend->status(QStringLiteral("heartbeatLastGapMs"), QString::number(gap));
    mFrontend->status(QStringLiteral("heartbeatMaxGapMs"), QString::number(mMaxHeartbeatGapMs));
}
