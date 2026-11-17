#pragma once

#include "IEngine.h"
#include "IFrontend.h"
#include "MapPng.h"
#include "Profile.h"
#include "SessionStore.h"

#include <QElapsedTimer>
#include <QList>
#include <QObject>
#include <QSize>
#include <QStringList>
#include <QTimer>

#include <deque>
#include <functional>
#include <memory>
#include <optional>

class MudletEngine;
class QColor;
class THeadlessSession;

// One profile of libmudlet's engine, in a THeadlessSession of its own that shares the process's pool of profiles with
// the others' (docs/multi-profile.md): what the host asks of that profile, and what it shows. MudletEngine opens,
// closes and restores these, and has what is the whole engine's. Only THeadlessSession's public API is used.
class MudletProfile : public QObject, public IEngine
{
    Q_OBJECT

public:
    // How the profile is to start, as openProfile asked
    struct Open
    {
        QString profile;
        QString password;
        bool connect = true;
        // As SessionStore::Session's: empty, 0 and unset for the profile's own
        QString host;
        quint16 port = 0;
        std::optional<bool> tls;
        QStringList lua;

        SessionStore::Session kept() const { return {profile, host, port, connect, tls}; }
    };

    // A child of the engine, so one whose deleteLater() the event loop has not reached goes with it
    MudletProfile(MudletEngine& engine, const QString& profile);
    ~MudletProfile() override;

    void connectTo(const QString& host, quint16 port, std::optional<bool> tls) override;
    void send(const QString& line) override;
    void sendHidden(const QString& line) override;
    void dismissHidden() override;
    QString complete(const QString& text, bool fromHistory) override;
    QStringList history() const override;
    void disconnectFromGame() override;
    void runLua(const QString& code) override;
    void setScreenSize(int columns, int rows) override;
    QString link(const QString& window, int id, Link& link) const override;
    QString followLink(const QString& window, int id, std::optional<int> item) override;
    QString setMainWindowSize(int width, int height) override;
    std::optional<WindowText> windowText(const QString& window, int first, int count) const override;
    QString placeUserWindow(const QString& name, const QRect& geometry, bool floating) override;
    QString closeUserWindow(const QString& name) override;
    QString sendWindowCommandLine(const QString& name, const QString& text) override;
    QString setTextBoxText(const QString& name, QString& text) override;
    QString selectFrameTab(const QString& name) override;
    QString followLabelLink(const QString& label, const QString& href) override;
    QString pointer(const QString& window, const PointerEvent& event) override;
    std::variant<PngImage, QString> image(const QString& path, const QSize& size, std::optional<int> frame) const override;
    QString pressButton(int id) override;
    QString activateCommand(int id) override;
    QString mapPointer(const PointerEvent& event) override;
    QString setMapViewOnly(bool viewOnly) override;
    QString setMapScale(double scale) override;
    QString setAppMapSize(const QSize& size, double scale) override;
    QString appMapPointer(const PointerEvent& event) override;
    std::variant<RoomList, QString> findRooms(const QString& text) const override;
    QString showRoom(int room) override;
    QString moveMap(MapTarget map, const QPointF& pan, double zoom, const QPointF& about, qint64 seq) override;
    QString followPlayer(MapTarget map) override;
    QString showMapLevel(MapTarget map, int level) override;
    QString showMapArea(MapTarget map, int area) override;
    std::variant<RoomDetails, QString> roomInfo(int room) const override;
    QString walkToRoom(int room) override;
    std::variant<QList<MapAreaInfo>, QString> mapAreas() const override;
    std::variant<RoomDetails, QString> editRoom(int room, const RoomEdit& edit) override;
    std::variant<RoomDetails, QString> setRoomExit(int room, const QString& direction, int to, bool bothWays) override;
    std::variant<RoomDetails, QString> lockRoomExit(int room, const QString& direction, bool locked) override;
    std::variant<RoomDetails, QString> addRoom(int beside, const QString& direction) override;
    QString deleteRoom(int room) override;
    QString setPlayerRoom(int room) override;
    QString renameArea(int area, const QString& name) override;
    std::variant<QList<MapEnvInfo>, QString> mapEnvs() const override;
    QString downloadMap() override;
    QString cancelMapDownload() override;
    QString answerSecureOffer(bool accept) override;
    std::variant<QVariantMap, QString> settings(const QStringList& keys) override;
    QString setSettings(const QVariantMap& values, QMap<QString, QString>& refused) override;
    std::variant<QList<ScriptItemInfo>, QString> scriptItems(ScriptItemKind kind) const override;
    std::variant<QVariantMap, QString> scriptItem(ScriptItemKind kind, int item) override;
    std::variant<int, QString> addScriptItem(ScriptItemKind kind, int parent, int position, bool folder, const QString& name) override;
    QString editScriptItem(ScriptItemKind kind, int item, const QVariantMap& fields) override;
    QString enableScriptItem(ScriptItemKind kind, int item, bool enabled) override;
    QString moveScriptItem(ScriptItemKind kind, int item, int parent, int position) override;
    QString deleteScriptItem(ScriptItemKind kind, int item) override;
    std::optional<LuaProblem> checkLua(const QString& code) const override;
    std::variant<QList<PackageInfo>, QString> packages() const override;
    QString installPackage(const QString& path) override;
    QString uninstallPackage(const QString& name) override;
    QList<ErrorConsoleEntry> errorConsole() const override;
    void clearErrorConsole() override;

    const QString& name() const { return mProfile; }
    // While it has not started: what a later connect or Lua starts
    void rename(const QString& profile);
    // Its name as not started, and disconnected, to the control socket, for a version 13 UI with no profile open
    void showIdle();
    bool idle() const { return mState == State::idle; }
    bool started() const { return mState == State::started; }
    bool stopping() const { return mState == State::stopping; }
    // Running or about to: neither closing nor closed
    bool running() const { return mState == State::starting || mState == State::started; }
    // Its start has called THeadlessSession's, whose load the other sessions of the pool are refused during
    bool loading() const { return mState == State::starting && mLoadBegun; }
    // Loaded, and not closing: the script windows' calls reach it
    bool loaded() const { return mLoaded && mState != State::stopping; }
    // Not started, though a restore brought it back: kept for a restart of the engine until it starts or is dropped
    bool awaitingRestore() const { return mState == State::idle && !mKept.profile.isEmpty(); }
    // What a restart of the engine is to bring back of it, if anything
    std::optional<SessionStore::Session> kept() const;
    void open(const Open& open);
    // The running profile, started without connecting or since disconnected, connects to its own address
    void connectProfile();
    // openProfile for the running profile: it takes the password for its next connection, an empty one for none,
    // and connects if connect
    void openAgain(const QString& password, bool connect);
    // What saveProfile stored for the running profile, for its next connection. addressChanged: its host, port or TLS
    // choice is not the one stored before, so a restart of the engine is to bring the profile back at the stored one
    void profileSaved(const Profile& stored, bool addressChanged);
    // Saves and closes it, as Mudlet's own close does, now or once its start is over; forSwitch, for the profile
    // openProfile opens in place of the others
    void close(bool forSwitch);
    void setRestore(const SessionStore::Session& session);
    // Starts it as the engine before this one ran it, after delayMs if it is to connect
    void scheduleRestore(int delayMs);
    void beginRestore();
    // The UI took over: the restore no longer reports, nor starts or connects it later
    void cancelRestore();
    // Not to be brought back by a restart of the engine, as the UI opened another in its place
    void dropRestore();
    void setWindowMinimized(bool minimized);
    THeadlessSession& session() { return *mSession; }
    // The close waited for the pool's save, which has ended
    void continueClose();
    // As the process ends: its sysExitEvent and save, waited for a few seconds at most
    void closeAtExit();
    // Raises sysProfileFocusChangeEvent, as a desktop tab change does; false if refused
    bool showInFront();

private:
    // stopping: the profile closes
    enum class State { idle, starting, started, stopping };
    // The bottom line of the main console, which may still grow: the last line lineShown sent, which an echo() after
    // deleteLine() or a command typed at a prompt continues, or what localText has written since its last line feed
    struct OpenLine
    {
        enum class Kind { none, shown, local };
        Kind kind = Kind::none;
        QString text;
        QList<ColorRun> runs;
        bool prompt = false;
    };

    void connectSession();
    // Now, or once no other profile of the pool loads, as THeadlessSession refuses this one's calls meanwhile
    void whenPoolFree(const std::function<void()>& action);
    // False, with status sendError, while the profile is not running to take what the player types
    bool takesInput();
    void requestShutdown();
    void onShutdownFinished(bool saved, const QString& error);
    bool takesChanges(const std::function<void()>& again);
    void applySaved();
    void applyPassword();
    void setSecureOffer(bool offered);
    // Why the running profile cannot be connected, with this TLS choice if one is given; empty if it can
    QString connectProblem(std::optional<bool> tls) const;
    // The profile has closed or did not start
    void closed();
    void restoreStatus(const QString& phase, const QString& detail = {});
    QString restoreTarget() const;
    bool profileReconnects() const;
    void start(const QString& host, quint16 port, bool connect, std::optional<bool> tls, const QString& password = {});
    void beginLoad();
    void onStartFinished(int result, const QString& error);
    void runNow(const QString& code);
    void applyScreenSize();
    void reportConsoleColors(const QColor& foreground, const QColor& background);
    void flushCounters();
    void markCountersDirty();
    void onProfileClosed();
    // A start that failed can leave the profile open in the session, which then takes no other start
    void renewSession();
    // The script windows, and with withText each console's last rows
    void pushWindows(bool withText);
    void pushWindowText(const QString& window);
    void pushMainArea();
    void pushButtonBars();
    void pushCommands();
    // The map drawn at the size of the mapper window, which is none while it does not show
    void pushMap(const std::optional<QSize>& mapperSize);
    // The map drawn at size, or none where it cannot be, with a warning unless size is empty
    std::optional<MapFrame> drawMap(const QSize& size);
    // The app's map drawn at the size the UI asked for, or none while no profile is open; nothing while it asks for none
    void pushAppMap();
    // The app's map at the size asked for, or none where it cannot be drawn, as for a map with no rooms, warned of
    std::optional<MapFrame> drawAppMap();
    void warnAppMapUndrawn(const QString& why);
    // What the last frame of that map laid out; none where it showed no area
    std::optional<MapViewInfo> mapView(MapTarget map) const;
    MapInfo mapInfo(MapTarget map) const;
    // Whether the game offers a map, at url (none if empty), into mMapDownload; a download keeps the host it is from
    void takeOfferedMap(const QString& url);
    // mMapDownload in state, with no progress, cancelable only while downloading, and error only for a failure
    void setMapDownloadState(MapDownload::State state, const QString& error = {});
    // Mudlet's progress, once the UI's download's own has started, into mMapDownload, and pushed as
    // pushMapDownloadProgress() does
    void takeMapDownloadProgress(const QString& label, int value, int maximum, bool force);
    // mMapDownload's progress, at most once a percent, as a download reports each piece that arrives; always with force
    void pushMapDownloadProgress(bool force);

    void onLineShown(const QString& text, bool isPrompt, const QList<ColorRun>& runs, bool continuesLine);
    void onLocalText(const QString& text, const QList<ColorRun>& runs, bool continuesLine);
    void onMainConsoleCleared();
    void appendToOpen(const QString& text, const QList<ColorRun>& runs);
    void commitCompleteLines(OpenLine::Kind remainder);
    void commitOpen();
    void scheduleTailSync();
    void syncTail();

    MudletEngine& mEngine;
    // While it runs its profile socket's, else the control socket's
    IFrontend* mFrontend;
    std::unique_ptr<THeadlessSession> mSession;
    QString mProfile;
    State mState = State::idle;
    // Its start has called THeadlessSession's
    bool mLoadBegun = false;
    // The start waiting for another profile's load to end
    QString mStartHost;
    quint16 mStartPort = 0;
    bool mStartConnect = false;
    std::optional<bool> mStartTls;
    QString mStartPassword;
    // close() while the profile started: it closes once started
    bool mCloseWhenStarted = false;
    // Closing for the profile openProfile opens in place of this one, which connect, disconnect and Lua are then for
    bool mForSwitch = false;
    // Its shutdownAsync() has been called and not yet answered
    bool mShutdownAsked = false;
    // The profile has loaded, since its start
    bool mLoaded = false;
    // The main window's size the UI gave, which the profile takes as it loads; empty for none, or once the profile
    // refused it. While it is set, applyScreenSize() tells the game the UI's console without resizing the main window
    QSize mMainWindowSize{0, 0};
    // The size of the mapper window as pushMap() last had it: none while it did not show
    std::optional<QSize> mMapSize;
    // Device pixels to each of the mapper window's, as setMapScale() asked
    double mMapScale = 1;
    // The mapper's and the app's map as last drawn
    MapPicture mMapPicture;
    MapPicture mAppMapPicture;
    // The seq of the last moveMap() each map took, which the frames drawn since carry
    qint64 mMapMoved = 0;
    qint64 mAppMapMoved = 0;
    // The app's map as setAppMapSize() asked for it: empty for none
    QSize mAppMapSize{0, 0};
    double mAppMapScale = 1;
    // Why the app's map was last not drawn, since it was last drawn: warned of once
    QString mAppMapUndrawn;
    // The map the game offers, and the download of it, as the engine knows it: progress within a percent of what was
    // last sent is held back (pushMapDownloadProgress())
    MapDownload mMapDownload;
    Sounds mSounds;
    // The URL being downloaded, as the game may offer another meanwhile: for its password to mask, never sent or logged
    QString mMapDownloadUrl;
    // The percentage of the download last sent, -1 while its size is unknown
    int mMapDownloadPercent = -1;
    // cancelMapDownload() stopped the download, so its end is a cancel rather than a failure
    bool mMapDownloadCancelled = false;
    // The game's offer of its secure port waits for the player's answer
    bool mSecureOffer = false;
    // The download's own progress has started: Mudlet's progress before then is the end of a map import or export a
    // script began
    bool mMapDownloadProgressStarted = false;
    // The TLS choice the running profile connects with: the one it was started with, unset when that start made
    // none, then the one of a saved profile once Mudlet has taken it
    std::optional<bool> mProfileTls;
    // The saved profile and a password for the running profile that THeadlessSession has yet to take, as it refuses
    // them while the profile, or another of the pool, loads
    std::optional<Profile> mSaved;
    std::optional<QString> mNewPassword;
    // Why Mudlet refused the address last saved for the running profile, which keeps the one it had
    QString mAddressProblem;
    // Lua (connects included) asked for while the profile starts, run once it has
    QStringList mPendingLua;
    QString mErrorPending;
    std::deque<ErrorConsoleEntry> mErrorConsole;
    qint64 mErrorConsoleCount = 0;
    qsizetype mErrorConsoleChars = 0;

    // What a restart of the engine would bring back of this profile while it runs, or awaits its restore
    SessionStore::Session mKept;
    // The UI asked for a disconnect, so the disconnected that follows does not leave the connection wanted
    bool mDisconnectRequested = false;
    // From the restore's start until it has connected, or found it will not
    bool mRestoring = false;
    // This start is a restore's, which the next profile to restore waits for
    bool mRestoreStart = false;
    // Holds back a restore that is to wait before it connects
    QTimer mRestoreTimer;

    OpenLine mOpen;
    // localText's next piece starts with the line feed that ends the line it left open, which lineShown has
    // since ended by sending a line below it
    bool mDropLineFeed = false;
    bool mTailSyncPending = false;
    // What the frontend was last sent as the tail; its kind is not used
    OpenLine mSentTail;

    // The highest link id the engine had sent before this profile started, added to its ids, which count from 1 again,
    // so that an id on an earlier profile's line, which a version 13 UI keeps, names none of this profile's links
    int mLinkBase = 0;

    // The UI's last setScreenSize, given to the profile as it loads; empty until then
    QSize mScreenSize;

    QTimer mCounterTimer;
    qint64 mLinesReceived = 0;
    qint64 mPromptsReceived = 0;
    qint64 mLocalLines = 0;
    qint64 mErrorLines = 0;
    bool mCountersDirty = false;
    QElapsedTimer mStartClock;
};
