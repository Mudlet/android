#pragma once

#include "IEngineControl.h"
#include "IEngineHost.h"
#include "MudletProfile.h"
#include "ProfileStore.h"
#include "SessionStore.h"

#include <QList>
#include <QObject>
#include <QPair>
#include <QPointer>
#include <QSize>
#include <QStringList>
#include <QTimer>

#include <functional>
#include <memory>
#include <optional>

// libmudlet behind the host protocol: each open profile a MudletProfile, in a THeadlessSession of
// its own that shares the process's pool of profiles (docs/multi-profile.md). Profiles start by openProfile, on a
// version 13 UI's first connect or Lua call, or at start-up to restore those the engine before it ran when that engine
// was killed. What is the whole engine's is here: the profiles' store, the app's state, the save and the restore.
class MudletEngine : public QObject, public IEngineControl
{
    Q_OBJECT

public:
    explicit MudletEngine(IEngineHost* host, int heartbeatMs = 30000, QObject* parent = nullptr);
    ~MudletEngine() override;

    void setAppState(bool active, bool minimized) override;
    void save() override;
    QList<Profile> profiles() const override { return mProfiles.profiles(); }
    QString saveProfile(const Profile& profile) override;
    QString deleteProfile(const QString& name) override;
    QString openProfile(const QString& name, const QString& password, bool connect, bool alongside) override;
    QString closeProfile(const QString& name) override;
    IEngine* defaultSession() override;
    bool restorePending() const override;
    void setFront(const QString& name) override;

private:
    friend class MudletProfile;

    // The UI's sizes as it last gave them, which a profile takes as it starts
    struct UiSizes
    {
        QSize screen;
        QSize mainWindow{0, 0};
        double mapScale = 1;
        QSize appMap{0, 0};
        double appMapScale = 1;
    };

    void takeRestore();
    // The next profile the restore brings back, staggerMs after the one before it loaded
    void restoreNext();
    // The front profile's restore, or one after it, has begun loading and ended, or was called off before it began
    void restoreEnded();
    void cancelRestores();
    void restoreStatus(const QString& phase, const QString& detail = {});
    void persist();
    bool inUse(const QString& folder) const;
    MudletProfile* openNamed(const QString& name) const;
    // A profile not yet started, for name: the idle one where it is free, else a new one
    MudletProfile* freeProfile(const QString& name);
    void openPending();
    void reportFont();
    void onHeartbeat();
    void applyAppActive();
    // setActiveProfile() for the front profile, which raises sysProfileFocusChangeEvent, once it has started
    void applyFront();
    bool anyLoading() const;
    bool loadingOther(const MudletProfile* profile) const;
    // action, run once no profile of the pool loads, while owner lives
    void later(QObject* owner, const std::function<void()>& action);

    // What the profiles tell
    void profileStarting(MudletProfile* profile);
    void loadEnded(bool restoreStart);
    void closeRefused();
    void profileClosed(MudletProfile* profile);
    void saveFinished(MudletProfile* profile, bool saved, const QString& error);

    IEngineHost* mHost;
    // MUDLET_PROFILE, else Android: what a version 13 UI's connect and Lua start when nothing has named another
    QString mDefaultProfile;
    QString mConfigDir;
    ProfileStore mProfiles;
    // Starting, running or closing, in the order opened
    QList<MudletProfile*> mOpen;
    // Not started: what a version 13 UI's requests reach while no profile is open, and the front profile a restore is
    // to bring back; null while neither is wanted
    MudletProfile* mIdle = nullptr;
    // The other profiles the restore brings back, in turn
    QList<MudletProfile*> mRestoreQueue;
    QTimer mRestoreChain;
    // A profile to start once the others have closed, as openProfile asked without alongside. While there is one, a
    // version 13 UI's connect, disconnect and Lua are for it.
    std::optional<MudletProfile::Open> mPendingOpen;
    // The profile the player looks at, and the one setActiveProfile() last brought forward
    QString mFront;
    QString mAppliedFront;
    bool mFrontPending = false;
    // Actions waiting for a load to end, which THeadlessSession refuses the pool's other calls during
    QList<QPair<QPointer<QObject>, std::function<void()>>> mDeferred;

    std::unique_ptr<SessionStore> mStore;
    QList<SessionStore::Session> mSavedSessions;
    QString mSavedFront;
    // The engine is stopping in order: nothing more is written for a restore
    bool mClosing = false;

    UiSizes mUi;
    // The highest link id sent on a main console, which a profile that starts counts its own from
    int mLinkTop = 0;
    // The UI's last setAppState; active is true before the first, as in THeadlessSession
    bool mAppActive = true;
    bool mMinimized = false;
    // The profile a saveAsync() was asked of, until it answers; a shutdown would be refused meanwhile
    QPointer<MudletProfile> mSaver;

    QTimer mHeartbeat;
    qint64 mLastHeartbeatMs = 0;
    qint64 mHeartbeatTicks = 0;
    qint64 mMaxHeartbeatGapMs = 0;
};
