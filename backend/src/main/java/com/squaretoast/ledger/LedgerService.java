package com.squaretoast.ledger;

import com.squaretoast.domain.ledger.JournalLine;
import com.squaretoast.domain.ledger.JournalTxn;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Appends journal transactions. Must join a caller's transaction: the balance triggers are deferred to commit. */
@Service
public class LedgerService {

    private final JdbcClient db;

    LedgerService(JdbcClient db) {
        this.db = db;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(UUID locationId, JournalTxn txn) {
        UUID id = UUID.randomUUID();
        db.sql("insert into journal_txn(id, location_id, kind, source_ref) values (?, ?, ?, ?)")
                .params(id, locationId, txn.kind(), txn.sourceRef()).update();
        for (JournalLine line : txn.lines()) {
            db.sql("insert into journal_line(txn_id, account, amount_cents, currency) values (?, ?, ?, ?)")
                    .params(id, line.account().name(), line.amount().cents(), line.amount().currency().code()).update();
        }
        return id;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasEntry(String kind, String sourceRef) {
        return db.sql("select exists (select 1 from journal_txn where kind = ? and source_ref = ?)")
                .params(kind, sourceRef).query(Boolean.class).single();
    }
}
