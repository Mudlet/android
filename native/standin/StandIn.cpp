#include "StandIn.h"

#include "Engine.h"

#include <QDateTime>
#include <QFile>
#include <QFont>
#include <QFontDatabase>
#include <QFontInfo>
#include <QFontMetrics>
#include <QGuiApplication>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSaveFile>

#include <algorithm>

StandIn::StandIn(IEngineHost* host, int heartbeatMs, const QString& profilesPath, QObject* parent)
: QObject(parent)
, mHost(host)
, mProfilesPath(profilesPath)
{
    loadProfiles();
    reportFont();
    mLastHeartbeatMs = QDateTime::currentMSecsSinceEpoch();
    mHeartbeat.setInterval(heartbeatMs);
    connect(&mHeartbeat, &QTimer::timeout, this, &StandIn::onHeartbeat);
    mHeartbeat.start();
}

IEngine* StandIn::defaultSession()
{
    if (!mDefault) {
        mDefault = new Engine(this);
        mDefault->start(mHost->controlFrontend());
    }
    return mDefault;
}

QString StandIn::openProfile(const QString& name, const QString&, bool connect, bool alongside)
{
    const qsizetype index = profileIndex(name);
    if (index < 0) {
        return QStringLiteral("there is no profile named \"%1\"").arg(name);
    }
    QList<Profile> profiles = mProfiles;
    const Profile profile = profiles.takeAt(index);
    profiles.prepend(profile);
    if (const QString problem = storeProfiles(profiles); !problem.isEmpty()) {
        return problem;
    }
    Engine* session = nullptr;
    if (const qsizetype open = openIndex(profile.name); open >= 0) {
        session = mOpen.at(open).session;
        if (connect && session->unconnected()) {
            session->connectTo(profile.host, quint16(profile.port), profile.tls);
        }
    } else {
        session = new Engine(this);
        mOpen.append({profile.name, session});
        IFrontend* frontend = mHost->profileOpened(profile.name, session, IEngineHost::ProfileState::starting);
        session->start(frontend);
        frontend->status(QStringLiteral("profile"), QStringLiteral("%1 loaded").arg(profile.name));
        mHost->profileState(profile.name, IEngineHost::ProfileState::loaded);
        if (connect) {
            session->connectTo(profile.host, quint16(profile.port), profile.tls);
        }
    }
    for (qsizetype i = mOpen.size() - 1; i >= 0; --i) {
        if (mOpen.at(i).session != session) {
            closeAt(i);
        }
    }
    if (mDefault) {
        mDefault->close();
        mDefault->deleteLater();
        mDefault = nullptr;
    }
    return {};
}

QString StandIn::closeProfile(const QString& name)
{
    const qsizetype index = openIndex(name);
    if (index < 0) {
        return QStringLiteral("\"%1\" is not open").arg(name);
    }
    closeAt(index);
    return {};
}

void StandIn::closeAt(qsizetype index)
{
    const OpenProfile open = mOpen.takeAt(index);
    open.session->close();
    mHost->profileClosed(open.name);
    open.session->deleteLater();
}

qsizetype StandIn::openIndex(const QString& name) const
{
    const auto open = std::find_if(mOpen.cbegin(), mOpen.cend(), [&name](const OpenProfile& o) {
        return o.name.compare(name, Qt::CaseInsensitive) == 0;
    });
    return open == mOpen.cend() ? -1 : open - mOpen.cbegin();
}

void StandIn::reportFont()
{
    if (!qobject_cast<QGuiApplication*>(QCoreApplication::instance())) {
        mHost->engineStatus(QStringLiteral("fontError"), QStringLiteral("no QGuiApplication"));
        return;
    }
    QFont font = QFontDatabase::systemFont(QFontDatabase::FixedFont);
    font.setPixelSize(14);
    mHost->engineStatus(QStringLiteral("fontFamily"), QFontInfo(font).family());
    mHost->engineStatus(QStringLiteral("advanceM"), QString::number(QFontMetrics(font).horizontalAdvance(QLatin1Char('M'))));
    mHost->engineStatus(QStringLiteral("platform"), QGuiApplication::platformName());
}

void StandIn::onHeartbeat()
{
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    const qint64 gap = now - mLastHeartbeatMs;
    mLastHeartbeatMs = now;
    ++mHeartbeatTicks;
    mMaxHeartbeatGapMs = std::max(mMaxHeartbeatGapMs, gap);
    mHost->engineStatus(QStringLiteral("heartbeatTicks"), QString::number(mHeartbeatTicks));
    mHost->engineStatus(QStringLiteral("heartbeatLastGapMs"), QString::number(gap));
    mHost->engineStatus(QStringLiteral("heartbeatMaxGapMs"), QString::number(mMaxHeartbeatGapMs));
}

// {"v": 1, "profiles": [{"name", "host", "port", "tls", "login", "autoReconnect"}, ...]}, the last opened first
void StandIn::loadProfiles()
{
    QFile file(mProfilesPath);
    if (!file.exists()) {
        return;
    }
    if (!file.open(QIODevice::ReadOnly)) {
        qWarning("StandIn: cannot read the profiles in %s: %s", qPrintable(mProfilesPath), qPrintable(file.errorString()));
        return;
    }
    QJsonParseError error{};
    const QJsonDocument doc = QJsonDocument::fromJson(file.readAll(), &error);
    if (!doc.isObject()) {
        qWarning("StandIn: ignoring the unreadable profiles in %s: %s", qPrintable(mProfilesPath), qPrintable(error.errorString()));
        return;
    }
    for (const QJsonValue& value : doc.object().value(QLatin1String("profiles")).toArray()) {
        const QJsonObject json = value.toObject();
        const Profile profile{json.value(QLatin1String("name")).toString(),
                              json.value(QLatin1String("host")).toString(),
                              json.value(QLatin1String("port")).toInt(23),
                              json.value(QLatin1String("tls")).toBool(),
                              json.value(QLatin1String("login")).toString(),
                              json.value(QLatin1String("autoReconnect")).toBool()};
        if (Profile::newNameProblem(profile.name).isEmpty() && profileIndex(profile.name) < 0) {
            mProfiles.append(profile);
        }
    }
}

// Kept in memory only once on disk, so the UI is never shown a profile a restart would lose
QString StandIn::storeProfiles(const QList<Profile>& profiles)
{
    QJsonArray list;
    for (const Profile& profile : profiles) {
        list.append(QJsonObject{{QStringLiteral("name"), profile.name},
                                {QStringLiteral("host"), profile.host},
                                {QStringLiteral("port"), profile.port},
                                {QStringLiteral("tls"), profile.tls},
                                {QStringLiteral("login"), profile.login},
                                {QStringLiteral("autoReconnect"), profile.autoReconnect}});
    }
    QSaveFile file(mProfilesPath);
    if (!file.open(QIODevice::WriteOnly) || file.write(QJsonDocument(QJsonObject{{QStringLiteral("v"), 1}, {QStringLiteral("profiles"), list}}).toJson()) < 0 || !file.commit()) {
        return QStringLiteral("could not store the profiles in %1: %2").arg(mProfilesPath, file.errorString());
    }
    mProfiles = profiles;
    return {};
}

qsizetype StandIn::profileIndex(const QString& name) const
{
    for (qsizetype i = 0; i < mProfiles.size(); ++i) {
        if (mProfiles.at(i).name.compare(name, Qt::CaseInsensitive) == 0) {
            return i;
        }
    }
    return -1;
}

QString StandIn::saveProfile(const Profile& profile)
{
    QList<Profile> profiles = mProfiles;
    const qsizetype index = profileIndex(profile.name);
    if (index >= 0) {
        // Under the name it was created with, as libmudlet keeps its folder's
        Profile updated = profile;
        updated.name = profiles.at(index).name;
        profiles[index] = updated;
    } else if (const QString problem = Profile::newNameProblem(profile.name); !problem.isEmpty()) {
        return problem;
    } else {
        profiles.prepend(profile);
    }
    return storeProfiles(profiles);
}

QString StandIn::deleteProfile(const QString& name)
{
    const qsizetype index = profileIndex(name);
    if (index < 0) {
        return QStringLiteral("there is no profile named \"%1\"").arg(name);
    }
    if (const qsizetype open = openIndex(name); open >= 0) {
        return QStringLiteral("\"%1\" is open: open another profile before deleting it").arg(mOpen.at(open).name);
    }
    QList<Profile> profiles = mProfiles;
    profiles.removeAt(index);
    return storeProfiles(profiles);
}
