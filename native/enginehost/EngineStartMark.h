#pragma once
// Measure only (perf-engine-measure branch): a CLOCK_BOOTTIME stamp on the log, SystemClock.elapsedRealtime()'s clock
#include <QtGlobal>
#include <ctime>

inline void engineMark(const char* what)
{
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    const long long us = static_cast<long long>(ts.tv_sec) * 1000000 + ts.tv_nsec / 1000;
    qInfo("MUDLET_T %s %lld.%03lld", what, us / 1000, us % 1000);
}
