package app.dispofold.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.dispofold.model.Dataset;
import app.dispofold.model.HistoryEntry;
import app.dispofold.model.Period;
import app.dispofold.model.Plan;
import app.dispofold.model.Rules;

/** JSONB document storage for master data, periods, plans, history and rules (plain JDBC). */
@ApplicationScoped
public class Store {

    /**
     * A stored plan with the figures of its last solve.
     *
     * @param inputHash fingerprint of the planning input when the plan was last solved or changed, may be null
     * @param previousHash fingerprint of the previous month's plan at that time, may be null
     */
    public record StoredPlan(Plan plan, int hardViolations, long elapsedMillis, String inputHash,
            String previousHash) {
    }

    /** A planning month with its revision; every save increments the revision. */
    public record StoredPeriod(Period period, long revision) {
    }

    /** The period was saved by someone else since it was read. */
    public static final class ConflictException extends RuntimeException {
        public ConflictException() {
            super("revision conflict");
        }
    }

    /** Held while master data is read, changed and written back (person edits, imports). */
    public static final Object DATASET_LOCK = new Object();

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper mapper;

    public Optional<Dataset> dataset() {
        return queryOne("select doc from dataset where id = 1", Dataset.class);
    }

    /** Stores the master data; periods are stored separately with {@link #savePeriod}. */
    public void saveDataset(Dataset dataset) {
        upsert("update dataset set doc = ?, updated = current_timestamp where id = 1",
                "insert into dataset (id, doc) values (1, ?)", json(dataset));
    }

    public Optional<Period> period(YearMonth month) {
        return storedPeriod(month).map(StoredPeriod::period);
    }

    public Optional<StoredPeriod> storedPeriod(YearMonth month) {
        List<StoredPeriod> periods = new ArrayList<>();
        query("select doc, revision from period where ym = ?",
                rs -> periods.add(new StoredPeriod(read(rs.getString(1), Period.class), rs.getLong(2))),
                month.toString());
        return periods.stream().findFirst();
    }

    /** Saves without a revision check (imports). @return the new revision */
    public long savePeriod(Period period) {
        return savePeriod(period, null);
    }

    /**
     * @param expectedRevision the revision the change is based on, or null for no check (a new month has 0)
     * @return the new revision
     * @throws ConflictException if the stored revision differs
     */
    public synchronized long savePeriod(Period period, Long expectedRevision) {
        String month = period.month().toString();
        String doc = json(period);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                long current = -1;
                try (PreparedStatement ps = c.prepareStatement("select revision from period where ym = ?")) {
                    ps.setString(1, month);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            current = rs.getLong(1);
                        }
                    }
                }
                if (expectedRevision != null && expectedRevision != Math.max(0, current)) {
                    c.rollback();
                    throw new ConflictException();
                }
                try (PreparedStatement ps = c.prepareStatement(current < 0
                        ? "insert into period (doc, revision, ym) values (?, ?, ?)"
                        : "update period set doc = ?, revision = ? where ym = ?")) {
                    // A new month starts at revision 1, so that two saves based on "no month yet" (0) conflict.
                    long next = Math.max(0, current) + 1;
                    bind(ps, doc, next, month);
                    ps.executeUpdate();
                    c.commit();
                    return next;
                }
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("saving period failed", e);
        }
    }

    public List<YearMonth> periodMonths() {
        List<YearMonth> months = new ArrayList<>();
        query("select ym from period order by ym", rs -> months.add(YearMonth.parse(rs.getString(1))));
        return months;
    }

    public Optional<StoredPlan> plan(YearMonth month) {
        List<StoredPlan> plans = new ArrayList<>();
        query("select doc, hard_violations, elapsed_millis, input_hash, previous_hash from plan where ym = ?",
                rs -> plans.add(storedPlan(rs)), month.toString());
        return plans.stream().findFirst();
    }

    public List<StoredPlan> plans() {
        List<StoredPlan> plans = new ArrayList<>();
        query("select doc, hard_violations, elapsed_millis, input_hash, previous_hash from plan order by ym",
                rs -> plans.add(storedPlan(rs)));
        return plans;
    }

    private StoredPlan storedPlan(ResultSet rs) throws SQLException {
        return new StoredPlan(read(rs.getString(1), Plan.class), rs.getInt(2), rs.getLong(3), rs.getString(4),
                rs.getString(5));
    }

    /** Records the fingerprints of the inputs the plan is based on. */
    public void savePlanInputs(YearMonth month, String inputHash, String previousHash) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "update plan set input_hash = ?, previous_hash = ? where ym = ?")) {
            bind(ps, inputHash, previousHash, month.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("saving plan inputs failed", e);
        }
    }

    public void savePlan(Plan plan, int hardViolations, long elapsedMillis) {
        String month = plan.month().toString();
        String doc = json(plan);
        upsert2("update plan set doc = ?, hard_violations = ?, elapsed_millis = ? where ym = ?",
                new Object[] { doc, hardViolations, elapsedMillis, month },
                "insert into plan (ym, doc, hard_violations, elapsed_millis) values (?, ?, ?, ?)",
                new Object[] { month, doc, hardViolations, elapsedMillis });
    }

    /** Finalized history entries of all months. */
    public List<HistoryEntry> history() {
        List<HistoryEntry> entries = new ArrayList<>();
        query("select doc from history order by ym, person_id",
                rs -> entries.add(read(rs.getString(1), HistoryEntry.class)));
        return entries;
    }

    /** Replaces the finalized history of one month. */
    public void saveHistory(String month, List<HistoryEntry> entries) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement delete = c.prepareStatement("delete from history where ym = ?");
                    PreparedStatement insert = c.prepareStatement(
                            "insert into history (person_id, ym, doc) values (?, ?, ?)")) {
                delete.setString(1, month);
                delete.executeUpdate();
                for (HistoryEntry e : entries) {
                    insert.setString(1, e.personId());
                    insert.setString(2, month);
                    insert.setString(3, json(e));
                    insert.addBatch();
                }
                insert.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("saving history failed", e);
        }
    }

    /**
     * History of closed months merged with the imported history. Imported entries are actual values from the
     * source system and win over the closed plan of the same person and month.
     */
    public List<HistoryEntry> mergedHistory(List<HistoryEntry> imported) {
        return merge(history(), imported);
    }

    /**
     * An imported entry wins over the closed plan's entry of the same person and month for every value it has; a
     * balance it leaves empty (unknown) is taken from the closed plan.
     */
    static List<HistoryEntry> merge(List<HistoryEntry> closed, List<HistoryEntry> imported) {
        Map<String, HistoryEntry> merged = new LinkedHashMap<>();
        for (HistoryEntry e : closed) {
            merged.put(e.personId() + "/" + e.month(), e);
        }
        for (HistoryEntry e : imported == null ? List.<HistoryEntry> of() : imported) {
            HistoryEntry plan = merged.get(e.personId() + "/" + e.month());
            if (plan != null && (e.hoursAccountAfter() == null || e.compRestBalanceAfter() == null)) {
                e = new HistoryEntry(e.personId(), e.month(), e.source(), e.nights(), e.weekendsWorked(),
                        e.holidays(), e.unpopular(), e.wishes(), e.wishesMet(), e.hours(),
                        e.hoursAccountAfter() != null ? e.hoursAccountAfter() : plan.hoursAccountAfter(),
                        e.compRestBalanceAfter() != null ? e.compRestBalanceAfter() : plan.compRestBalanceAfter(),
                        e.compRestUsed(), e.compRestEarned());
            }
            merged.put(e.personId() + "/" + e.month(), e);
        }
        return List.copyOf(merged.values());
    }

    public Rules rules() {
        return queryOne("select doc from rules where id = 1", Rules.class).orElseGet(Rules::defaults);
    }

    public void saveRules(Rules rules) {
        upsert("update rules set doc = ? where id = 1", "insert into rules (id, doc) values (1, ?)", json(rules));
    }

    private interface RowHandler {
        void row(ResultSet rs) throws SQLException;
    }

    private void query(String sql, RowHandler handler, Object... params) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    handler.row(rs);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed", e);
        }
    }

    private <T> Optional<T> queryOne(String sql, Class<T> type, Object... params) {
        List<T> result = new ArrayList<>();
        query(sql, rs -> result.add(read(rs.getString(1), type)), params);
        return result.stream().findFirst();
    }


    /** Update of a single-row table, or insert when the row does not exist yet. */
    private void upsert(String updateSql, String insertSql, String doc) {
        upsert2(updateSql, new Object[] { doc }, insertSql, new Object[] { doc });
    }

    /** Portable upsert (PostgreSQL and H2): update, and insert when nothing was updated, in one transaction. */
    private synchronized void upsert2(String updateSql, Object[] updateParams, String insertSql,
            Object[] insertParams) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement update = c.prepareStatement(updateSql)) {
                bind(update, updateParams);
                if (update.executeUpdate() == 0) {
                    try (PreparedStatement insert = c.prepareStatement(insertSql)) {
                        bind(insert, insertParams);
                        insert.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("update failed", e);
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // no cause: Jackson messages may quote stored values
            throw new IllegalStateException("cannot serialise " + value.getClass().getSimpleName());
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            // no cause: Jackson messages may quote stored values
            throw new IllegalStateException("stored " + type.getSimpleName() + " is unreadable");
        }
    }
}
