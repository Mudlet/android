#include "ProfileStore.h"

#include <QDataStream>
#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QSaveFile>

#include <algorithm>

namespace {

// As Mudlet stores a check box: the number of a Qt::CheckState
QString checkState(bool checked)
{
    return QString::number(checked ? Qt::Checked : Qt::Unchecked);
}

} // namespace

ProfileStore::ProfileStore(const QString& configDir)
: mProfilesDir(QDir(configDir).filePath(QStringLiteral("profiles")))
, mOrderPath(QDir(configDir).filePath(QStringLiteral("profile-order.json")))
{
}

QList<Profile> ProfileStore::profiles() const
{
    QStringList folders = QDir(mProfilesDir).entryList(QDir::Dirs | QDir::NoDotAndDotDot);
    std::sort(folders.begin(), folders.end(), [](const QString& a, const QString& b) {
        return a.compare(b, Qt::CaseInsensitive) < 0;
    });
    QStringList sorted;
    for (const QString& name : order()) {
        if (folders.contains(name) && !sorted.contains(name)) {
            sorted.append(name);
        }
    }
    for (const QString& folder : std::as_const(folders)) {
        if (!sorted.contains(folder)) {
            sorted.append(folder);
        }
    }
    QList<Profile> list;
    for (const QString& folder : std::as_const(sorted)) {
        list.append(read(folder));
    }
    return list;
}

QString ProfileStore::find(const QString& name) const
{
    for (const QString& folder : QDir(mProfilesDir).entryList(QDir::Dirs | QDir::NoDotAndDotDot)) {
        if (folder.compare(name, Qt::CaseInsensitive) == 0) {
            return folder;
        }
    }
    return {};
}

Profile ProfileStore::read(const QString& folder) const
{
    Profile profile;
    profile.name = folder;
    profile.host = readItem(folder, QStringLiteral("url"));
    bool portOk = false;
    const int port = readItem(folder, QStringLiteral("port")).toInt(&portOk);
    // Host's default, as Mudlet connects a profile with no port stored
    profile.port = portOk && port > 0 && port <= 65535 ? port : 23;
    profile.tls = readItem(folder, QStringLiteral("ssl_tsl")).toInt() == Qt::Checked;
    profile.login = readItem(folder, QStringLiteral("login"));
    profile.autoReconnect = readItem(folder, QStringLiteral("autoreconnect")).toInt() == Qt::Checked;
    return profile;
}

QString ProfileStore::save(const Profile& profile, bool isNew)
{
    if (isNew && !QDir().mkpath(folderPath(profile.name))) {
        return QStringLiteral("could not create the folder %1").arg(folderPath(profile.name));
    }
    const std::pair<QString, QString> items[] = {
            {QStringLiteral("url"), profile.host},
            {QStringLiteral("port"), QString::number(profile.port)},
            {QStringLiteral("ssl_tsl"), checkState(profile.tls)},
            {QStringLiteral("login"), profile.login},
            {QStringLiteral("autoreconnect"), checkState(profile.autoReconnect)},
    };
    for (const auto& [item, value] : items) {
        if (const QString problem = writeItem(profile.name, item, value); !problem.isEmpty()) {
            // Half made, it would be listed with the details it lacks
            if (isNew) {
                QDir(folderPath(profile.name)).removeRecursively();
            }
            return problem;
        }
    }
    if (isNew) {
        markOpened(profile.name);
    }
    return {};
}

QString ProfileStore::remove(const QString& folder)
{
    if (!QDir(QDir(folderPath(folder)).filePath(QStringLiteral("current"))).removeRecursively()) {
        // What could be removed is gone, so what is left is listed as it now is
        return QStringLiteral("could not delete all of %1").arg(folderPath(folder));
    }
    QStringList names = order();
    names.removeAll(folder);
    writeOrder(names);
    return {};
}

void ProfileStore::markOpened(const QString& folder)
{
    QStringList names = order();
    names.removeAll(folder);
    names.prepend(folder);
    // Names of folders that have gone, deleted by Mudlet's own dialog say, are dropped
    names.erase(std::remove_if(names.begin(),
                               names.end(),
                               [this](const QString& name) {
                                   return !QDir(folderPath(name)).exists();
                               }),
                names.end());
    writeOrder(names);
}

QString ProfileStore::folderPath(const QString& folder) const
{
    return QDir(mProfilesDir).filePath(folder);
}

// MudletApp::readProfileData()'s format
QString ProfileStore::readItem(const QString& folder, const QString& item) const
{
    QFile file(QDir(folderPath(folder)).filePath(item));
    if (!file.open(QIODevice::ReadOnly)) {
        return {};
    }
    QDataStream in(&file);
    in.setVersion(QDataStream::Qt_5_12);
    QString value;
    in >> value;
    return value;
}

// MudletApp::writeProfileData()'s format, replaced atomically
QString ProfileStore::writeItem(const QString& folder, const QString& item, const QString& value) const
{
    QSaveFile file(QDir(folderPath(folder)).filePath(item));
    if (!file.open(QIODevice::WriteOnly)) {
        return QStringLiteral("could not write %1: %2").arg(file.fileName(), file.errorString());
    }
    QDataStream out(&file);
    out.setVersion(QDataStream::Qt_5_12);
    out << value;
    if (out.status() != QDataStream::Ok || !file.commit()) {
        return QStringLiteral("could not write %1: %2").arg(file.fileName(), file.errorString());
    }
    return {};
}

QStringList ProfileStore::order() const
{
    QFile file(mOrderPath);
    if (!file.open(QIODevice::ReadOnly)) {
        return {};
    }
    QStringList names;
    for (const QJsonValue& name : QJsonDocument::fromJson(file.readAll()).array()) {
        names.append(name.toString());
    }
    return names;
}

// Only the order: a failure costs the listing its order, never a profile
void ProfileStore::writeOrder(const QStringList& order) const
{
    QSaveFile file(mOrderPath);
    if (!file.open(QIODevice::WriteOnly) || file.write(QJsonDocument(QJsonArray::fromStringList(order)).toJson(QJsonDocument::Compact)) < 0 || !file.commit()) {
        qWarning("ProfileStore: could not write %s: %s", qPrintable(mOrderPath), qPrintable(file.errorString()));
    }
}
