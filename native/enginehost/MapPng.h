#pragma once

#include <QByteArray>
#include <QImage>

// A map's picture as the PNG its frame carries, with the pixels Qt's own PNG writer would give it; empty if it could
// not be made. Qt's writer tries every row filter and compresses hard, which takes several times as long as painting
// the map did, for each frame of a pan or a walk, on the thread that also runs the game. A map is flat colour, lines
// and names, so this writes the stream itself: a pixel that repeats the one before it or the one above it costs a few
// bits and nothing is searched for. A picture that is mostly anything else, as a map with a photograph for a label is,
// would come out as large as its pixels, so that one is left to Qt's writer.
QByteArray mapPng(QImage image);

// One map's pictures as PNGs, one after another. Mudlet asks for the map to be drawn again for much that leaves its
// picture as it was, a change to its info box among it, which the picture leaves out, and the engine then draws the
// mapper's map and the app's, whichever changed. A picture can only be told from the last one once it is drawn: so
// the last one's pixels are kept, 4 bytes each, and a picture that is that one over again gets its PNG without
// another being made.
class MapPicture
{
public:
    QByteArray png(QImage image);
    // Lets go of the picture kept, for a map that is no longer drawn
    void clear();

private:
    QImage mPixels;
    QByteArray mPng;
};
