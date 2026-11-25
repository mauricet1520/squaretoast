package com.squaretoast.menu;

import com.squaretoast.domain.menu.Availability;
import com.squaretoast.domain.menu.MenuItem;
import com.squaretoast.domain.menu.ModifierGroup;
import com.squaretoast.domain.menu.ModifierOption;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/** The menu JSON shared by POS, KDS and guest QR, with each item and option's derived 86 state. */
public final class MenuViews {

    private MenuViews() {}

    /** Items for which {@code include} is true (e.g. only QR-visible ones for guests). */
    public static MenuView view(LocationMenu menu, Predicate<UUID> include) {
        Availability a = Availability.of(menu.levels());
        return new MenuView(menu.items().stream().filter(i -> include.test(i.id()))
                .map(i -> item(i, a, menu.currency().code())).toList());
    }

    private static ItemView item(MenuItem i, Availability a, String currency) {
        return new ItemView(i.id(), i.name(), i.price().cents(), currency, a.item(i),
                i.modifierGroups().stream().map(g -> group(g, a)).toList());
    }

    private static GroupView group(ModifierGroup g, Availability a) {
        return new GroupView(g.id(), g.name(), g.minSelect(), g.maxSelect(), g.options().stream().map(o -> option(o, a)).toList());
    }

    private static OptionView option(ModifierOption o, Availability a) {
        return new OptionView(o.id(), o.name(), o.priceDeltaCents(), a.option(o));
    }

    public record MenuView(List<ItemView> items) {}

    public record ItemView(UUID id, String name, long priceCents, String currency, boolean available, List<GroupView> modifierGroups) {}

    public record GroupView(UUID id, String name, int minSelect, int maxSelect, List<OptionView> options) {}

    public record OptionView(UUID id, String name, long priceDeltaCents, boolean available) {}
}
