#pragma once

#include <QByteArray>
#include <functional>

// Minimal telnet: strips IAC sequences, refuses every option, reports GA/EOR.
// Keeps state between feeds, so sequences split across TCP reads are handled.
class TelnetDecoder
{
public:
    static constexpr unsigned char IAC = 255, DONT = 254, DO = 253, WONT = 252, WILL = 251,
                                   SB = 250, GA = 249, SE = 240, EOR = 239;

    std::function<void(const QByteArray&)> onData;
    std::function<void(const QByteArray&)> onReply;
    std::function<void()> onGoAhead;

    void feed(const QByteArray& bytes);
    static QByteArray escapeOutgoing(const QByteArray& bytes);

private:
    enum class State { Data, Iac, Option, Sub, SubIac };
    State mState = State::Data;
    unsigned char mVerb = 0;
};
