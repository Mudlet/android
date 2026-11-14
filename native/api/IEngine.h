#pragma once

#include <QString>

// What the host asks of an engine: the stand-in, or libmudlet behind MudletEngine
class IEngine
{
public:
    virtual ~IEngine() = default;
    virtual void connectTo(const QString& host, quint16 port) = 0;
    virtual void send(const QString& line) = 0;
    virtual void disconnectFromGame() = 0;
    virtual void runLua(const QString& code) = 0;
    virtual void setViewWidthPx(int px) = 0;
};
