#include "AndroidNotifier.h"

#include <QCoreApplication>
#include <QJniEnvironment>
#include <QJniObject>
#include <QtCore/qcoreapplication_platform.h>

namespace AndroidNotifier {

namespace {

// A local reference, which the caller deletes: Qt's thread never returns to Java to free it
jobjectArray stringArray(QJniEnvironment& env, const QStringList& strings)
{
    jobjectArray array = env->NewObjectArray(jsize(strings.size()), env.findClass("java/lang/String"), nullptr);
    for (qsizetype i = 0; i < strings.size(); ++i) {
        env->SetObjectArrayElement(array, jsize(i), QJniObject::fromString(strings.at(i)).object<jstring>());
    }
    return array;
}

} // namespace

void postTell(const QString& profile, const QString& title, const QString& text, int expireMs)
{
    // In a QtService process the Qt context is the Service, which may post notifications
    const auto context = QNativeInterface::QAndroidApplication::context();
    QJniObject::callStaticMethod<void>("org/mudlet/android/engine/EngineNotifications",
                                       "postTell",
                                       "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)V",
                                       context.object(),
                                       QJniObject::fromString(profile).object<jstring>(),
                                       QJniObject::fromString(title).object<jstring>(),
                                       QJniObject::fromString(text).object<jstring>(),
                                       jlong(expireMs));
}

void showProfiles(const QStringList& names, const QStringList& connections)
{
    QJniEnvironment env;
    const auto context = QNativeInterface::QAndroidApplication::context();
    jobjectArray namesArray = stringArray(env, names);
    jobjectArray connectionsArray = stringArray(env, connections);
    QJniObject::callStaticMethod<void>(
            "org/mudlet/android/engine/EngineNotifications", "showProfiles", "(Landroid/content/Context;[Ljava/lang/String;[Ljava/lang/String;)V", context.object(), namesArray, connectionsArray);
    env->DeleteLocalRef(namesArray);
    env->DeleteLocalRef(connectionsArray);
}

void appState(bool away)
{
    const auto context = QNativeInterface::QAndroidApplication::context();
    QJniObject::callStaticMethod<void>("org/mudlet/android/engine/EngineNotifications", "appState", "(Landroid/content/Context;Z)V", context.object(), jboolean(away));
}

void gameText(const QString& profile, qint64 unread, const QString& latest, bool attention)
{
    const auto context = QNativeInterface::QAndroidApplication::context();
    QJniObject::callStaticMethod<void>("org/mudlet/android/engine/EngineNotifications",
                                       "gameText",
                                       "(Landroid/content/Context;Ljava/lang/String;JLjava/lang/String;Z)V",
                                       context.object(),
                                       QJniObject::fromString(profile).object<jstring>(),
                                       jlong(unread),
                                       QJniObject::fromString(latest).object<jstring>(),
                                       jboolean(attention));
}

void gameClosed(const QString& profile)
{
    const auto context = QNativeInterface::QAndroidApplication::context();
    QJniObject::callStaticMethod<void>(
            "org/mudlet/android/engine/EngineNotifications", "gameClosed", "(Landroid/content/Context;Ljava/lang/String;)V", context.object(), QJniObject::fromString(profile).object<jstring>());
}

} // namespace AndroidNotifier
