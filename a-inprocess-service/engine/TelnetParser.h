#pragma once

#include <QByteArray>

#include <functional>

// Minimal telnet: strips IAC sequences (state survives chunk boundaries), refuses every
// option (DO -> WONT, WILL -> DONT) and reports GA/EOR so the caller can flush a prompt.
class TelnetParser
{
public:
    enum : unsigned char {
        SE = 240, GA = 249, SB = 250, WILL = 251, WONT = 252, DO = 253, DONT = 254, IAC = 255, EOR = 239
    };

    std::function<void(char)> onByte;
    std::function<void()> onGoAhead;
    std::function<void(const QByteArray&)> onReply;

    void feed(const QByteArray& chunk);
    void reset() { mState = State::Data; }

private:
    enum class State { Data, Iac, Option, Sub, SubIac };
    State mState = State::Data;
    unsigned char mVerb = 0;
};
