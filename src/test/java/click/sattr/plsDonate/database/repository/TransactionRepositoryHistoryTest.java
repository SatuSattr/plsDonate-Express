package click.sattr.plsDonate.database.repository;

import click.sattr.plsDonate.database.repository.TransactionRepository.TransactionRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the per-player history queries against a real temporary SQLite database,
 * mirroring {@link TransactionRepositoryClaimTest}. Verifies sandbox/VOID exclusion,
 * case-insensitive name matching, recency ordering, and pagination.
 */
class TransactionRepositoryHistoryTest {

    private Path dbFile;
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("plsdonate-history-test", ".db");
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE transactions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "tx_id TEXT UNIQUE, " +
                    "amount REAL, " +
                    "donor_name TEXT, " +
                    "donor_uuid TEXT, " +
                    "checksum TEXT, " +
                    "status TEXT, " +
                    "timestamp INTEGER, " +
                    "completed_at INTEGER, " +
                    "is_sandbox INTEGER DEFAULT 0)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null) conn.close();
        if (dbFile != null) Files.deleteIfExists(dbFile);
    }

    private void insert(String txId, String name, double amount, String status, long timestamp, boolean sandbox) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO transactions (tx_id, amount, donor_name, status, timestamp, is_sandbox) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, txId);
            ps.setDouble(2, amount);
            ps.setString(3, name);
            ps.setString(4, status);
            ps.setLong(5, timestamp);
            ps.setInt(6, sandbox ? 1 : 0);
            ps.executeUpdate();
        }
    }

    @Test
    void includesCompletedAndPendingButExcludesSandboxAndVoid() throws Exception {
        insert("a", "Steve", 5000, "COMPLETED", 100, false);
        insert("b", "Steve", 3000, "PENDING", 200, false);
        insert("c", "Steve", 9000, "VOID", 300, false);
        insert("d", "Steve", 7000, "COMPLETED", 400, true); // sandbox

        List<TransactionRecord> history = TransactionRepository.queryPlayerHistory(conn, "Steve", null, 50, 0);

        assertEquals(2, history.size());
        assertTrue(history.stream().anyMatch(r -> r.txId().equals("a")));
        assertTrue(history.stream().anyMatch(r -> r.txId().equals("b")));
        assertEquals(2, TransactionRepository.queryPlayerHistoryCount(conn, "Steve", null));
    }

    @Test
    void nameMatchIsCaseInsensitive() throws Exception {
        insert("a", "Steve", 5000, "COMPLETED", 100, false);
        insert("b", "Steve", 3000, "COMPLETED", 200, false);

        assertEquals(2, TransactionRepository.queryPlayerHistory(conn, "steve", null, 50, 0).size());
        assertEquals(2, TransactionRepository.queryPlayerHistory(conn, "STEVE", null, 50, 0).size());
        assertEquals(2, TransactionRepository.queryPlayerHistoryCount(conn, "sTeVe", null));
    }

    @Test
    void otherPlayersAreNotIncluded() throws Exception {
        insert("a", "Steve", 5000, "COMPLETED", 100, false);
        insert("b", "Alex", 3000, "COMPLETED", 200, false);

        List<TransactionRecord> history = TransactionRepository.queryPlayerHistory(conn, "Steve", null, 50, 0);
        assertEquals(1, history.size());
        assertEquals("a", history.get(0).txId());
    }

    @Test
    void orderedByMostRecentFirst() throws Exception {
        insert("old", "Steve", 5000, "COMPLETED", 100, false);
        insert("new", "Steve", 3000, "COMPLETED", 300, false);
        insert("mid", "Steve", 4000, "PENDING", 200, false);

        List<TransactionRecord> history = TransactionRepository.queryPlayerHistory(conn, "Steve", null, 50, 0);
        assertEquals("new", history.get(0).txId());
        assertEquals("mid", history.get(1).txId());
        assertEquals("old", history.get(2).txId());
    }

    @Test
    void paginationLimitsAndOffsets() throws Exception {
        for (int i = 0; i < 5; i++) {
            insert("tx" + i, "Steve", 1000 + i, "COMPLETED", 100 + i, false);
        }

        List<TransactionRecord> page1 = TransactionRepository.queryPlayerHistory(conn, "Steve", null, 2, 0);
        List<TransactionRecord> page2 = TransactionRepository.queryPlayerHistory(conn, "Steve", null, 2, 2);

        assertEquals(2, page1.size());
        assertEquals(2, page2.size());
        // newest first: tx4, tx3 | tx2, tx1
        assertEquals("tx4", page1.get(0).txId());
        assertEquals("tx2", page2.get(0).txId());
        assertEquals(5, TransactionRepository.queryPlayerHistoryCount(conn, "Steve", null));
    }

    @Test
    void emptyHistoryForUnknownPlayer() throws Exception {
        insert("a", "Steve", 5000, "COMPLETED", 100, false);
        assertTrue(TransactionRepository.queryPlayerHistory(conn, "Nobody", null, 50, 0).isEmpty());
        assertEquals(0, TransactionRepository.queryPlayerHistoryCount(conn, "Nobody", null));
    }
}
