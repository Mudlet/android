#pragma once

#include "IFrontend.h"

#include <QByteArray>
#include <QHash>
#include <QJsonArray>
#include <QJsonObject>
#include <QMap>
#include <QObject>
#include <QPointer>
#include <QStringList>
#include <deque>
#include <optional>
#include <variant>

class ControlBridge;
class IEngine;
class QLocalServer;
class QLocalSocket;

// IFrontend over a QLocalServer. Wire format: 4-byte big-endian length, then
// one UTF-8 JSON object with a "t" type field. See docs/protocol.md for the messages.
//
// The engine never waits for the UI: Android freezes a cached UI process, so a
// UI that stops reading only stops getting lines. They stay in a ring buffer and
// are sent, with a "gap" marker if the ring overflowed, once the UI drains.
//
// One serves each open profile's socket; ControlBridge, the control socket, is one too.
class SocketBridge : public QObject, public IFrontend
{
    Q_OBJECT

public:
    static constexpr int protocolVersion = 39;
    static constexpr quint32 maxFrameBytes = 16 * 1024 * 1024;
    static constexpr qsizetype scrollbackLines = 2000;
    static constexpr qint64 highWaterBytes = 4 * 1024 * 1024;
    static constexpr qint64 lowWaterBytes = 256 * 1024;
    // The most rows one getWindowText is answered with
    static constexpr int maxWindowTextRows = 1000;

    // A profile's socket, owned by control, which hears of its lines, connection and attention; else, with none, the
    // control socket's
    explicit SocketBridge(QObject* parent = nullptr, ControlBridge* control = nullptr, const QString& profile = {}, int slot = 0);
    void setEngine(IEngine* engine) { mEngine = engine; }
    IEngine* engine() const { return mEngine; }
    bool listen(const QString& path);
    // Drops the UI and removes the socket, as the profile has closed
    void close();
    // The control socket's bridge while this profile is the front one, which a UI that left out profileSockets hears
    // through, as one socket served it in version 13
    void setMirror(SocketBridge* mirror) { mMirror = mirror; }
    const QString& profileName() const { return mProfileName; }
    int slot() const { return mSlot; }
    QString socketPath() const;
    qint64 lastSeq() const { return mNextSeq - 1; }
    QString connection() const { return mStatus.value(QStringLiteral("connection")); }
    const QHash<QString, QString>& statuses() const { return mStatus; }
    // How many messages every bridge has made of map frames so far, which nothing but its test reads
    // (tests/map_frames_test.cpp): a frame given again is to have none made of it
    static int mapMessagesMade() { return sMapMessagesMade; }

    void line(const QString& text, const QList<ColorRun>& runs, bool isPrompt) override;
    void tail(const QString& text, const QList<ColorRun>& runs, bool isPrompt) override;
    void cleared() override;
    void notify(const QString& title, const QString& text) override;
    void status(const QString& key, const QString& value) override;
    void hiddenInput(bool wanted) override;
    void history(const QStringList& lines) override;
    void commandLine(const QString& text) override;
    void announce(const QString& text, bool assertive) override;
    void linksChanged(const QList<int>& ids) override;
    void attention(int ms) override;
    void moveTypedLine() override;
    void windows(const QList<ScriptWindowInfo>& windows, const QList<LabelInfo>& labels, const QList<TextBoxInfo>& textBoxes) override;
    void windowText(const QString& window, const WindowText& text) override;
    void mainArea(const MainArea& area) override;
    void windowCommandLine(const QString& name, const QString& text) override;
    void buttonBars(const QList<ButtonBar>& bars) override;
    void commands(const QList<AddonCommandInfo>& commands) override;
    void keyBindings(const QList<KeyBinding>& bindings) override;
    void map(const std::optional<MapFrame>& frame) override;
    void appMap(const std::optional<MapFrame>& frame) override;
    void mapDownload(const MapDownload& download) override;
    void mapFile(const MapFile& file) override;
    void sounds(const Sounds& sounds) override;
    void packagesChanged() override;
    void packageInstalled(const QString& path, PackageInstallResult result, const QString& error) override;
    void errorConsoleEntry(const ErrorConsoleEntry& entry) override;
    void errorConsoleCleared() override;
    void secureOffer(bool offered) override;
    void certificateRefused(const std::optional<RefusedCertificate>& certificate) override;
    void scriptsStopped(bool stopped) override;
    void replayRecording(bool recording, const QString& file) override;

protected:
    struct StoredLine
    {
        qint64 seq;
        QString text;
        QList<ColorRun> runs;
        bool prompt;
    };

    void onNewConnection();
    void onReadyRead();
    void onBytesWritten();
    // Whether a cancelMapFile has come on this socket, which it takes out of the frames still to be handled
    bool mapFileCancelAsked();
    // The first frame of type, with id if one is given, among those still to be handled, taken out of them; the socket
    // is read for up to waitMs while none has come. The others wait for the event loop.
    std::optional<QJsonObject> takeFrame(QLatin1StringView type, const QJsonValue& id, int waitMs);
    void handleMessage(const QJsonObject& message);
    // True once the request is answered here: one for the whole engine, or a refusal of a request sent on the wrong
    // socket; false to pass it to the profile
    virtual bool handleControl(const QString& type, const QJsonObject& message);
    virtual void greet(const QJsonObject& hello);
    void sendLinesAfter(qint64 seq, bool replay);
    // Every status, then the input line's state and the script windows', as a UI attaching or catching up needs them
    virtual void sendState();
    void sendNotify(const QString& title, const QString& text);
    void sendFrame(const QJsonObject& message);
    // An answer to the request being handled: it carries that request's id as re, and is sent even to a lagging UI
    void sendReply(QJsonObject message);
    void sendError(const QString& message);
    // An error naming the request, for a problem that is not empty
    void refuse(const QString& request, const QString& problem);
    void complete(const QJsonObject& message);
    void setScreenSize(const QJsonObject& message);
    void getLink(const QJsonObject& message);
    void followLink(const QJsonObject& message);
    QString setMainWindowSize(const QJsonObject& message);
    void getWindowText(const QJsonObject& message);
    QString placeUserWindow(const QJsonObject& message);
    QString pointer(const QJsonObject& message);
    void setTextBoxText(const QJsonObject& message);
    QString mapPointer(const QJsonObject& message);
    QString setAppMapSize(const QJsonObject& message);
    QString moveMap(const QJsonObject& message);
    void findRooms(const QJsonObject& message);
    void getSettings(const QJsonObject& message);
    void setSettings(const QJsonObject& message);
    // The settings message answering request: the values of keys as the engine now holds them, and those refused
    void sendSettings(const QString& request, const QStringList& keys, const QMap<QString, QString>& refused);
    void roomInfo(const QJsonObject& message);
    // A room, as the answer to request, or an error naming request
    void sendRoom(const QString& request, const std::variant<RoomDetails, QString>& found);
    void mapAreas();
    void editRoom(const QJsonObject& message);
    void setRoomExit(const QJsonObject& message);
    void lockRoomExit(const QJsonObject& message);
    void addRoom(const QJsonObject& message);
    QString renameArea(const QJsonObject& message);
    void mapEnvs();
    void packages();
    void uninstallPackage(const QJsonObject& message);
    void scriptItems(const QJsonObject& message);
    void scriptItem(const QJsonObject& message);
    void addScriptItem(const QJsonObject& message);
    void editScriptItem(const QJsonObject& message);
    void enableScriptItem(const QJsonObject& message);
    QString moveScriptItem(const QJsonObject& message);
    QString deleteScriptItem(const QJsonObject& message);
    void searchScriptItems(const QJsonObject& message);
    void checkLua(const QJsonObject& message);
    void pressKey(const QJsonObject& message);
    // The item of message's kind, as the answer to request, or an error naming request
    void sendScriptItem(const QString& request, const QJsonObject& message, int item);
    void getImage(const QJsonObject& message);
    // Sent whole, and only when it differs from what was kept, which a UI that attaches or drains gets again
    void sendIfChanged(QJsonObject& kept, QJsonObject message);
    void dropClient(const QString& reason);
    void forgetClient();
    bool clientLive() const { return mClient && mClientGreeted && !mClientLagging; }
    // A profile's output goes to this UI: a profile socket's, or the control socket's that left out profileSockets
    bool outputLive() const { return clientLive() && mProfileOutput; }
    bool replying() const { return !mCurrentRequestId.isUndefined() && !mCurrentRequestId.isNull(); }
    static QJsonObject lineMessage(const StoredLine& stored, bool replay);
    static QJsonArray runsJson(const QList<ColorRun>& runs);
    QJsonObject tailMessage() const;
    QJsonObject hiddenInputMessage() const;
    QJsonObject secureOfferMessage() const;
    QJsonObject scriptsStoppedMessage() const;
    QJsonObject replayRecordingMessage() const;
    QJsonObject certificateMessage() const;
    static QJsonObject historyMessage(const QStringList& lines);
    static QJsonObject linksChangedMessage(const QList<int>& ids);
    static QJsonObject windowJson(const ScriptWindowInfo& window);
    static QJsonObject labelMessage(const LabelInfo& label);
    QJsonObject textBoxMessage(const TextBoxInfo& textBox) const;
    static QJsonObject windowTextMessage(const QString& window, const WindowText& text);
    static QJsonObject mainAreaMessage(const MainArea& area);
    static QJsonArray buttonsJson(const QList<BarButton>& buttons);
    static QJsonObject buttonBarsMessage(const QList<ButtonBar>& bars);
    static QJsonObject commandsMessage(const QList<AddonCommandInfo>& commands);
    // The error console's entries: all it holds for full, else ones to add to those
    static QJsonObject errorConsoleMessage(const QList<ErrorConsoleEntry>& entries, bool full);
    static QJsonObject keyBindingsMessage(const QList<KeyBinding>& bindings);
    // The fields of a map or appMap message, all but its type; empty for a frame too large to send
    static QJsonObject mapMessage(const MapFrame& frame);
    static QJsonObject mapViewMessage(const MapViewInfo& view);
    static QJsonObject mapInfoMessage(const MapInfo& info);
    static inline int sMapMessagesMade = 0;

    ControlBridge* mControl = nullptr;
    SocketBridge* mMirror = nullptr;
    // Empty and 0 for the control socket
    QString mProfileName;
    int mSlot = 0;
    bool mProfileOutput = true;
    QLocalServer* mServer = nullptr;
    QPointer<QLocalSocket> mClient;
    bool mClientGreeted = false;
    bool mClientLagging = false;
    qint64 mLastSentSeq = 0;
    QByteArray mReadBuffer;
    bool mTakingFrame = false;
    bool mReadQueued = false;
    IEngine* mEngine = nullptr;
    QHash<QString, QString> mStatus;
    bool mHiddenInput = false;
    bool mSecureOffer = false;
    bool mScriptsStopped = false;
    bool mReplayRecording = false;
    QString mRecordedReplay;
    std::optional<RefusedCertificate> mRefusedCertificate;
    // A linksChanged went to no live UI, so the next to attach or catch up is told any link may have changed
    bool mLinksMissed = false;
    // As IFrontend::windows() last gave them
    QList<ScriptWindowInfo> mWindows;
    QList<LabelInfo> mLabels;
    // As IFrontend::windows() last gave them, with what Mudlet kept of the text the UI sent since, so it is not sent back
    QList<TextBoxInfo> mTextBoxes;
    // How many setTextBoxText this UI has sent for each text box, by its name, which each textBox carries
    QHash<QString, int> mTextBoxTyped;
    // None until the engine has sent one
    std::optional<MainArea> mMainArea;
    // As IFrontend::buttonBars(), commands() and keyBindings() last gave them, as sent
    QJsonObject mButtonBars = buttonBarsMessage({});
    QJsonObject mCommands = commandsMessage({});
    QJsonObject mKeyBindings = keyBindingsMessage({});
    // A map's message as last sent, and the frame it was made for, none where that was no frame: the engine draws
    // the map again for much that leaves the frame as it was, and that frame would make that message again
    struct KeptMap
    {
        std::optional<MapFrame> frame;
        QJsonObject message;
    };
    // The map message last sent, empty while no mapper shows
    KeptMap mMap;
    // The appMap message last sent to this UI, empty until the engine has drawn the app's map or said there is nothing to
    // draw
    KeptMap mAppMap;
    // The mapDownload message last sent, empty until the engine has sent one
    QJsonObject mMapDownload;
    // The sounds message last sent, empty until the engine has sent one
    QJsonObject mSounds;
    std::deque<StoredLine> mScrollback;
    StoredLine mTail{0, {}, {}, false};
    qint64 mNextSeq = 1;
    // The last line sent before the console was last emptied, -1 for never: a UI with no line after it is told
    qint64 mClearedAfterSeq = -1;
    // Distinguishes a restarted engine, whose sequence numbers start again
    QString mInstance;
    // Request id of the command being executed, echoed on the status frames it causes
    QJsonValue mCurrentRequestId;
};
