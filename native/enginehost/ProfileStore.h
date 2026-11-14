#pragma once

#include "Profile.h"

#include <QList>
#include <QString>
#include <QStringList>

// libmudlet's profiles, read and written in its own files until THeadlessSession manages them: a profile is a folder
// under <configDir>/profiles, and its connection details are the files Mudlet's connection dialog writes there (url,
// port, ssl_tsl, login, autoreconnect), each one QString in a QDataStream. Mudlet keeps no order, so the order they
// were last opened in is a file of this engine's own, <configDir>/profile-order.json.
class ProfileStore
{
public:
    explicit ProfileStore(const QString& configDir);

    // The last opened or created first, then those this engine has not opened, by name
    QList<Profile> profiles() const;
    // The folder's own spelling of a profile's name, matched ignoring case as Mudlet matches it; empty for none
    QString find(const QString& name) const;
    Profile read(const QString& folder) const;
    // Writes every detail, making the folder for a new profile; why it could not, empty once done
    QString save(const Profile& profile, bool isNew);
    QString remove(const QString& folder);
    void markOpened(const QString& folder);

private:
    QString folderPath(const QString& folder) const;
    QString readItem(const QString& folder, const QString& item) const;
    QString writeItem(const QString& folder, const QString& item, const QString& value) const;
    QStringList order() const;
    void writeOrder(const QStringList& order) const;

    QString mProfilesDir;
    QString mOrderPath;
};
