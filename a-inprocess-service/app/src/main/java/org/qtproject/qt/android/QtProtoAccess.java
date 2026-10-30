package org.qtproject.qt.android;

import android.app.Service;

/**
 * Reaches package-private QtNative state. Living in Qt's package makes a Qt upgrade that
 * renames these fail at compile time rather than at run time, as reflection would.
 */
public final class QtProtoAccess {
    private QtProtoAccess() {}

    public static boolean qtStarted() {
        return QtNative.getStateDetails().isStarted;
    }

    /** Qt::ApplicationState as Qt's Java side last set it (0 suspended, 1 hidden, 2 inactive, 4 active). */
    public static int applicationState() {
        return QtNative.getStateDetails().state;
    }

    /** QtServiceBase.onCreate() skips this when Qt is already running; keep QtNative's weak ref live. */
    public static void setService(Service service) {
        QtNative.setService(service);
    }

    public static boolean serviceValid() {
        return QtNative.isServiceValid();
    }
}
