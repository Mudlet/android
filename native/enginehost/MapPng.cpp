#include "MapPng.h"

#include <QBuffer>
#include <QtEndian>

#include <algorithm>
#include <array>
#include <cstring>
#include <zlib.h>

namespace {

// Bits as a deflate stream takes them (RFC 1951, 3.1.1): a Huffman code from its first bit, the extra bits that follow
// it from their lowest
struct Code
{
    quint32 bits = 0;
    int count = 0;
};

constexpr quint32 reversed(quint32 code, int count)
{
    quint32 result = 0;
    for (int i = 0; i < count; ++i) {
        result = (result << 1) | ((code >> i) & 1);
    }
    return result;
}

// The fixed code of a literal or length symbol (RFC 1951, 3.2.6)
constexpr Code fixedCode(int symbol)
{
    if (symbol < 144) {
        return {reversed(0x30 + symbol, 8), 8};
    }
    if (symbol < 256) {
        return {reversed(0x190 + symbol - 144, 9), 9};
    }
    if (symbol < 280) {
        return {reversed(symbol - 256, 7), 7};
    }
    return {reversed(0xC0 + symbol - 280, 8), 8};
}

constexpr int minMatch = 3;
constexpr int maxMatch = 258;
constexpr int maxDistance = 32768;

constexpr std::array<Code, 256> literalCodes = [] {
    std::array<Code, 256> codes;
    for (int byte = 0; byte < 256; ++byte) {
        codes[byte] = fixedCode(byte);
    }
    return codes;
}();

// By length, from minMatch
constexpr std::array<Code, maxMatch + 1> lengthCodes = [] {
    constexpr int base[29] = {3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258};
    constexpr int extra[29] = {0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0};
    std::array<Code, maxMatch + 1> codes;
    for (int length = minMatch; length <= maxMatch; ++length) {
        int i = 28;
        while (base[i] > length) {
            --i;
        }
        const Code symbol = fixedCode(257 + i);
        codes[length] = {symbol.bits | (quint32(length - base[i]) << symbol.count), symbol.count + extra[i]};
    }
    return codes;
}();

constexpr Code distanceCode(int distance)
{
    constexpr int base[30] = {1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577};
    constexpr int extra[30] = {0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13};
    int i = 29;
    while (base[i] > distance) {
        --i;
    }
    return {reversed(quint32(i), 5) | (quint32(distance - base[i]) << 5), 5 + extra[i]};
}

constexpr int bytesPerPixel = 4;
constexpr Code samePixel = distanceCode(bytesPerPixel);

class BitWriter
{
public:
    explicit BitWriter(uchar* out)
    : mOut(out)
    {
    }

    // At most 32 bits a call
    void put(const Code& code)
    {
        mBits |= quint64(code.bits) << mCount;
        mCount += code.count;
        if (mCount >= 32) {
            qToLittleEndian<quint32>(quint32(mBits), mOut);
            mOut += 4;
            mBits >>= 32;
            mCount -= 32;
        }
    }

    // `bytes` that repeat the ones `distance` before them. A match is 3 to 258 bytes, so a longer run is cut where
    // what is left of it is still a match.
    void putMatch(qsizetype bytes, const Code& distance)
    {
        while (bytes > 0) {
            const int length = bytes <= maxMatch ? int(bytes) : bytes - maxMatch >= minMatch ? maxMatch : int(bytes - minMatch);
            put(lengthCodes[length]);
            put(distance);
            bytes -= length;
        }
    }

    // Where the next whole byte goes, once the bits of an unfinished one are out
    uchar* finish()
    {
        for (; mCount > 0; mCount -= 8) {
            *mOut++ = uchar(mBits);
            mBits >>= 8;
        }
        mCount = 0;
        return mOut;
    }

    // Past the bytes written and the ones the bits not yet written will take
    const uchar* reach() const { return mOut + 8; }

private:
    uchar* mOut;
    quint64 mBits = 0;
    int mCount = 0;
};

void putChunkStart(uchar* at, quint32 dataSize, const char* type)
{
    qToBigEndian<quint32>(dataSize, at);
    std::memcpy(at + 4, type, 4);
}

// The checksum of the chunk that starts at `at`, written after its `dataSize` bytes; returns where the next chunk goes
uchar* putChunkEnd(uchar* at, quint32 dataSize)
{
    uchar* end = at + 8 + dataSize;
    qToBigEndian<quint32>(quint32(crc32(0, at + 4, 4 + dataSize)), end);
    return end + 4;
}

constexpr int signatureSize = 8;
constexpr int headerSize = 13;
constexpr int chunkOverhead = 12;

// Empty if the picture would take more than `budget` bytes of stream. `image` is Format_RGBA8888.
QByteArray runPng(const QImage& image, qsizetype budget)
{
    const int width = image.width();
    const int height = image.height();
    const qsizetype stride = qsizetype(width) * bytesPerPixel;
    // A row is its filter byte and its pixels, so the pixel above is this far back; deflate reaches back 32768 bytes
    const Code pixelAbove = stride + 1 <= maxDistance ? distanceCode(int(stride + 1)) : Code{};
    // All of it as literals of 9 bits
    const qsizetype rowAtMost = (stride + 1) * 9 / 8 + 1;

    const qsizetype streamAt = signatureSize + chunkOverhead + headerSize + 8;
    QByteArray png(streamAt + budget + rowAtMost + 64 + 2 * chunkOverhead, Qt::Uninitialized);
    uchar* const start = reinterpret_cast<uchar*>(png.data());
    std::memcpy(start, "\x89PNG\r\n\x1a\n", signatureSize);
    uchar* const header = start + signatureSize;
    putChunkStart(header, headerSize, "IHDR");
    qToBigEndian<quint32>(quint32(width), header + 8);
    qToBigEndian<quint32>(quint32(height), header + 12);
    // 8 bits a channel, RGBA, and the only compression, filter and interlace methods there are, the last being none
    std::memcpy(header + 16, "\x08\x06\x00\x00\x00", 5);
    uchar* const data = putChunkEnd(header, headerSize);

    uchar* const stream = data + 8;
    // A zlib stream of deflate with a 32K window, marked as the fastest compression (RFC 1950)
    stream[0] = 0x78;
    stream[1] = 0x01;
    BitWriter out(stream + 2);
    // The last block, with the fixed Huffman codes: nothing is counted to build a table from
    out.put({0b011, 3});
    const uchar* const limit = stream + budget;
    const uchar noFilter = 0;
    uLong sum = adler32(0, nullptr, 0);
    for (int y = 0; y < height; ++y) {
        const auto* line = reinterpret_cast<const quint32*>(image.constScanLine(y));
        const auto* above = y > 0 && pixelAbove.count ? reinterpret_cast<const quint32*>(image.constScanLine(y - 1)) : nullptr;
        sum = adler32(sum, &noFilter, 1);
        sum = adler32(sum, reinterpret_cast<const Bytef*>(line), uInt(stride));
        out.put(literalCodes[noFilter]);
        int x = 0;
        while (x < width) {
            // How many pixels from here repeat the one before, and how many the ones above them
            int same = 0;
            if (x > 0) {
                const quint32 before = line[x - 1];
                while (x + same < width && line[x + same] == before) {
                    ++same;
                }
            }
            int over = 0;
            if (above && x + same < width) {
                while (x + over < width && line[x + over] == above[x + over]) {
                    ++over;
                }
            }
            // The one before is the nearer, so the cheaper to name
            if (same > 0 && same >= over) {
                out.putMatch(qsizetype(same) * bytesPerPixel, samePixel);
                x += same;
            } else if (over > 0) {
                out.putMatch(qsizetype(over) * bytesPerPixel, pixelAbove);
                x += over;
            } else {
                const auto* pixel = reinterpret_cast<const uchar*>(line + x);
                for (int i = 0; i < bytesPerPixel; ++i) {
                    out.put(literalCodes[pixel[i]]);
                }
                ++x;
            }
        }
        if (out.reach() > limit) {
            return {};
        }
    }
    constexpr int endOfBlock = 256;
    out.put(fixedCode(endOfBlock));
    uchar* end = out.finish();
    qToBigEndian<quint32>(quint32(sum), end);
    end += 4;

    const quint32 dataSize = quint32(end - stream);
    putChunkStart(data, dataSize, "IDAT");
    end = putChunkEnd(data, dataSize);
    putChunkStart(end, 0, "IEND");
    end = putChunkEnd(end, 0);
    png.truncate(end - start);
    return png;
}

} // namespace

QByteArray mapPng(QImage image)
{
    if (image.isNull()) {
        return {};
    }
    // What Qt's writer makes of a picture in another format before it writes it, so the pixels are its own
    const QImage pixels = std::move(image).convertToFormat(QImage::Format_RGBA8888);
    if (pixels.isNull()) {
        return {};
    }
    // A quarter of the pixels' own bytes: a map takes a tenth of them at most, a photograph all of them. Nor more than
    // half a frame of the protocol (SocketBridge's maxFrameBytes), where the PNG is base64 and the map's rooms follow.
    const qsizetype budget = std::min<qsizetype>(pixels.sizeInBytes() / 4, 8 * 1024 * 1024);
    QByteArray png = runPng(pixels, budget);
    png.clear();
    QBuffer buffer(&png);
    buffer.open(QIODevice::WriteOnly);
    if (!pixels.save(&buffer, "PNG")) {
        return {};
    }
    return png;
}
