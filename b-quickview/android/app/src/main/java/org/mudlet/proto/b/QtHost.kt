package org.mudlet.proto.b

import android.content.Context
import org.mudlet.proto.b.qt.mudengine.MudEngine.Hidden
import org.qtproject.qt.android.QtQmlStatus
import org.qtproject.qt.android.QtQmlStatusChangeListener
import org.qtproject.qt.android.QtQuickView

object QtHost {
    /**
     * loadContent() is what starts Qt: it runs QtEmbeddedLoader and calls main() on Qt's thread,
     * whether or not the view is ever attached to a window. The Context decides which Qt delegate
     * (Activity or Service) owns Qt's lifetime. Hidden is generated from qtapp/Hidden.qml by
     * QT_ANDROID_GENERATE_JAVA_QTQUICKVIEW_CONTENTS, which the Qt Gradle Plugin turns on.
     */
    fun create(context: Context): QtQuickView {
        EngineBridge.noteQtStartRequested(context.javaClass.simpleName)
        val view = QtQuickView(context)
        view.setStatusChangeListener(object : QtQmlStatusChangeListener {
            override fun onStatusChanged(status: QtQmlStatus) {
                EngineBridge.onStatus("qml", status.name)
            }
        })
        view.loadContent(Hidden())
        return view
    }
}
