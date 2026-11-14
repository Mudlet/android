#pragma once

#include <QString>

#include <optional>

// What the host asks of an engine: the stand-in, or libmudlet behind MudletEngine
class IEngine
{
public:
    virtual ~IEngine() = default;
    // tls: whether to encrypt the game connection; unset, as the engine would choose
    virtual void connectTo(const QString& host, quint16 port, std::optional<bool> tls) = 0;
    virtual void send(const QString& line) = 0;
    virtual void disconnectFromGame() = 0;
    virtual void runLua(const QString& code) = 0;
    virtual void setViewWidthPx(int px) = 0;
};
