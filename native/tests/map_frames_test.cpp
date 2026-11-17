// What a map's frame costs the bridge that sends it: a message made of each frame that is another than the last, the
// PNG in base64 and the rest as JSON, and none made of a frame that is the last one over again. Nothing of that shows
// on the socket, where the last message over again was never sent, so this counts the messages made.

#include "SocketBridge.h"

#include <QCoreApplication>

#include <cstdio>
#include <utility>

namespace {

int failures = 0;

void check(bool passed, const char* map, const char* what)
{
    if (!passed) {
        std::fprintf(stderr, "FAIL %s: %s\n", map, what);
        ++failures;
    }
}

// A frame made anew, the PNG's bytes too: it is the last one by what it holds, not by sharing it
MapFrame drawn()
{
    MapViewInfo view;
    view.area = 2;
    view.areaName = QStringLiteral("Shire");
    view.zoom = 12;
    view.playerRoom = 7;
    view.roomSize = 31.5;
    view.rooms = {MapRoomPlace{7, QPointF(100.5, 50.25), QStringLiteral("The Green Dragon")}};
    view.levels = {0, 1};
    return MapFrame{
            QByteArray(300 * 1024, 'p'), QSize(411, 800), true, view, MapInfo{{MapInfoLine{QStringLiteral("Area: Shire"), true, false, QStringLiteral("#ffffff")}}, QStringLiteral("#000000"), 200}};
}

// IFrontend::map() or appMap()
using Give = void (SocketBridge::*)(const std::optional<MapFrame>&);

// How many messages were made of the frame, given that many times
int made(SocketBridge& bridge, Give give, const std::optional<MapFrame>& frame, int times = 1)
{
    const int before = SocketBridge::mapMessagesMade();
    for (int i = 0; i < times; ++i) {
        (bridge.*give)(frame);
    }
    return SocketBridge::mapMessagesMade() - before;
}

void checkFrames(Give give, const char* map)
{
    SocketBridge bridge;
    check(made(bridge, give, drawn()) == 1, map, "the first frame was not made one message");
    check(made(bridge, give, drawn(), 10) == 0, map, "the same frame, drawn again and given ten times, was made a message");

    // A frame that differs from the last in one thing, that frame over again, and the one before it
    QList<std::pair<const char*, MapFrame>> changes;
    const auto change = [&changes](const char* what) -> MapFrame& {
        changes.append({what, drawn()});
        return changes.last().second;
    };
    change("another PNG of the same length").png.back() = 'q';
    change("another size").size = QSize(411, 801);
    change("edited, not only viewed").viewOnly = false;
    change("no view").view.reset();
    change("another moveMap drawn").view->moved = 3;
    change("a room in another place").view->rooms[0].position = QPointF(100.5, 50.5);
    change("another text in the info box").info.lines[0].text = QStringLiteral("Area: Bree");
    change("an info line in italics").info.lines[0].italic = true;
    change("another alpha behind the info box").info.backgroundAlpha = 100;
    for (const auto& [what, frame] : std::as_const(changes)) {
        const int first = made(bridge, give, frame);
        const int over = made(bridge, give, frame, 10);
        const int back = made(bridge, give, drawn());
        if (first != 1 || over != 0 || back != 1) {
            std::fprintf(stderr, "FAIL %s, %s: %d messages made of it, %d of it ten times more and %d of the frame before it, for 1, 0 and 1\n", map, what, first, over, back);
            ++failures;
        }
    }

    // The control socket's bridge is given each frame of the front profile's too
    SocketBridge front;
    front.setMirror(&bridge);
    check(made(front, give, drawn()) == 1, map, "a frame the control socket's bridge had was not made one message, the profile's");
    check(made(front, give, changes.first().second) == 2, map, "a frame new to both bridges was not made a message by each");
    check(made(front, give, changes.first().second, 10) == 0, map, "that frame ten times more was made a message");
}

} // namespace

int main(int argc, char** argv)
{
    const QCoreApplication application(argc, argv);

    checkFrames(&SocketBridge::map, "map");
    checkFrames(&SocketBridge::appMap, "appMap");

    // No frame is the mapper's window gone, with its map forgotten
    SocketBridge bridge;
    bridge.map(drawn());
    check(made(bridge, &SocketBridge::map, std::nullopt) == 0, "map", "no frame was made a message");
    check(made(bridge, &SocketBridge::map, drawn()) == 1, "map", "the frame after no frame was not made a message again");
    // No frame of the app's map is a message of its own, which takes the map down, or says there is none to a UI that
    // asked for one
    check(made(bridge, &SocketBridge::appMap, std::nullopt) == 1, "appMap", "no frame, the first given, was not made its message");
    bridge.appMap(drawn());
    check(made(bridge, &SocketBridge::appMap, std::nullopt) == 1, "appMap", "no frame was not made its message");
    check(made(bridge, &SocketBridge::appMap, drawn()) == 1, "appMap", "the frame after no frame was not made a message again");

    if (failures) {
        std::fprintf(stderr, "%d checks failed\n", failures);
        return 1;
    }
    std::printf("the bridge's map frames: all checks passed\n");
    return 0;
}
