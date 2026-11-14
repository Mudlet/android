#include "SessionStore.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSaveFile>

namespace {

constexpr int fileVersion = 1;

} // namespace

SessionStore::SessionStore(const QString& path, const QString& stopMarkerPath)
: mPath(path)
, mStopMarkerPath(stopMarkerPath)
{
}

SessionStore::Restore SessionStore::takeRestore(qint64 nowMs)
{
    // The user stopped the engine before this one: whatever it left is not to come back
    if (QFile::exists(mStopMarkerPath)) {
        clear();
        if (!QFile::remove(mStopMarkerPath)) {
            qWarning("SessionStore: could not remove the stop marker %s", qPrintable(mStopMarkerPath));
        }
        return {};
    }
    QFile file(mPath);
    if (!file.open(QIODevice::ReadOnly)) {
        return {};
    }
    QJsonParseError error{};
    const QJsonObject json = QJsonDocument::fromJson(file.readAll(), &error).object();
    if (error.error != QJsonParseError::NoError || json.value(QLatin1String("v")).toInt() != fileVersion) {
        qWarning("SessionStore: ignoring %s: %s", qPrintable(mPath),
                 error.error != QJsonParseError::NoError ? qPrintable(error.errorString()) : "an unknown version");
        return {};
    }
    for (const QJsonValue& at : json.value(QLatin1String("restores")).toArray()) {
        const qint64 ms = qint64(at.toDouble());
        if (ms > nowMs - restoreWindowMs && ms <= nowMs) {
            mRestores.append(ms);
        }
    }
    Restore restore;
    restore.session = {json.value(QLatin1String("profile")).toString(), json.value(QLatin1String("host")).toString(),
                       json.value(QLatin1String("port")).toInt(), json.value(QLatin1String("connect")).toBool()};
    if (restore.session.profile.isEmpty()) {
        return {};
    }
    if (mRestores.size() >= maxRestores) {
        restore.kind = Restore::Kind::gaveUp;
        restore.attempt = int(mRestores.size());
        clear();
        return restore;
    }
    mRestores.append(nowMs);
    restore.kind = Restore::Kind::restore;
    restore.attempt = int(mRestores.size());
    restore.reconnectDelayMs = reconnectDelayMs[restore.attempt - 1];
    write(restore.session);
    return restore;
}

void SessionStore::save(const Session& session)
{
    write(session);
}

void SessionStore::clear()
{
    mRestores.clear();
    if (QFile::exists(mPath) && !QFile::remove(mPath)) {
        qWarning("SessionStore: could not remove %s", qPrintable(mPath));
    }
}

bool SessionStore::write(const Session& session)
{
    QJsonArray restores;
    for (const qint64 ms : std::as_const(mRestores)) {
        restores.append(ms);
    }
    const QJsonObject json{{QStringLiteral("v"), fileVersion},          {QStringLiteral("profile"), session.profile},
                           {QStringLiteral("host"), session.host},      {QStringLiteral("port"), session.port},
                           {QStringLiteral("connect"), session.connect}, {QStringLiteral("restores"), restores}};
    QDir().mkpath(QFileInfo(mPath).absolutePath());
    // Written beside the file and renamed over it, so a kill part-way leaves the last whole one
    QSaveFile file(mPath);
    if (!file.open(QIODevice::WriteOnly) || file.write(QJsonDocument(json).toJson(QJsonDocument::Compact)) < 0 || !file.commit()) {
        qWarning("SessionStore: could not write %s: %s", qPrintable(mPath), qPrintable(file.errorString()));
        return false;
    }
    return true;
}
