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
