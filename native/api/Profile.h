#pragma once

#include <QString>

// A profile as the UI sees it: a name and the game it connects to. The password is never part of it.
struct Profile
{
    QString name;
    QString host;
    int port = 23;
    bool tls = false;
    QString login;
    bool autoReconnect = false;

    bool operator==(const Profile&) const = default;

    // Why name cannot be a new profile's name, empty if it can: Mudlet's rules for a profile folder
    // (MudletApp's), refused rather than corrected so the UI shows the name the engine will use
    static QString newNameProblem(const QString& name)
    {
        if (name.trimmed().isEmpty()) {
            return QStringLiteral("a profile needs a name");
        }
        if (name != name.trimmed()) {
            return QStringLiteral("a profile name cannot start or end with a space");
        }
        static const QString allowed = QStringLiteral(". _()0123456789-#&");
        for (const QChar c : name) {
            if (!(c >= QLatin1Char('a') && c <= QLatin1Char('z')) && !(c >= QLatin1Char('A') && c <= QLatin1Char('Z')) && !allowed.contains(c)) {
                return QStringLiteral("a profile name can have only the letters A to Z, digits, spaces and . _ ( ) - # &");
            }
        }
        if (name == QLatin1String(".") || name.contains(QLatin1String(".."))) {
            return QStringLiteral("a profile name cannot be \".\" or have \"..\" in it");
        }
        return {};
    }
};
