package com.squaretoast.menu;

import com.squaretoast.domain.menu.Availability;
import com.squaretoast.domain.menu.MenuItem;
import com.squaretoast.domain.menu.ModifierGroup;
import com.squaretoast.domain.menu.ModifierOption;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** One menu (with derived 86 state) serves POS, guest QR and KDS alike; manual 86 toggles inventory. */
@RestController
class MenuController {

    private final MenuRepository menus;
    private final EightySixService eightySix;

    MenuController(MenuRepository menus, EightySixService eightySix) {
        this.menus = menus;
        this.eightySix = eightySix;
    }

    @GetMapping(path = "/locations/{locationId}/menu", version = "1")
    ResponseEntity<MenuView> menu(@PathVariable UUID locationId) {
        return menus.load(locationId).map(m -> ResponseEntity.ok(view(m))).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping(path = "/locations/{locationId}/inventory/{inventoryId}/eighty-six", version = "1")
    ResponseEntity<Void> setEightySixed(@PathVariable UUID locationId, @PathVariable UUID inventoryId,
                                        @RequestBody EightySixRequest body) {
        return eightySix.set(locationId, inventoryId, body.eightySixed())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private static MenuView view(LocationMenu menu) {
        Availability a = Availability.of(menu.levels());
        return new MenuView(menu.items().stream().map(i -> item(i, a, menu.currency().code())).toList());
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

    record EightySixRequest(boolean eightySixed) {}

    record MenuView(List<ItemView> items) {}

    record ItemView(UUID id, String name, long priceCents, String currency, boolean available, List<GroupView> modifierGroups) {}

    record GroupView(UUID id, String name, int minSelect, int maxSelect, List<OptionView> options) {}

    record OptionView(UUID id, String name, long priceDeltaCents, boolean available) {}
}
