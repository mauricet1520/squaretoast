package com.squaretoast.menu;

import com.squaretoast.realtime.RealtimePublisher;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Manual 86 of an inventory item, broadcast to every POS/QR/KDS client of the location. */
@Service
public class EightySixService {

    private final JdbcClient jdbc;
    private final MenuRepository menus;
    private final RealtimePublisher realtime;

    EightySixService(JdbcClient jdbc, MenuRepository menus, RealtimePublisher realtime) {
        this.jdbc = jdbc;
        this.menus = menus;
        this.realtime = realtime;
    }

    /** False when the inventory item does not exist at that location. */
    @Transactional
    public boolean set(UUID location, UUID inventory, boolean eightySixed) {
        int updated = jdbc.sql("update inventory_item set manually_86ed = ?, version = version + 1 where id = ? and location_id = ?")
                .params(eightySixed, inventory, location).update();
        if (updated == 0) {
            return false;
        }
        realtime.publish(location, AvailabilityEvents.TOPIC,
                () -> AvailabilityEvents.payload(menus.load(location).orElseThrow(), Set.of(inventory)));
        return true;
    }
}
