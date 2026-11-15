#pragma once

#include <QString>

namespace AndroidNotifier {
void postTell(const QString& title, const QString& text);
// The profile the engine notification names; empty when none is open
void showProfile(const QString& profile);
}
