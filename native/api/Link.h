#pragma once

#include <QList>
#include <QString>

#include <optional>

// What a link of a console offers now, as THeadlessSession::Link has it
struct Link
{
    // An item of its menu; a separator has no label
    struct Item
    {
        QString label;
        bool enabled = true;
        bool separator = false;
    };

    QString tooltip;
    bool menu = false;
    QString menuTitle;
    QList<Item> items;
    bool spoiler = false;
    bool revealed = false;
    bool disabled = false;
    bool concealed = false;
    bool visited = false;
    bool selected = false;
    // How the link is painted now, in ColorRun's colours and attribute bits; unset where the run's own applies
    std::optional<int> fg;
    std::optional<int> bg;
    std::optional<int> styles;
};
