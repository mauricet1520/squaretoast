package com.squaretoast.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.squaretoast.admin.PermissionResolver.Grant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Slice B2: what a user may do at a scope is the union of the grants whose scope covers it, and nothing more. */
class PermissionResolverTest {

    private final UUID merchant = UUID.randomUUID();
    private final UUID east = UUID.randomUUID();
    private final UUID west = UUID.randomUUID();
    private final UUID north = UUID.randomUUID();
    private final UUID coastGroup = UUID.randomUUID(); // east + west

    private Set<UUID> groupsOf(UUID location) {
        return location.equals(north) ? Set.of() : Set.of(coastGroup);
    }

    private Set<Permission> atLocation(List<Grant> grants, UUID location) {
        return PermissionResolver.atScope(grants, merchant, ScopeKind.LOCATION, location, groupsOf(location));
    }

    @Test
    void locationGrantDoesNotLeakToSiblingLocation() {
        List<Grant> gm = List.of(new Grant(ScopeKind.LOCATION, east, EnumSet.of(Permission.MENU_EDIT, Permission.STAFF_MANAGE)));

        assertThat(atLocation(gm, east)).containsExactlyInAnyOrder(Permission.MENU_EDIT, Permission.STAFF_MANAGE);
        assertThat(atLocation(gm, west)).isEmpty();
        assertThat(PermissionResolver.atScope(gm, merchant, ScopeKind.GROUP, coastGroup, Set.of())).isEmpty();
        assertThat(PermissionResolver.atScope(gm, merchant, ScopeKind.MERCHANT, merchant, Set.of())).isEmpty();
    }

    @Test
    void groupGrantCoversMemberLocationsOnly() {
        List<Grant> admin = List.of(new Grant(ScopeKind.GROUP, coastGroup, EnumSet.of(Permission.MENU_PUBLISH)));

        assertThat(atLocation(admin, east)).containsExactly(Permission.MENU_PUBLISH);
        assertThat(atLocation(admin, west)).containsExactly(Permission.MENU_PUBLISH);
        assertThat(atLocation(admin, north)).isEmpty();
        assertThat(PermissionResolver.atScope(admin, merchant, ScopeKind.GROUP, coastGroup, Set.of()))
                .containsExactly(Permission.MENU_PUBLISH);
        assertThat(PermissionResolver.atScope(admin, merchant, ScopeKind.MERCHANT, merchant, Set.of()))
                .as("a group grant is not merchant-wide").isEmpty();
    }

    @Test
    void merchantOwnerCoversAll() {
        List<Grant> owner = List.of(new Grant(ScopeKind.MERCHANT, merchant, EnumSet.allOf(Permission.class)));

        assertThat(atLocation(owner, east)).containsExactlyInAnyOrder(Permission.values());
        assertThat(atLocation(owner, north)).containsExactlyInAnyOrder(Permission.values());
        assertThat(PermissionResolver.atScope(owner, merchant, ScopeKind.GROUP, coastGroup, Set.of()))
                .containsExactlyInAnyOrder(Permission.values());
        assertThat(PermissionResolver.atScope(owner, UUID.randomUUID(), ScopeKind.MERCHANT, UUID.randomUUID(), Set.of()))
                .as("another merchant").isEmpty();
    }

    @Test
    void grantsAddUp() {
        List<Grant> both = List.of(
                new Grant(ScopeKind.GROUP, coastGroup, EnumSet.of(Permission.REPORTS_SALES)),
                new Grant(ScopeKind.LOCATION, east, EnumSet.of(Permission.VOID)));

        assertThat(atLocation(both, east)).containsExactlyInAnyOrder(Permission.REPORTS_SALES, Permission.VOID);
        assertThat(atLocation(both, west)).containsExactly(Permission.REPORTS_SALES);
    }

    @Test
    void ownerHoldsEveryPermission() {
        assertThat(SystemRole.OWNER.permissions()).containsExactlyInAnyOrder(Permission.values());
        assertThat(SystemRole.STAFF.permissions()).isEmpty();
        assertThat(SystemRole.GM.permissions()).doesNotContain(Permission.MENU_PUBLISH, Permission.PRICE_EDIT);
    }
}
