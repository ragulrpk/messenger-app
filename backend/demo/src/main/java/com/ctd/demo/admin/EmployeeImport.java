package com.ctd.demo.admin;

import com.ctd.demo.user.EmployeeFields;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates employee accounts from a CSV file (UTF-8, header row; Excel can "Save as CSV UTF-8").
 *
 *  <p>Columns, in any order: {@code gpf_number}, {@code username}, {@code name}, {@code email}, {@code mobile},
 *  {@code cadre}, {@code password}. Each row needs a name, an email, and a GPF number or (for employees without
 *  one) a username. The GPF number, lower-cased, is the login ID. Mobile, cadre and password are optional.
 *
 *  <p>Every account starts with a temporary password: the first sign-in asks for an emailed code and a new
 *  password. Rows without a password get one nobody knows; those employees use "Forgot password" to set theirs.
 *
 *  <p>Rows that are invalid or that duplicate a login ID, GPF number, email or mobile (in the file or already in
 *  the database) are skipped and reported by line number; the valid rows are created together. Reported reasons
 *  never repeat the row's values, so the report is safe to log. */
@Service
public class EmployeeImport {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    private final TransactionTemplate transactions;

    public EmployeeImport(JdbcTemplate db, PasswordEncoder encoder, TransactionTemplate transactions) {
        this.db = db;
        this.encoder = encoder;
        this.transactions = transactions;
    }

    public record Problem(int line, String reason) { }
    public record Report(int imported, List<Problem> skipped) { }
    private record Row(int line, String loginId, String gpf, String name, String email, String mobile, String cadre,
                       String password) { }

    /** @param passwords whether a password column is accepted (the server-side import) or refused (the console)
     *  @param actor the administrator, or null for the server-side import
     *  @param maxRows refuse larger files */
    public Report importCsv(Reader reader, boolean passwords, UUID actor, int maxRows) throws IOException {
        var records = parse(reader);
        if (records.isEmpty()) throw new IllegalArgumentException("The file is empty.");
        var header = new HashMap<String, Integer>();
        var headings = records.getFirst();
        for (int i = 0; i < headings.size(); i++)
            header.put(headings.get(i).strip().toLowerCase(Locale.ROOT).replace(' ', '_').replace("﻿", ""), i);
        for (String required : List.of("name", "email"))
            if (!header.containsKey(required)) throw new IllegalArgumentException("The file needs a \"" + required + "\" column.");
        if (!header.containsKey("gpf_number") && !header.containsKey("username"))
            throw new IllegalArgumentException("The file needs a \"gpf_number\" or \"username\" column.");
        if (!passwords && header.containsKey("password"))
            throw new IllegalArgumentException("Remove the password column. Employees added here set their own password with \"Forgot password\".");
        if (records.size() - 1 > maxRows) throw new IllegalArgumentException("Add at most " + maxRows + " people per file.");

        var taken = new Taken(db);
        var rows = new ArrayList<Row>();
        var problems = new ArrayList<Problem>();
        for (int index = 1; index < records.size(); index++) {
            var cells = records.get(index);
            int line = index + 1;
            if (cells.stream().allMatch(String::isBlank)) continue;
            try {
                String gpfCell = cell(cells, header, "gpf_number");
                String gpf = gpfCell.isBlank() ? null : EmployeeFields.gpf(gpfCell);
                String loginId = gpf != null ? gpf.toLowerCase(Locale.ROOT) : EmployeeFields.username(cell(cells, header, "username"));
                String email = EmployeeFields.email(cell(cells, header, "email"));
                String mobile = EmployeeFields.mobile(cell(cells, header, "mobile"));
                String password = passwords ? cell(cells, header, "password") : "";
                if (password.getBytes(StandardCharsets.UTF_8).length > 72)
                    throw new IllegalArgumentException("Password must be 72 UTF-8 bytes or fewer.");
                var row = new Row(line, loginId, gpf, EmployeeFields.name(cell(cells, header, "name")), email, mobile,
                        EmployeeFields.cadre(cell(cells, header, "cadre")), password.isEmpty() ? null : password);
                taken.claim(row);
                rows.add(row);
            } catch (IllegalArgumentException exception) {
                problems.add(new Problem(line, exception.getMessage()));
            }
        }
        if (rows.isEmpty()) return new Report(0, problems);

        var hashes = hash(rows);
        transactions.executeWithoutResult(status -> {
            Timestamp now = Timestamp.from(Instant.now());
            db.batchUpdate("INSERT INTO app_users(id,username,display_name,password_hash,enabled,created_at,administrator,"
                            + "must_change_password,gpf_number,email,mobile,cadre) VALUES (?,?,?,?,TRUE,?,FALSE,TRUE,?,?,?,?)",
                    rows, 500, (statement, row) -> {
                        statement.setObject(1, UUID.randomUUID());
                        statement.setString(2, row.loginId());
                        statement.setString(3, row.name());
                        statement.setString(4, hashes.get(row.line()));
                        statement.setTimestamp(5, now);
                        statement.setString(6, row.gpf());
                        statement.setString(7, row.email());
                        statement.setString(8, row.mobile());
                        statement.setString(9, row.cadre());
                    });
            db.update("INSERT INTO account_audit(id,occurred_at,actor_id,target_id,action,detail) VALUES (?,?,?,?,?,?)",
                    UUID.randomUUID(), now, actor, null, "ACCOUNTS_IMPORTED", rows.size() + " accounts");
        });
        return new Report(rows.size(), problems);
    }

    /** BCrypt is deliberately slow (about a quarter second each), so given passwords are hashed in parallel. Rows
     *  without one share the hash of a random password that is discarded at once: nobody can sign in with it. */
    private Map<Integer, String> hash(List<Row> rows) {
        var hashes = new HashMap<Integer, String>();
        byte[] random = new byte[24];
        RANDOM.nextBytes(random);
        String unknown = encoder.encode(Base64.getEncoder().encodeToString(random));
        try (var pool = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors()))) {
            var pending = new HashMap<Integer, Future<String>>();
            for (var row : rows) {
                if (row.password() == null) hashes.put(row.line(), unknown);
                else pending.put(row.line(), pool.submit(() -> encoder.encode(row.password())));
            }
            for (var entry : pending.entrySet()) hashes.put(entry.getKey(), entry.getValue().get());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Import interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Password hashing failed", exception.getCause());
        }
        return hashes;
    }

    /** Login IDs, GPF numbers, emails and mobiles already used in the database or earlier in the file. */
    private static final class Taken {
        private final Map<String, String> owners = new HashMap<>();

        Taken(JdbcTemplate db) {
            db.query("SELECT username,gpf_number,email,mobile FROM app_users", rs -> {
                put("login ID", rs.getString(1), "an existing account");
                put("GPF number", rs.getString(2), "an existing account");
                put("email", rs.getString(3), "an existing account");
                put("mobile", rs.getString(4), "an existing account");
            });
        }

        void claim(Row row) {
            var fields = new LinkedHashMap<String, String>();
            fields.put("login ID", row.loginId());
            fields.put("GPF number", row.gpf());
            fields.put("email", row.email());
            fields.put("mobile", row.mobile());
            for (var field : fields.entrySet()) {
                String owner = field.getValue() == null ? null
                        : owners.get(field.getKey() + ":" + field.getValue().toLowerCase(Locale.ROOT));
                if (owner != null) throw new IllegalArgumentException("Same " + field.getKey() + " as " + owner + ".");
            }
            fields.forEach((field, value) -> put(field, value, "line " + row.line()));
        }

        private void put(String field, String value, String owner) {
            if (value != null) owners.putIfAbsent(field + ":" + value.toLowerCase(Locale.ROOT), owner);
        }
    }

    private static String cell(List<String> cells, Map<String, Integer> header, String column) {
        Integer index = header.get(column);
        return index == null || index >= cells.size() ? "" : cells.get(index);
    }

    /** RFC 4180 CSV: quoted fields may hold commas, quotes ("") and line breaks; a leading BOM is ignored. */
    static List<List<String>> parse(Reader source) throws IOException {
        // Looking one character ahead needs mark/reset.
        Reader reader = source.markSupported() ? source : new java.io.BufferedReader(source);
        var records = new ArrayList<List<String>>();
        var record = new ArrayList<String>();
        var field = new StringBuilder();
        boolean quoted = false, started = false;
        int c;
        while ((c = reader.read()) != -1) {
            char ch = (char) c;
            if (quoted) {
                if (ch == '"') {
                    reader.mark(1);
                    int next = reader.read();
                    if (next == '"') field.append('"');
                    else {
                        quoted = false;
                        if (next != -1) reader.reset();
                    }
                } else field.append(ch);
            } else if (ch == '"' && field.isEmpty()) {
                quoted = true;
                started = true;
            } else if (ch == ',') {
                record.add(field.toString());
                field.setLength(0);
                started = true;
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r') {
                    reader.mark(1);
                    if (reader.read() != '\n') reader.reset();
                }
                if (started || !field.isEmpty() || !record.isEmpty()) {
                    record.add(field.toString());
                    records.add(record);
                }
                record = new ArrayList<>();
                field.setLength(0);
                started = false;
            } else if (ch != '﻿' || started || !field.isEmpty() || !record.isEmpty() || !records.isEmpty()) {
                field.append(ch);
                started = true;
            }
        }
        if (started || !field.isEmpty() || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }
}
