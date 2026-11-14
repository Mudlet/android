#include "AndroidNotifier.h"

#include <QCoreApplication>
#include <QJniObject>
#include <QtCore/qcoreapplication_platform.h>

namespace AndroidNotifier {

void postTell(const QString& title, const QString& text)
{
    // In a QtService process the Qt context is the Service, which may post notifications
    const auto context = QNativeInterface::QAndroidApplication::context();
    QJniObject::callStaticMethod<void>("org/mudlet/android/engine/EngineNotifications",
                                       "postTell",
                                       "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)V",
                                       context.object(),
                                       QJniObject::fromString(title).object<jstring>(),
                                       QJniObject::fromString(text).object<jstring>());
}

} // namespace AndroidNotifier
