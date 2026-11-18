package com.squaretoast.sync;

import com.squaretoast.device.DevicePrincipal;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The POS outbox endpoint. Each mutation succeeds or fails on its own; one conflict never blocks the batch. */
@RestController
class SyncController {

    static final int MAX_BATCH = 50;

    record Batch(@Nullable List<MutationApplier.Mutation> mutations) {}

    record Results(List<MutationApplier.Result> results) {}

    private final MutationApplier applier;

    SyncController(MutationApplier applier) {
        this.applier = applier;
    }

    @PostMapping(path = "/sync/mutations", version = "1")
    ResponseEntity<Results> push(@RequestAttribute(DevicePrincipal.ATTRIBUTE) DevicePrincipal device, @RequestBody Batch batch) {
        List<MutationApplier.Mutation> mutations = batch.mutations() == null ? List.of() : batch.mutations();
        if (mutations.size() > MAX_BATCH) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(new Results(mutations.stream().map(m -> applier.apply(device, m)).toList()));
    }
}
