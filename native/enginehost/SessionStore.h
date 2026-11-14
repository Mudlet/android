#pragma once

#include <QList>
#include <QString>

// What the engine keeps on disk so that, started again after it was killed, it can bring back the profile it ran and
// its game connection (docs/architecture.md, "Restoring the session"). One JSON file, replaced atomically on each
// change. An orderly stop removes it; a stop marker, which the app writes when the user stops the engine, removes it
// at the next start-up, in case the stop ended the process before the engine could.
class SessionStore
{
public:
    // Restores within this long count towards a crash loop
    static constexpr qint64 restoreWindowMs = 10 * 60 * 1000;
    // A start that finds this many restores within the window restores nothing
    static constexpr int maxRestores = 3;
    // How long the n-th restore within the window waits before it connects, so a game is not called again at once by
    // an engine that keeps dying
    static constexpr int reconnectDelayMs[maxRestores] = {0, 10 * 1000, 30 * 1000};

    struct Session
    {
        // Empty while no profile runs
        QString profile;
        // The game the UI last connected to; empty for the address stored in the profile
        QString host;
        int port = 0;
        // The connection is wanted: the profile is connected, or would connect again on its own
        bool connect = false;

        bool operator==(const Session&) const = default;
    };

    struct Restore
    {
        enum class Kind {
            // Nothing to restore: no profile ran, or the engine before this one stopped in order
            none,
            // The engine before this one died with a profile running
            restore,
            // It did, but this many restores within the window came before: a crash loop
            gaveUp
        };
        Kind kind = Kind::none;
        Session session;
        // This restore's number within the window, from 1; for gaveUp, how many came before
        int attempt = 0;
        int reconnectDelayMs = 0;
    };

    SessionStore(const QString& path, const QString& stopMarkerPath);

    // Once, at start-up: what the engine before this one left, which a restore then counts in the file before it
    // starts, so that one which kills the engine still counts
    Restore takeRestore(qint64 nowMs);
    // The session as it is now, kept with the restores of the window
    void save(const Session& session);
    // An orderly stop: nothing to restore
    void clear();

    QString path() const { return mPath; }

private:
    bool write(const Session& session);

    QString mPath;
    QString mStopMarkerPath;
    // When the restores within the window started, in ms since the epoch
    QList<qint64> mRestores;
};
