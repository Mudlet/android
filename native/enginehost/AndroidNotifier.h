#pragma once

#include <QStringList>

namespace AndroidNotifier {
// A tell from that profile's scripts; profile is empty for one from no profile. expireMs: how long it shows, 0 until
// the player takes it away
void postTell(const QString& profile, const QString& title, const QString& text, int expireMs);
// The open profiles the engine notification lists, in the order opened, each with its connection status; with none,
// the engine leaves the foreground
void showProfiles(const QStringList& names, const QStringList& connections);
// The app left the front (away) or came back
void appState(bool away);
// While the app is away: a profile's unread count and newest line, and whether it asks for the player's attention
void gameText(const QString& profile, qint64 unread, const QString& latest, bool attention);
void gameClosed(const QString& profile);
}
