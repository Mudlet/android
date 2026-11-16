#pragma once

#include "IEngineControl.h"
#include "IEngineHost.h"

#include <QList>
#include <QObject>
#include <QTimer>

class Engine;

// The stand-in engine: its profiles, kept in a JSON file, each open one run by an Engine of its own, as many at once as
// the host allows
class StandIn : public QObject, public IEngineControl
{
    Q_OBJECT

public:
    // heartbeatMs is 30000 in the app; tests shorten it
    StandIn(IEngineHost* host, int heartbeatMs, const QString& profilesPath, QObject* parent = nullptr);

    // Nothing to tell a game, and its profiles are written as they change
    void setAppState(bool, bool) override {}
    void save() override {}
    QList<Profile> profiles() const override { return mProfiles; }
    QString saveProfile(const Profile& profile) override;
    QString deleteProfile(const QString& name) override;
    // The stand-in has no login, so the password goes nowhere
    QString openProfile(const QString& name, const QString& password, bool connect, bool alongside) override;
    QString closeProfile(const QString& name) override;
    IEngine* defaultSession() override;
    bool restorePending() const override { return false; }

private:
    struct OpenProfile
    {
        QString name;
        Engine* session = nullptr;
    };

    void reportFont();
    void onHeartbeat();
    void loadProfiles();
    QString storeProfiles(const QList<Profile>& profiles);
    qsizetype profileIndex(const QString& name) const;
    qsizetype openIndex(const QString& name) const;
    void closeAt(qsizetype index);

    IEngineHost* mHost;
    QString mProfilesPath;
    // The last opened first; a new one goes first too
    QList<Profile> mProfiles;
    // In the order opened
    QList<OpenProfile> mOpen;
    // While no profile is open
    Engine* mDefault = nullptr;

    QTimer mHeartbeat;
    qint64 mLastHeartbeatMs = 0;
    qint64 mHeartbeatTicks = 0;
    qint64 mMaxHeartbeatGapMs = 0;
};
