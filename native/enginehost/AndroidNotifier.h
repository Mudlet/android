#pragma once

#include <QStringList>

namespace AndroidNotifier {
// A tell from that profile's scripts; profile is empty for one from no profile
void postTell(const QString& profile, const QString& title, const QString& text);
// The open profiles the engine notification lists, in the order opened, each with its connection status; with none,
// the engine leaves the foreground
void showProfiles(const QStringList& names, const QStringList& connections);
}
