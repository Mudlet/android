#include "TelnetDecoder.h"

void TelnetDecoder::feed(const QByteArray& bytes)
{
    QByteArray data;
    data.reserve(bytes.size());
    auto flushData = [&]() {
        if (!data.isEmpty() && onData) {
            onData(data);
        }
        data.clear();
    };

    for (const char ch : bytes) {
        const auto b = static_cast<unsigned char>(ch);
        switch (mState) {
        case State::Data:
            if (b == IAC) {
                mState = State::Iac;
            } else {
                data.append(ch);
            }
            break;
        case State::Iac:
            if (b == IAC) {
                data.append(ch);
                mState = State::Data;
            } else if (b == DO || b == DONT || b == WILL || b == WONT) {
                mVerb = b;
                mState = State::Option;
            } else if (b == SB) {
                mState = State::Sub;
            } else {
                if (b == GA || b == EOR) {
                    flushData();
                    if (onGoAhead) {
                        onGoAhead();
                    }
                }
                mState = State::Data;
            }
            break;
        case State::Option:
            // Answering only DO/WILL (never the negatives) cannot loop.
            if (onReply && (mVerb == DO || mVerb == WILL)) {
                QByteArray reply;
                reply.append(char(IAC));
                reply.append(char(mVerb == DO ? WONT : DONT));
                reply.append(ch);
                onReply(reply);
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
    flushData();
}

QByteArray TelnetDecoder::escapeOutgoing(const QByteArray& bytes)
{
    QByteArray out;
    out.reserve(bytes.size());
    for (const char ch : bytes) {
        out.append(ch);
        if (static_cast<unsigned char>(ch) == IAC) {
            out.append(ch);
        }
    }
    return out;
}
