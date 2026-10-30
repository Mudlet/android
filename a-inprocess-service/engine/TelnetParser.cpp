#include "TelnetParser.h"

void TelnetParser::feed(const QByteArray& chunk)
{
    for (const char ch : chunk) {
        const auto b = static_cast<unsigned char>(ch);
        switch (mState) {
        case State::Data:
            if (b == IAC) {
                mState = State::Iac;
            } else if (onByte) {
                onByte(ch);
            }
            break;
        case State::Iac:
            if (b == IAC) {
                if (onByte) {
                    onByte(ch);
                }
                mState = State::Data;
            } else if (b == WILL || b == WONT || b == DO || b == DONT) {
                mVerb = b;
                mState = State::Option;
            } else if (b == SB) {
                mState = State::Sub;
            } else {
                if ((b == GA || b == EOR) && onGoAhead) {
                    onGoAhead();
                }
                mState = State::Data;
            }
            break;
        case State::Option:
            if (onReply) {
                if (mVerb == DO) {
                    onReply(QByteArray{"\xff\xfc", 2} + ch);
                } else if (mVerb == WILL) {
                    onReply(QByteArray{"\xff\xfe", 2} + ch);
                }
            }
            mState = State::Data;
            break;
        case State::Sub:
            if (b == IAC) {
                mState = State::SubIac;
            }
            break;
        case State::SubIac:
            mState = (b == SE) ? State::Data : State::Sub;
            break;
        }
    }
}
