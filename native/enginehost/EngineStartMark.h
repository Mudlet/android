#pragma once
// Measure only (perf-engine-measure branch): a CLOCK_BOOTTIME stamp on the log, SystemClock.elapsedRealtime()'s clock
#include <QtGlobal>
#include <ctime>
#ifdef Q_OS_ANDROID
#include <sys/system_properties.h>
#endif

inline void engineMark(const char* what)
{
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    const long long us = static_cast<long long>(ts.tv_sec) * 1000000 + ts.tv_nsec / 1000;
    qInfo("MUDLET_T %s %lld.%03lld", what, us / 1000, us % 1000);
}

// Measure only: the base's behaviour in the A/B rounds, from adb's setprop debug.mudlet.base 1 or MUDLET_MEASURE_BASE
inline bool measureBase()
{
#ifdef Q_OS_ANDROID
    char value[PROP_VALUE_MAX] = {};
    __system_property_get("debug.mudlet.base", value);
    return value[0] == '1';
#else
    return qEnvironmentVariableIsSet("MUDLET_MEASURE_BASE");
#endif
}
