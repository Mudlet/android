// The map's PNG writer (enginehost/MapPng) against Qt's own: the same pixels from each picture, the cheap stream for a
// picture like a map, Qt's writer for one that is not. Run with the path of the picture the instrumented tests decode
// (app/src/androidTest/assets/map-picture.png), which is this writer's; WRITE_MAP_PICTURE=1 writes it anew.
#include "MapPng.h"

#include <QBuffer>
#include <QFile>
#include <QGuiApplication>
#include <QPainter>
#include <QtEndian>

#include <cstdio>
#include <optional>
#include <zlib.h>

namespace {

int failures = 0;

void check(bool passed, const char* picture, const char* what)
{
    if (!passed) {
        ++failures;
        std::fprintf(stderr, "FAIL %s: %s\n", picture, what);
    }
}

QByteArray qtPng(const QImage& image)
{
    QByteArray png;
    QBuffer buffer(&png);
    buffer.open(QIODevice::WriteOnly);
    image.save(&buffer, "PNG");
    return png;
}

QImage pixelsOf(const QByteArray& png)
{
    return QImage::fromData(png, "PNG").convertToFormat(QImage::Format_RGBA8888);
}

struct Png
{
    int width = 0;
    int height = 0;
    QByteArray header;
    int dataChunks = 0;
    QByteArray stream;
};

std::optional<Png> parsed(const QByteArray& png)
{
    if (!png.startsWith(QByteArray("\x89PNG\r\n\x1a\n", 8))) {
        return std::nullopt;
    }
    Png result;
    qsizetype at = 8;
    while (at + 12 <= png.size()) {
        const auto size = qFromBigEndian<quint32>(png.constData() + at);
        const QByteArray type = png.mid(at + 4, 4);
        if (at + 12 + size > png.size()) {
            return std::nullopt;
        }
        const QByteArray data = png.mid(at + 8, size);
        const auto sum = qFromBigEndian<quint32>(png.constData() + at + 8 + size);
        if (sum != crc32(0, reinterpret_cast<const Bytef*>(png.constData() + at + 4), 4 + size)) {
            return std::nullopt;
        }
        if (type == "IHDR" && size == 13) {
            result.width = int(qFromBigEndian<quint32>(data.constData()));
            result.height = int(qFromBigEndian<quint32>(data.constData() + 4));
            result.header = data.mid(8);
        } else if (type == "IDAT") {
            ++result.dataChunks;
            result.stream += data;
        }
        at += 12 + size;
    }
    return at == png.size() ? std::optional<Png>(result) : std::nullopt;
}

// The stream this writer makes and Qt's does not: 8-bit RGBA in one chunk, marked as the fastest compression, one
// last block of the fixed Huffman codes, and no row filtered. zlib checks the stream's own checksum as it inflates.
bool cheap(const QByteArray& png)
{
    const std::optional<Png> file = parsed(png);
    if (!file || file->header != QByteArray("\x08\x06\x00\x00\x00", 5) || file->dataChunks != 1 || file->stream.size() < 3) {
        return false;
    }
    const auto* stream = reinterpret_cast<const uchar*>(file->stream.constData());
    if (stream[0] != 0x78 || stream[1] != 0x01 || (stream[2] & 0b111) != 0b011) {
        return false;
    }
    const qsizetype row = qsizetype(file->width) * 4 + 1;
    QByteArray rows(row * file->height, Qt::Uninitialized);
    uLongf size = uLongf(rows.size());
    if (uncompress(reinterpret_cast<Bytef*>(rows.data()), &size, stream, uLong(file->stream.size())) != Z_OK || qsizetype(size) != rows.size()) {
        return false;
    }
    for (int y = 0; y < file->height; ++y) {
        if (rows.at(y * row) != 0) {
            return false;
        }
    }
    return true;
}

enum class Writer { cheap, qt };

void checkPicture(const char* name, const QImage& image, Writer expected)
{
    const QByteArray png = mapPng(image);
    check(!png.isEmpty(), name, "no PNG");
    const QImage pixels = pixelsOf(png);
    check(pixels.size() == image.size(), name, "the PNG is not the picture's size");
    check(pixels == pixelsOf(qtPng(image)), name, "the pixels are not the ones Qt's PNG has");
    check(cheap(png) == (expected == Writer::cheap), name, expected == Writer::cheap ? "not the cheap stream" : "the cheap stream, for a picture it does not suit");
}

// Each pixel from its place alone, so that the instrumented test (MapPngTest.kt) can say what it must be, and none
// translucent, as Android keeps those premultiplied and gives back a rounded one: rows of nothing, rows of one colour,
// a pixel and 65 that repeat it (260 bytes, two more than one match holds), pixels that repeat nothing, and a line
// down it that repeats the pixel above
constexpr int pictureWidth = 80;
constexpr int pictureHeight = 24;

QRgb picturePixel(int x, int y)
{
    if (y < 4) {
        return qRgba(0, 0, 0, 0);
    }
    if (x == 7) {
        return qRgb(255, 255, 255);
    }
    if (y < 12) {
        return qRgb(0x20, 0x40, 0x60);
    }
    if (y < 16) {
        return x < 74 ? qRgb(0x33, 0x66, 0x99) : qRgb(0, 0, 0);
    }
    if (x < 60) {
        return qRgb(0x80, 0, 0);
    }
    return qRgb((x * 37 + y * 101) & 0xFF, ((x * 59) ^ (y * 83)) & 0xFF, (x + y * 7) & 0xFF);
}

QImage picture()
{
    QImage image(pictureWidth, pictureHeight, QImage::Format_ARGB32_Premultiplied);
    for (int y = 0; y < pictureHeight; ++y) {
        for (int x = 0; x < pictureWidth; ++x) {
            image.setPixel(x, y, picturePixel(x, y));
        }
    }
    return image;
}

// As the engine draws a map: lines, rooms and a translucent marker, antialiased, on a picture of the device's pixels
QImage mapLike(const QSize& size, double scale, const QColor& background)
{
    QImage image(size * scale, QImage::Format_ARGB32_Premultiplied);
    image.setDevicePixelRatio(scale);
    image.fill(background);
    QPainter painter(&image);
    painter.setRenderHint(QPainter::Antialiasing);
    const double cell = size.width() / 8.0;
    for (int row = 0; row * cell < size.height(); ++row) {
        for (int column = 0; column < 8; ++column) {
            if ((row * 5 + column * 3) % 4 == 0) {
                continue;
            }
            const QPointF middle((column + 0.5) * cell, (row + 0.5) * cell);
            painter.setPen(QPen(QColor(Qt::lightGray), 1.0));
            painter.drawLine(middle, middle + QPointF(cell, 0));
            painter.drawLine(middle, middle + QPointF(cell, cell));
            painter.setBrush(QColor(row % 2 ? Qt::darkCyan : Qt::darkGreen));
            painter.drawRect(QRectF(middle - QPointF(cell / 4, cell / 4), QSizeF(cell / 2, cell / 2)));
        }
    }
    QRadialGradient marker(QPointF(cell * 1.5, cell * 1.5), cell * 0.4);
    marker.setColorAt(0, Qt::white);
    marker.setColorAt(0.7, QColor(255, 0, 0, 200));
    marker.setColorAt(0.95, QColor(255, 0, 0, 150));
    marker.setColorAt(1, Qt::transparent);
    painter.setPen(Qt::NoPen);
    painter.setBrush(marker);
    painter.drawEllipse(QPointF(cell * 1.5, cell * 1.5), cell * 0.4, cell * 0.4);
    return image;
}

// Pixels that repeat nothing, from the row `from` down; translucent ones among them
QImage noisy(QImage image, int from)
{
    quint32 state = 12345;
    for (int y = from; y < image.height(); ++y) {
        for (int x = 0; x < image.width(); ++x) {
            state = state * 1664525 + 1013904223;
            image.setPixel(x, y, qPremultiply(state | (x % 3 ? 0xFF000000 : 0)));
        }
    }
    return image;
}

// Pixels that repeat nothing but are no noise either, in the rows above `to`: the cheap stream spells each out, Qt's
// writer finds the pattern
QImage sloped(QImage image, int to)
{
    for (int y = 0; y < to; ++y) {
        auto* line = reinterpret_cast<QRgb*>(image.scanLine(y));
        for (int x = 0; x < image.width(); ++x) {
            line[x] = qRgb(x & 0xFF, (x + y) & 0xFF, y & 0xFF);
        }
    }
    return image;
}

QImage filled(int width, int height, const QColor& colour, QImage::Format format = QImage::Format_ARGB32_Premultiplied)
{
    QImage image(width, height, format);
    image.fill(colour);
    return image;
}

} // namespace

int main(int argc, char** argv)
{
    const QGuiApplication application(argc, argv);
    if (argc != 2) {
        std::fprintf(stderr, "usage: %s <app/src/androidTest/assets/map-picture.png>\n", argv[0]);
        return 2;
    }

    checkPicture("the instrumented tests' picture", picture(), Writer::cheap);
    checkPicture("a map on black", mapLike(QSize(360, 640), 2.625, Qt::black), Writer::cheap);
    checkPicture("a map on nothing", mapLike(QSize(360, 640), 3, Qt::transparent), Writer::cheap);
    checkPicture("a map on a translucent colour", mapLike(QSize(200, 120), 1, QColor(10, 20, 30, 40)), Writer::cheap);
    checkPicture("one colour, opaque format", filled(300, 200, Qt::darkBlue, QImage::Format_RGB32), Writer::cheap);
    checkPicture("one colour, not premultiplied", filled(300, 200, QColor(200, 100, 50, 128), QImage::Format_ARGB32), Writer::cheap);
    checkPicture("one row", filled(300, 1, Qt::black), Writer::cheap);
    checkPicture("a few columns", filled(8, 300, Qt::black), Writer::cheap);
    // 8192 pixels and a filter byte are more than deflate reaches back, so the row above is not there to repeat
    checkPicture("rows longer than deflate reaches back", mapLike(QSize(8192, 16), 1, Qt::black), Writer::cheap);
    checkPicture("the last rows repeat nothing", noisy(filled(256, 256, Qt::black), 240), Writer::cheap);
    checkPicture("most rows repeat nothing", noisy(filled(256, 256, Qt::black), 128), Writer::qt);
    checkPicture("nothing repeats", noisy(filled(64, 64, Qt::black), 0), Writer::qt);
    checkPicture("one pixel", filled(1, 1, Qt::red), Writer::qt);
    // 12 MB as the cheap stream: under a quarter of the picture's 64 MiB, over the half of a frame the writer keeps to
    checkPicture("the largest map, too large for a frame as the cheap stream", sloped(filled(4096, 4096, Qt::black), 700), Writer::qt);
    check(mapPng(QImage()).isEmpty(), "no picture", "a PNG of it");

    // The engine's thread pays for each byte, and the app's for parsing it: a phone's screen of one colour, 10 MB of
    // pixels, is runs alone
    const QByteArray flat = mapPng(filled(1080, 2340, Qt::black));
    check(cheap(flat) && flat.size() < 150 * 1024, "a screen of one colour", "over 150 KB, or not the cheap stream");

    const QByteArray png = mapPng(picture());
    QFile file(QString::fromLocal8Bit(argv[1]));
    if (qEnvironmentVariableIsSet("WRITE_MAP_PICTURE")) {
        check(file.open(QIODevice::WriteOnly) && file.write(png) == png.size(), argv[1], "not written");
    } else {
        check(file.open(QIODevice::ReadOnly) && file.readAll() == png, argv[1], "not the PNG this writer makes of the picture: WRITE_MAP_PICTURE=1 writes it, and MapPngTest.kt has its pixels");
    }

    if (failures) {
        std::fprintf(stderr, "%d checks failed\n", failures);
        return 1;
    }
    std::printf("the map's PNG writer: all checks passed\n");
    return 0;
}
