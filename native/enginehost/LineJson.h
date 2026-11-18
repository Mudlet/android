#pragma once

#include <QByteArray>
#include <QList>
#include <QStringView>

struct ColorRun;

// The frames a console's lines go out in (docs/protocol.md: `line`, `lineUpdated`, `tail`), written straight to the
// bytes QJsonDocument::toJson(Compact) gives the same QJsonObject. A game's spam is a frame for each line, and
// building a QJsonObject of a QJsonArray for each run, to be written out once, cost the engine four times what writing
// it does. tests/line_json_test.cpp holds these to Qt's bytes.
QByteArray lineJson(qint64 seq, QStringView text, const QList<ColorRun>& runs, bool prompt, qint64 time, bool replay);
QByteArray lineUpdatedJson(qint64 seq, QStringView text, const QList<ColorRun>& runs, bool prompt);
QByteArray lineDeletedJson(qint64 seq);
QByteArray tailJson(QStringView text, const QList<ColorRun>& runs, bool prompt, qint64 time);
