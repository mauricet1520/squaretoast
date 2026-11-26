package com.squaretoast.menu;

import com.squaretoast.device.DevicePrincipal;
import com.squaretoast.menu.MenuViews.MenuView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The location's full menu (with derived 86 state) for its own devices, and manual 86 toggles. Another
 * location's device gets 404, as if the location did not exist. Guests reach the menu through their table's QR.
 */
@RestController
class MenuController {

    private final MenuRepository menus;
    private final EightySixService eightySix;

    MenuController(MenuRepository menus, EightySixService eightySix) {
        this.menus = menus;
        this.eightySix = eightySix;
    }

    @GetMapping(path = "/locations/{locationId}/menu", version = "1")
    ResponseEntity<MenuView> menu(@RequestAttribute(DevicePrincipal.ATTRIBUTE) DevicePrincipal device,
                                  @PathVariable UUID locationId) {
        if (!device.locationId().equals(locationId)) {
            return ResponseEntity.notFound().build();
        }
        return menus.load(locationId).map(m -> ResponseEntity.ok(MenuViews.view(m, id -> true)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(path = "/locations/{locationId}/inventory", version = "1")
    ResponseEntity<StockList> stock(@RequestAttribute(DevicePrincipal.ATTRIBUTE) DevicePrincipal device,
                                    @PathVariable UUID locationId) {
        if (!device.locationId().equals(locationId) || !menus.locationExists(locationId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new StockList(menus.stock(locationId)));
    }

    record StockList(List<MenuRepository.StockView> items) {}

    @PutMapping(path = "/locations/{locationId}/inventory/{inventoryId}/eighty-six", version = "1")
    ResponseEntity<Void> setEightySixed(@RequestAttribute(DevicePrincipal.ATTRIBUTE) DevicePrincipal device,
                                        @PathVariable UUID locationId, @PathVariable UUID inventoryId,
                                        @RequestBody EightySixRequest body) {
        if (!device.locationId().equals(locationId)) {
            return ResponseEntity.notFound().build();
        }
        return eightySix.set(locationId, inventoryId, body.eightySixed())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    record EightySixRequest(boolean eightySixed) {}
}
